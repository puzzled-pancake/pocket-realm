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
    bool prefillOnly = false;
    std::promise<std::string> result;
};

struct LlmSlot
{
    int32_t seqId = -1;
    uint32 formatVersion = 0;
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

        if (m_failed || m_model || m_running)
            return !m_failed; // m_running covers the companion-reload window

        if (!LoadModel())
            return false;

        m_running = true;
        m_worker = std::thread(&LlamaRuntimeImpl::WorkerLoop, this);
        m_watchdog = std::thread(&LlamaRuntimeImpl::WatchdogLoop, this);
        m_available.store(true, std::memory_order_relaxed);

        return true;
    }

    // lock-free snapshot for the map-thread prewarm path: PreWarmSlot must
    // never call Start() (a cold start or a companion reload under m_mutex
    // would block the simulation thread for the whole model load)
    bool ReadyForPrewarm() const
    {
        return m_available.load(std::memory_order_relaxed);
    }

    // caller holds m_mutex (or is the worker between generations)
    bool LoadModel()
    {
        if (sPlayerbotAIConfig.llmModelPath.empty())
        {
            sLog.outError("BotLLM: llama backend selected but AiPlayerbot.LLMModelPath is empty");
            m_failed = true;
            return false;
        }

        // once per process: the companion reload re-enters LoadModel and
        // repeated llama_backend_init calls re-scan the backend registry
        static bool s_backendInitDone = false;
        if (!s_backendInitDone)
        {
            llama_backend_init();
            s_backendInitDone = true;
        }

        llama_model_params mparams = llama_model_default_params();
        // coexistence profile: mmap lazy paging keeps real resident memory at
        // the weights actually touched; companion mode (dedicated sit-and-talk
        // screen, world paused) takes full residency for the faster prefill
        mparams.load_mode = m_companion ? LLAMA_LOAD_MODE_AUTO : LLAMA_LOAD_MODE_MMAP;

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

        {
            std::lock_guard<std::mutex> lock(m_slotMutex);
            m_freeSeqs.clear();
            for (uint32 i = 0; i < m_slotCount; ++i)
                m_freeSeqs.push_back(static_cast<llama_seq_id>(i));
        }

        sLog.outString("BotLLM: llama runtime started (model %s, ctx %u/slot, slots %u, threads %u, profile %s)",
            sPlayerbotAIConfig.llmModelPath.c_str(), sPlayerbotAIConfig.llmCtxSize, m_slotCount,
            sPlayerbotAIConfig.llmThreads, m_companion ? "companion" : "coexistence");

        return true;
    }

    void Shutdown()
    {
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            if (!m_running)
                return;
            m_running = false;
            m_available.store(false, std::memory_order_relaxed);
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

    // map-thread variant of Submit: the only long-held m_mutex acquisition
    // is a companion ReloadInPlace (whole model free+load), so contention
    // here means a reload or teardown is in flight - drop the prewarm
    // instead of stalling the simulation tick; the next 30s cadence warms
    // the slot. The abandoned promise is never waited on.
    void TrySubmit(LlmRequest&& request)
    {
        {
            std::unique_lock<std::mutex> lock(m_mutex, std::try_to_lock);
            if (!lock.owns_lock() || !m_running || m_failed)
                return;
            m_queue.push_back(std::move(request));
        }
        m_cond.notify_one();
    }

    void SetCompanionMode(bool companion)
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_companion == companion || m_failed)
            return;

        // profile switch requires a reload: companion mode trades the
        // coexistence mmap profile for full residency and the faster prefill
        m_companion = companion;
        // only a RUNNING runtime needs the flag: before Start (or after
        // Shutdown) the next LoadModel already picks up m_companion, and a
        // leftover flag would trigger a pointless full reload right after
        // the first load
        m_reloadRequested = m_running;
    }

    bool ShouldReload()
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (!m_reloadRequested || !m_running)
            return false;
        m_reloadRequested = false;
        return true;
    }

    // swap model/context/threadpool in place; runs on the worker thread
    // between generations (Shutdown/Start would try to join this thread)
    bool ReloadInPlace()
    {
        // serialized against Submit/Start reads of m_failed/m_model/m_ctx; the
        // worker is between generations here and RunGeneration never holds
        // m_mutex, so this cannot deadlock against itself
        std::lock_guard<std::mutex> lock(m_mutex);
        // the availability gate must cover the reload window as well: left
        // set, a map-thread prewarm passes ReadyForPrewarm() and then stalls
        // in Submit() on this lock for the whole multi-second model load
        m_available.store(false, std::memory_order_relaxed);
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
        {
            std::lock_guard<std::mutex> lock(m_slotMutex);
            m_botSlots.clear();
            m_freeSeqs.clear();
        }
        if (!LoadModel())
            return false;
        m_available.store(true, std::memory_order_relaxed);
        return true;
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

    static int RequestPriority(LlmRequest const& request)
    {
        if (request.prefillOnly)
            return 0; // background pre-warm never delays a live conversation
        if (request.source == PlayerbotLlamaRuntime::LLM_SRC_DEBUG)
            return 3;
        if (request.source == PlayerbotLlamaRuntime::LLM_SRC_CHAT_REPLY)
            return 2;
        return 1;
    }

    void FailQueuedRequests()
    {
        // fulfill every queued promise on worker exit so callers do not stall
        // on the full caller-side timeout waiting for a dead worker
        std::lock_guard<std::mutex> lock(m_mutex);
        while (!m_queue.empty())
        {
            LlmRequest request = std::move(m_queue.front());
            m_queue.pop_front();
            request.result.set_value("error");
        }
    }

    void WorkerLoop()
    {
        setpriority(PRIO_PROCESS, 0, 10);

        while (true)
        {
            // companion-mode profile switches reload the model between
            // generations, never mid-generation
            if (ShouldReload() && !ReloadInPlace())
            {
                {
                    std::lock_guard<std::mutex> lock(m_mutex);
                    m_failed = true;
                    m_available.store(false, std::memory_order_relaxed);
                }
                FailQueuedRequests();
                return;
            }

            LlmRequest request;
            {
                std::unique_lock<std::mutex> lock(m_mutex);
                m_cond.wait(lock, [this] { return !m_running || !m_queue.empty(); });
                if (!m_running)
                {
                    lock.unlock();
                    FailQueuedRequests();
                    return;
                }
                // player-facing generations jump the queue over background
                // pre-warm so a whisper never waits on slot warming
                auto best = m_queue.begin();
                for (auto itr = m_queue.begin(); itr != m_queue.end(); ++itr)
                    if (RequestPriority(*itr) > RequestPriority(*best))
                        best = itr;
                request = std::move(*best);
                m_queue.erase(best);
            }

            try
            {
                std::string result = RunGeneration(request);
                request.result.set_value(result);
            }
            catch (std::exception const& e)
            {
                // a throw escaping the worker thread function would
                // std::terminate the process; surface it as the same
                // transport-error sentinel the consumer side expects
                sLog.outError("BotLLM: llama worker generation failed: %s", e.what());
                request.result.set_value(std::string("error"));
            }
            catch (...)
            {
                sLog.outError("BotLLM: llama worker generation failed (unknown exception)");
                request.result.set_value(std::string("error"));
            }
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

    // keepPrefix is in/out: a format-version slot drop forces the effective
    // keepPrefix to 0, which the caller must use as its decode offset (the
    // common prefix it computed no longer matches the cleared sequence)
    llama_seq_id AcquireSeq(uint32 botGuid, size_t& keepPrefix)
    {
        std::lock_guard<std::mutex> lock(m_slotMutex);

        auto itr = m_botSlots.find(botGuid);
        if (itr != m_botSlots.end() && itr->second.formatVersion != POCKETREALM_LLAMA_PROMPT_FORMAT_VERSION)
        {
            // warmed against a different prompt format: drop the slot truly
            // whole (the reused seq id keeps its old KV until the seq_rm
            // below clears it from position 0)
            m_freeSeqs.push_back(itr->second.seqId);
            m_botSlots.erase(itr);
            itr = m_botSlots.end();
            keepPrefix = 0;
        }
        if (itr == m_botSlots.end())
        {
            if (m_freeSeqs.empty() && !EvictOldestSlot())
                return -1;

            llama_seq_id seq = m_freeSeqs.front();
            m_freeSeqs.pop_front();
            itr = m_botSlots.emplace(botGuid, LlmSlot()).first;
            itr->second.seqId = seq;
            itr->second.formatVersion = POCKETREALM_LLAMA_PROMPT_FORMAT_VERSION;
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

        size_t common = CommonPrefixLength(request.botGuid, tokens);

        // an exact-prefix resubmission must still re-decode its final token:
        // AcquireSeq keeps KV positions [0, common) and the slot's token
        // record already contains it, so the prefill loop would skip to
        // sampling off stale logits - and decoding that position a second
        // time would leave a duplicate (pos, seq) cell that every later
        // generation attends to and that no later acquire can reclaim
        // (they only trim positions >= their own prefix)
        if (common > 0 && common == tokens.size())
            --common;

        // evict the slot when this prompt plus the generation budget would
        // overflow the per-slot context: decode would fail permanently
        uint32 const perSlotCtx = sPlayerbotAIConfig.llmCtxSize;
        if (tokens.size() + sPlayerbotAIConfig.llmMaxNewTokens + 8 >= perSlotCtx)
            common = 0;
        llama_seq_id seq = AcquireSeq(request.botGuid, common);
        if (seq < 0)
            return {};

        auto const genStart = std::chrono::steady_clock::now();
        bool aborted = false;
        m_activePrefillOnly = request.prefillOnly;
        // clear any abort the watchdog raised against a request that already
        // finished, so a stale flag cannot poison this generation's first decode
        m_abort.store(false, std::memory_order_relaxed);
        // best-effort pre-warm gets a short dedicated deadline: it must never
        // keep the worker busy for a full generation window
        m_activeDeadline.store(DeadlineIn(request.prefillOnly ? 10 : request.timeOutSeconds), std::memory_order_relaxed);
        std::string result = DecodeAndSample(vocab, seq, tokens, common, aborted);
        m_activeDeadline.store(0, std::memory_order_relaxed);

        if (aborted && !request.prefillOnly)
        {
            bool retryAllowed = false;
            {
                std::lock_guard<std::mutex> lock(m_mutex);
                retryAllowed = m_running && !m_failed;
            }
            // one retry after dropping the sequence back to empty, within the
            // time the caller still has left - never a fresh full window
            if (retryAllowed)
            {
                m_abort.store(false, std::memory_order_relaxed);
                sLog.outError("BotLLM: decode aborted, retrying once");

                common = 0;
                seq = AcquireSeq(request.botGuid, common);
                if (seq >= 0)
                {
                    int32_t const elapsed = static_cast<int32_t>(std::chrono::duration_cast<std::chrono::seconds>(
                        std::chrono::steady_clock::now() - genStart).count());
                    int32_t remaining = request.timeOutSeconds - elapsed;
                    if (remaining < 1)
                        remaining = 1;
                    m_activeDeadline.store(DeadlineIn(remaining), std::memory_order_relaxed);
                    aborted = false;
                    result = DecodeAndSample(vocab, seq, tokens, common, aborted);
                    m_activeDeadline.store(0, std::memory_order_relaxed);
                }
            }
            if (aborted || !retryAllowed)
                return "error";
        }

        return result;
    }

    std::string DecodeAndSample(llama_vocab const* vocab, llama_seq_id seq,
        std::vector<llama_token> const& tokens, size_t offset, bool& aborted)
    {
        llama_seq_id seqId = seq;

        // the caller guarantees offset < tokens.size(): an exact-prefix
        // resubmission clamps its offset so the final token is re-decoded by
        // this loop (with fresh logits) after AcquireSeq trimmed it from KV

        // llama_batch_get_one returns null seq/pos arrays (seq fixed to 0);
        // sequences other than 0 require a heap batch we fill ourselves
        int32_t const batchSize = 256;
        for (size_t i = offset; i < tokens.size(); i += batchSize)
        {
            int32_t const n = static_cast<int32_t>(std::min<size_t>(batchSize, tokens.size() - i));
            llama_batch batch = llama_batch_init(n, 0, 1);
            batch.n_tokens = n;
            for (int32_t j = 0; j < n; ++j)
            {
                batch.token[j] = tokens[i + j];
                batch.n_seq_id[j] = 1;
                batch.seq_id[j][0] = seqId; // row is preallocated; never replace the pointer
                batch.pos[j] = static_cast<llama_pos>(i + j);
                batch.logits[j] = (i + j + 1 == tokens.size()) ? 1 : 0;
            }
            bool failed = llama_decode(m_ctx, batch) != 0;
            llama_batch_free(batch);
            if (failed)
            {
                aborted = m_abort.load(std::memory_order_relaxed);
                return "error";
            }

            for (size_t j = i; j < i + (size_t)n; ++j)
                AppendSlotTokens(seq, tokens[j]);
        }

        if (m_activePrefillOnly)
            return "";

        llama_sampler* smpl = BuildSampler(vocab);
        if (!smpl)
            return "error";

        // sample from the final prompt token's logits, then feed only the
        // sampled tokens back - the last prompt token is never decoded twice
        std::string output;
        for (uint32 generated = 0; generated < sPlayerbotAIConfig.llmMaxNewTokens; ++generated)
        {
            llama_token last = llama_sampler_sample(smpl, m_ctx, -1);

            if (llama_vocab_is_eog(vocab, last))
                break;

            char piece[64];
            int32_t n = llama_token_to_piece(vocab, last, piece, sizeof(piece), 0, true);
            if (n > 0)
                output.append(piece, (size_t)n);

            // the token cap ends the reply with this piece: the loop exits
            // right after the decode below, so those logits are never
            // sampled. Skip the wasted pass (and the slot bookkeeping,
            // matching the EOG break) - a failure there would also discard
            // the finished output and hand the caller "error".
            if (generated + 1 == sPlayerbotAIConfig.llmMaxNewTokens)
                break;

            llama_batch batch = llama_batch_init(1, 0, 1);
            batch.n_tokens = 1;
            batch.token[0] = last;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = seqId;
            batch.pos[0] = static_cast<llama_pos>(tokens.size() + generated);
            batch.logits[0] = 1;
            bool failed = llama_decode(m_ctx, batch) != 0;
            llama_batch_free(batch);
            if (failed)
            {
                aborted = m_abort.load(std::memory_order_relaxed);
                llama_sampler_free(smpl);
                return aborted ? "" : "error";
            }

            // bookkeeping only after a successful decode: a phantom token
            // here would desync slot.tokens from the KV sequence
            AppendSlotTokens(seq, last);
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
        // the chain must end with a sampler that actually selects a token -
        // penalties/top-k/top-p/temp only reshape the candidate list, and
        // llama_sampler_sample asserts when no selector set `selected`
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        return smpl;
    }

    std::mutex m_mutex;
    std::condition_variable m_cond;
    std::deque<LlmRequest> m_queue;
    bool m_running = false;
    bool m_failed = false;
    bool m_companion = false;
    bool m_reloadRequested = false;

    std::mutex m_slotMutex;
    std::map<uint32, LlmSlot> m_botSlots;
    std::deque<llama_seq_id> m_freeSeqs;
    uint32 m_slotCount = 4;

    std::atomic<long long> m_activeDeadline{0};
    std::atomic<bool> m_activePrefillOnly{false};
    std::atomic<bool> m_abort{false};
    std::atomic<bool> m_available{false};

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

    // the worker's exception would rethrow here on the generation thread;
    // that thread is always detached-adjacent (SendDelayedPacket's waiter),
    // so it must surface as the transport-error sentinel, never escape
    try
    {
        return future.get();
    }
    catch (std::exception const& e)
    {
        if (!debugLines.empty())
            debugLines.push_back(std::string("llama: generation failed: ") + e.what());
        sLog.outError("BotLLM: llama generation failed: %s", e.what());
    }
    catch (...)
    {
        if (!debugLines.empty())
            debugLines.push_back("llama: generation failed (unknown exception)");
        sLog.outError("BotLLM: llama generation failed (unknown exception)");
    }
    return "error";
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
    if (sPlayerbotAIConfig.llmBackend != PlayerbotAIConfig::LLM_BACKEND_LLAMA)
        return;

    // invoked from PlayerbotAI::UpdateAI (a map/simulation thread): never
    // cold-start or wait on a companion reload here - a full model load
    // under m_mutex would stall the map tick for seconds. Only an already
    // running runtime accepts prewarm; the first real Generate (always on
    // an async thread) pays the cold start.
    if (!GetLlamaRuntime().ReadyForPrewarm())
        return;

    LlmRequest request;
    request.prompt = prompt;
    request.botGuid = botGuid;
    request.source = LLM_SRC_CHAT_REPLY;
    request.timeOutSeconds = sPlayerbotAIConfig.llmGenerationTimeout;
    request.prefillOnly = true; // warm the KV cache without sampling tokens
    GetLlamaRuntime().TrySubmit(std::move(request));
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
