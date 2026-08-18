#pragma once

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>

namespace pocket_server {

/**
 * Linearizes bot-target requests with admission-monitor retirement.
 *
 * Generation zero is reserved for the unmanaged/public control surface. A
 * non-zero admission generation must be registered before it can queue work.
 * The world tick consumes a request while holding the same short-lived gate as
 * retire(), so retirement cannot return with an old request still applicable.
 */
class BotTargetFence {
public:
    enum class QueueState { REJECTED, ALREADY_EFFECTIVE, QUEUED };

    struct QueueResult {
        QueueState state{QueueState::REJECTED};
        uint64_t sequence{0};
    };

    bool begin(int64_t generation)
    {
        if (generation == 0) return false;
        std::lock_guard<std::mutex> guard(m_mutex);
        if (m_active_generation != 0) return false;
        clear_pending_locked();
        m_active_generation = generation;
        m_changed.notify_all();
        return true;
    }

    bool retire(int64_t generation)
    {
        if (generation == 0) return false;
        std::lock_guard<std::mutex> guard(m_mutex);
        if (m_active_generation == 0) return true;
        if (m_active_generation != generation) return false;
        if (m_has_pending && m_pending_generation == generation)
            clear_pending_locked();
        m_active_generation = 0;
        m_changed.notify_all();
        return true;
    }

    QueueResult queue(int target, int64_t generation, int effective_target)
    {
        std::lock_guard<std::mutex> guard(m_mutex);
        if (!valid_generation_locked(generation)) return {};
        if (effective_target == target)
        {
            // A retry after acknowledgement must not leave a redundant request.
            clear_pending_locked();
            m_changed.notify_all();
            return {QueueState::ALREADY_EFFECTIVE, 0};
        }
        const uint64_t sequence = m_next_sequence++;
        m_has_pending = true;
        m_pending_target = target;
        m_pending_generation = generation;
        m_pending_sequence = sequence;
        m_changed.notify_all();
        return {QueueState::QUEUED, sequence};
    }

    bool wait_applied(
        uint64_t sequence,
        int64_t generation,
        std::chrono::milliseconds timeout)
    {
        if (sequence == 0) return true;
        std::unique_lock<std::mutex> guard(m_mutex);
        m_changed.wait_for(guard, timeout, [&] {
            return m_last_applied_sequence == sequence ||
                !valid_generation_locked(generation) ||
                (!m_has_pending && m_last_applied_sequence != sequence) ||
                (m_has_pending && m_pending_sequence != sequence);
        });
        return m_last_applied_sequence == sequence;
    }

    template <typename Apply>
    bool consume(Apply&& apply)
    {
        std::lock_guard<std::mutex> guard(m_mutex);
        if (!m_has_pending) return false;
        if (!valid_generation_locked(m_pending_generation))
        {
            clear_pending_locked();
            m_changed.notify_all();
            return false;
        }
        const int target = m_pending_target;
        const uint64_t sequence = m_pending_sequence;
        apply(target);
        m_last_applied_sequence = sequence;
        clear_pending_locked();
        m_changed.notify_all();
        return true;
    }

    void reset()
    {
        std::lock_guard<std::mutex> guard(m_mutex);
        m_active_generation = 0;
        m_last_applied_sequence = 0;
        clear_pending_locked();
        m_changed.notify_all();
    }

    bool has_pending_for_test()
    {
        std::lock_guard<std::mutex> guard(m_mutex);
        return m_has_pending;
    }

private:
    bool valid_generation_locked(int64_t generation) const
    {
        return generation == 0 ? m_active_generation == 0 :
            m_active_generation == generation;
    }

    void clear_pending_locked()
    {
        m_has_pending = false;
        m_pending_target = -1;
        m_pending_generation = 0;
        m_pending_sequence = 0;
    }

    std::mutex m_mutex;
    std::condition_variable m_changed;
    int64_t m_active_generation{0};
    bool m_has_pending{false};
    int m_pending_target{-1};
    int64_t m_pending_generation{0};
    uint64_t m_pending_sequence{0};
    uint64_t m_last_applied_sequence{0};
    uint64_t m_next_sequence{1};
};

} // namespace pocket_server
