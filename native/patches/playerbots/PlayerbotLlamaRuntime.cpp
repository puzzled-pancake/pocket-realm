#include "PlayerbotLlamaRuntime.h"

#include "PlayerbotAIConfig.h"

#include "Platform/Define.h"

#ifdef POCKETREALM_LLAMA

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <future>
#include <map>
#include <mutex>
#include <thread>
#include <vector>

#include <sys/resource.h>

#include <ggml-cpu.h>
#include <llama.h>

namespace {

struct LlmRequest
{
    std::string prompt;
    uint32 botGuid = 0;
    PlayerbotLlamaRuntime::LlmCallSource source = PlayerbotLlamaRuntime::LLM_SRC_CHAT_REPLY;
    int timeOutSeconds = 600;
    bool debug = false;
    std::promise<std::string> result;
};

struct LlmSlot
{
    int32_t seqId = -1;
    std::vector<llama_token> tokens;
    std::chrono::steady_clock::time_point lastUsed;
};

class LlamaRuntimeImpl
{
public:
    ~LlamaRuntimeImpl()
    {
        Shutdown();
    }

    bool Start()
    {
        std::lock_guard<std::mutex> lock(m_mutex);

        if (m_failed || m_model)
            return !m_failed;

        if (sPlayerbotAIConfig.llmModelPath.empty())
        {
            sLog.outError("BotLLM: llama backend selected but AiPlayerbot.LLMModelPath is empty");
            m_failed = true;
            return false;
        }

        llama_backend_init();

        llama_model_params mparams = llama_model_default_params();
        // coexistence profile: mmap lazy paging keeps real resident memory at
        // the weights actually touched; mlock would pin the whole file
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

        m_model = llama_model_load_from_file(sPlayerbotAIConfig.llmModelPath.c_str(), mparams);
        if (!m_model)
        {
            sLog.outError("BotLLM: failed to load model %s", sPlayerbotAIConfig.llmModelPath.c_str());
            m_failed = true;
            return false;
        }

        m_slotCount = std::max<uint32>(1, std::min<uint32>(8, sPlayerbotAIConfig.llmSlots));

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = sPlayerbotAIConfig.llmCtxSize * m_slotCount;
        cparams.n_seq_max = m_slotCount;
        cparams.n_threads = sPlayerbotAIConfig.llmThreads;
        cparams.n_threads_batch = sPlayerbotAIConfig.llmThreads;
        cparams.abort_callback = [](void* data) -> bool {
            return static_cast<std::atomic<bool>*>(data)->load(std::memory_order_relaxed);
        };
        cparams.abort_callback_data = &m_abort;

        m_ctx = llama_init_from_model(m_model, cparams);
        if (!m_ctx)
        {
            sLog.outError("BotLLM: failed to create llama context");
            m_failed = true;
            llama_model_free(m_model);
            m_model = nullptr;
            return false;
        }

        m_threadpool = CreatePinnedThreadpool();
        if (m_threadpool)
            llama_attach_threadpool(m_ctx, m_threadpool, m_threadpool);

        for (uint32 i = 0; i < m_slotCount; ++i)
            m_freeSeqs.push_back(static_cast<llama_seq_id>(i));

        m_running = true;
        m_worker = std::thread(&LlamaRuntimeImpl::WorkerLoop, this);
        m_watchdog = std::thread(&LlamaRuntimeImpl::WatchdogLoop, this);

        sLog.outString("BotLLM: llama runtime started (model %s, ctx %u/slot, slots %u, threads %u)",
            sPlayerbotAIConfig.llmModelPath.c_str(), sPlayerbotAIConfig.llmCtxSize, m_slotCount, sPlayerbotAIConfig.llmThreads);

        return true;
    }

    void Shutdown()
    {
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            if (!m_running)
                return;
            m_running = false;
        }
        m_cond.notify_all();
        if (m_worker.joinable())
            m_worker.join();
        if (m_watchdog.joinable())
            m_watchdog.join();

        if (m_threadpool)
        {
            ggml_threadpool_free(m_threadpool);
            m_threadpool = nullptr;
        }
        if (m_ctx)
        {
            llama_free(m_ctx);
            m_ctx = nullptr;
        }
        if (m_model)
        {
            llama_model_free(m_model);
            m_model = nullptr;
        }
    }

    std::future<std::string> Submit(LlmRequest&& request)
    {
        std::future<std::string> future = request.result.get_future();
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            if (!m_running || m_failed)
            {
                request.result.set_value("error");
                return future;
            }
            m_queue.push_back(std::move(request));
        }
        m_cond.notify_one();
        return future;
    }

    void SetCompanionMode(bool companion)
    {
        // profile switch requires a model reload; M5 wires this to the
        // sit-and-talk screen. Coexistence profile until then.
        (void)companion;
    }

    void InvalidateSlots()
    {
        std::lock_guard<std::mutex> lock(m_slotMutex);
        for (auto& pair : m_botSlots)
            m_freeSeqs.push_back(pair.second.seqId);
        m_botSlots.clear();
    }

private:
    ggml_threadpool_t CreatePinnedThreadpool()
    {
        uint32 const threads = std::max<uint32>(1, std::min<uint32>(6, sPlayerbotAIConfig.llmThreads));
        uint32 const firstCore = sPlayerbotAIConfig.llmCpuFirstCore;

        ggml_threadpool_params tparams = ggml_threadpool_params_default(threads);
        memset(tparams.cpumask, 0, sizeof(tparams.cpumask));
        for (uint32 i = 0; i < threads; ++i)
        {
            uint32 core = firstCore + i;
            if (core >= GGML_MAX_N_THREADS)
                break;
            tparams.cpumask[core] = true;
        }
        // never let ggml spill across a cluster boundary: mixing little+mid
        // cores collapses decode throughput
        tparams.strict_cpu = true;

        try
        {
            ggml_threadpool_t pool = ggml_threadpool_new(&tparams);
            if (!pool)
                sLog.outError("BotLLM: failed to create pinned threadpool, using default affinity");
            return pool;
        }
        catch (std::exception const& e)
        {
            sLog.outError("BotLLM: threadpool creation failed: %s", e.what());
            return nullptr;
        }
    }

    void WorkerLoop()
    {
        setpriority(PRIO_PROCESS, 0, 10);

        while (true)
        {
            LlmRequest request;
            {
                std::unique_lock<std::mutex> lock(m_mutex);
                m_cond.wait(lock, [this] { return !m_running || !m_queue.empty(); });
                if (!m_running)
                    return;
                request = std::move(m_queue.front());
                m_queue.pop_front();
            }

            std::string result = RunGeneration(request);
            request.result.set_value(result);
        }
    }

    void WatchdogLoop()
    {
        while (true)
        {
            {
                std::unique_lock<std::mutex> lock(m_mutex);
                m_cond.wait_for(lock, std::chrono::milliseconds(250), [this] { return !m_running; });
                if (!m_running)
                    return;
            }

            long long deadline = m_activeDeadline.load(std::memory_order_relaxed);
            if (deadline == 0)
                continue;

            auto now = std::chrono::duration_cast<std::chrono::steady_clock::duration>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            if (now >= deadline && !m_abort.load(std::memory_order_relaxed))
            {
                sLog.outError("BotLLM: generation exceeded timeout, aborting decode");
                m_abort.store(true, std::memory_order_relaxed);
            }
        }
    }

    size_t CommonPrefixLength(uint32 botGuid, std::vector<llama_token> const& tokens)
    {
        std::lock_guard<std::mutex> lock(m_slotMutex);
        auto itr = m_botSlots.find(botGuid);
        if (itr == m_botSlots.end())
            return 0;

        size_t const max = std::min(itr->second.tokens.size(), tokens.size());
        size_t common = 0;
        while (common < max && itr->second.tokens[common] == tokens[common])
            ++common;
        return common;
    }

    llama_seq_id AcquireSeq(uint32 botGuid, size_t keepPrefix)
    {
        std::lock_guard<std::mutex> lock(m_slotMutex);

        auto itr = m_botSlots.find(botGuid);
        if (itr == m_botSlots.end())
        {
            if (m_freeSeqs.empty() && !EvictOldestSlot())
                return -1;

            llama_seq_id seq = m_freeSeqs.front();
            m_freeSeqs.pop_front();
            itr = m_botSlots.emplace(botGuid, LlmSlot()).first;
            itr->second.seqId = seq;
        }

        itr->second.lastUsed = std::chrono::steady_clock::now();

        // keep the shared token prefix, drop everything after it so the
        // sequence continues from the common prefix (prefix-cache reuse)
        llama_memory_t mem = llama_get_memory(m_ctx);
        llama_memory_seq_rm(mem, itr->second.seqId, static_cast<llama_pos>(keepPrefix), -1);
        itr->second.tokens.resize(keepPrefix);

        return itr->second.seqId;
    }

    bool EvictOldestSlot()
    {
        if (m_botSlots.empty())
            return false;

        uint32 oldestGuid = m_botSlots.begin()->first;
        std::chrono::steady_clock::time_point oldest = m_botSlots.begin()->second.lastUsed;
        for (auto& pair : m_botSlots)
        {
            if (pair.second.lastUsed < oldest)
            {
                oldest = pair.second.lastUsed;
                oldestGuid = pair.first;
            }
        }

        m_freeSeqs.push_back(m_botSlots[oldestGuid].seqId);
        m_botSlots.erase(oldestGuid);
        return true;
    }

    void AppendSlotTokens(llama_seq_id seq, llama_token token)
    {
        std::lock_guard<std::mutex> lock(m_slotMutex);
        for (auto& pair : m_botSlots)
        {
            if (pair.second.seqId == seq)
            {
                pair.second.tokens.push_back(token);
                return;
            }
        }
    }

    long long DeadlineIn(int seconds)
    {
        return std::chrono::duration_cast<std::chrono::steady_clock::duration>(
            (std::chrono::steady_clock::now() + std::chrono::seconds(std::max(1, seconds))).time_since_epoch()).count();
    }

    std::string RunGeneration(LlmRequest const& request)
    {
        llama_vocab const* vocab = llama_model_get_vocab(m_model);

        int32_t needed = llama_tokenize(vocab, request.prompt.c_str(), (int32_t)request.prompt.size(), nullptr, 0, true, true);
        if (needed <= 0)
            return "error";

        std::vector<llama_token> tokens((size_t)needed);
        if (llama_tokenize(vocab, request.prompt.c_str(), (int32_t)request.prompt.size(), tokens.data(), needed, true, true) < 0)
            return "error";

        size_t const common = CommonPrefixLength(request.botGuid, tokens);

        llama_seq_id seq = AcquireSeq(request.botGuid, common);
        if (seq < 0)
            return {};

        bool aborted = false;
        m_activeDeadline.store(DeadlineIn(request.timeOutSeconds), std::memory_order_relaxed);
        std::string result = DecodeAndSample(vocab, seq, tokens, common, aborted);
        m_activeDeadline.store(0, std::memory_order_relaxed);

        if (aborted)
        {
            // one retry after dropping the sequence back to empty
            m_abort.store(false, std::memory_order_relaxed);
            sLog.outError("BotLLM: decode aborted, retrying once");

            seq = AcquireSeq(request.botGuid, 0);
            if (seq >= 0)
            {
                m_activeDeadline.store(DeadlineIn(request.timeOutSeconds), std::memory_order_relaxed);
                aborted = false;
                result = DecodeAndSample(vocab, seq, tokens, 0, aborted);
                m_activeDeadline.store(0, std::memory_order_relaxed);
            }
            if (aborted)
                return "error";
        }

        return result;
    }

    std::string DecodeAndSample(llama_vocab const* vocab, llama_seq_id seq,
        std::vector<llama_token> const& tokens, size_t offset, bool& aborted)
    {
        llama_seq_id seqId = seq;

        // prefill in batches; positions are auto-assigned from the sequence's
        // KV state after llama_memory_seq_rm trimmed it
        int32_t const batchSize = 256;
        for (size_t i = offset; i < tokens.size(); i += batchSize)
        {
            int32_t const n = static_cast<int32_t>(std::min<size_t>(batchSize, tokens.size() - i));
            llama_batch batch = llama_batch_get_one(const_cast<llama_token*>(tokens.data()) + i, n);
            for (int32_t j = 0; j < n; ++j)
            {
                batch.n_seq_id[j] = 1;
                batch.seq_id[j] = &seqId;
            }
            if (llama_decode(m_ctx, batch))
            {
                aborted = m_abort.load(std::memory_order_relaxed);
                return "error";
            }

            for (size_t j = i; j < i + (size_t)n; ++j)
                AppendSlotTokens(seq, tokens[j]);
        }

        llama_sampler* smpl = BuildSampler(vocab);
        if (!smpl)
            return "error";

        std::string output;
        llama_token last = tokens.empty() ? llama_vocab_bos(vocab) : tokens.back();

        uint32 const maxNew = std::max<uint32>(1, sPlayerbotAIConfig.llmMaxNewTokens);
        for (uint32 generated = 0; generated < maxNew; ++generated)
        {
            llama_batch batch = llama_batch_get_one(&last, 1);
            batch.n_seq_id[0] = 1;
            batch.seq_id[0] = &seqId;
            if (llama_decode(m_ctx, batch))
            {
                aborted = m_abort.load(std::memory_order_relaxed);
                llama_sampler_free(smpl);
                return aborted ? "" : "error";
            }

            last = llama_sampler_sample(smpl, m_ctx, -1);

            if (llama_vocab_is_eog(vocab, last))
                break;

            AppendSlotTokens(seq, last);

            char piece[16];
            int32_t n = llama_token_to_piece(vocab, last, piece, sizeof(piece), 0, true);
            if (n > 0)
                output.append(piece, (size_t)n);
        }

        llama_sampler_free(smpl);
        return output;
    }

    llama_sampler* BuildSampler(llama_vocab const* vocab)
    {
        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        llama_sampler* smpl = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab), 64, sPlayerbotAIConfig.llmRepeatPenalty, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sPlayerbotAIConfig.llmTopK));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sPlayerbotAIConfig.llmTopP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(sPlayerbotAIConfig.llmTemp));
        return smpl;
    }

    std::mutex m_mutex;
    std::condition_variable m_cond;
    std::deque<LlmRequest> m_queue;
    bool m_running = false;
    bool m_failed = false;

    std::mutex m_slotMutex;
    std::map<uint32, LlmSlot> m_botSlots;
    std::deque<llama_seq_id> m_freeSeqs;
    uint32 m_slotCount = 4;

    std::atomic<long long> m_activeDeadline{0};
    std::atomic<bool> m_abort{false};

    llama_model* m_model = nullptr;
    llama_context* m_ctx = nullptr;
    ggml_threadpool_t m_threadpool = nullptr;

    std::thread m_worker;
    std::thread m_watchdog;
};

LlamaRuntimeImpl& GetLlamaRuntime()
{
    static LlamaRuntimeImpl instance;
    return instance;
}

} // namespace

bool PlayerbotLlamaRuntime::Enabled()
{
    if (sPlayerbotAIConfig.llmBackend != PlayerbotAIConfig::LLM_BACKEND_LLAMA)
        return false;

    return GetLlamaRuntime().Start();
}

std::string PlayerbotLlamaRuntime::Generate(const std::string& prompt, uint32 botGuid, LlmCallSource source,
    int timeOutSeconds, std::vector<std::string>& debugLines)
{
    if (!Enabled())
        return "error";

    LlmRequest request;
    request.prompt = prompt;
    request.botGuid = botGuid;
    request.source = source;
    request.timeOutSeconds = timeOutSeconds;
    request.debug = !debugLines.empty();

    auto future = GetLlamaRuntime().Submit(std::move(request));

    // block on the async/std::async caller thread only - never call Generate
    // from the world or MapUpdater threads
    std::future_status status = future.wait_for(std::chrono::seconds(timeOutSeconds + 30));
    if (status != std::future_status::ready)
    {
        if (!debugLines.empty())
            debugLines.push_back("llama: generation did not complete in time");
        return "error";
    }

    return future.get();
}

void PlayerbotLlamaRuntime::SetCompanionMode(bool companion)
{
    GetLlamaRuntime().SetCompanionMode(companion);
}

void PlayerbotLlamaRuntime::InvalidateSlots()
{
    if (sPlayerbotAIConfig.llmBackend == PlayerbotAIConfig::LLM_BACKEND_LLAMA)
        GetLlamaRuntime().InvalidateSlots();
}

void PlayerbotLlamaRuntime::PreWarmSlot(uint32 botGuid, const std::string& prompt)
{
    if (!Enabled())
        return;

    LlmRequest request;
    request.prompt = prompt;
    request.botGuid = botGuid;
    request.source = LLM_SRC_CHAT_REPLY;
    request.timeOutSeconds = sPlayerbotAIConfig.llmGenerationTimeout;
    GetLlamaRuntime().Submit(std::move(request));
}

#else // !POCKETREALM_LLAMA

bool PlayerbotLlamaRuntime::Enabled()
{
    return false;
}

std::string PlayerbotLlamaRuntime::Generate(const std::string&, uint32, LlmCallSource, int, std::vector<std::string>&)
{
    return "error";
}

void PlayerbotLlamaRuntime::SetCompanionMode(bool) {}
void PlayerbotLlamaRuntime::InvalidateSlots() {}
void PlayerbotLlamaRuntime::PreWarmSlot(uint32, const std::string&) {}

#endif // POCKETREALM_LLAMA
