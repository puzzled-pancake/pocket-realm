#include "bot_target_fence.h"

#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdlib>
#include <future>
#include <thread>

// Assertions in this standalone contract test must evaluate in Release too.
#ifdef NDEBUG
#undef assert
#define assert(condition) ((condition) ? static_cast<void>(0) : std::abort())
#endif

using pocket_server::BotTargetFence;
using namespace std::chrono_literals;

int main()
{
    {
        BotTargetFence fence;
        assert(fence.begin(1));
        const auto queued = fence.queue(600, 1, 700);
        assert(queued.state == BotTargetFence::QueueState::QUEUED);
        assert(!fence.wait_applied(queued.sequence, 1, 0ms));
        assert(fence.retire(1));
        bool applied = false;
        assert(!fence.consume([&](int) { applied = true; }));
        assert(!applied);
    }

    {
        BotTargetFence fence;
        assert(fence.begin(2));
        fence.queue(550, 2, 600);
        std::promise<void> entered;
        std::promise<void> release;
        auto released = release.get_future().share();
        std::atomic<bool> applied{false};
        std::thread consumer([&] {
            fence.consume([&](int target) {
                assert(target == 550);
                entered.set_value();
                released.wait();
                applied.store(true);
            });
        });
        entered.get_future().wait();
        auto retired = std::async(std::launch::async, [&] { return fence.retire(2); });
        assert(retired.wait_for(50ms) == std::future_status::timeout);
        release.set_value();
        assert(retired.get());
        consumer.join();
        assert(applied.load());
        assert(!fence.has_pending_for_test());
    }

    {
        BotTargetFence fence;
        assert(fence.begin(3));
        const auto first = fence.queue(500, 3, 600);
        assert(!fence.wait_applied(first.sequence, 3, 0ms));
        const auto retry = fence.queue(500, 3, 600);
        int effective = 600;
        assert(fence.consume([&](int target) { effective = target; }));
        assert(fence.wait_applied(retry.sequence, 3, 0ms));
        const auto acknowledged = fence.queue(500, 3, effective);
        assert(acknowledged.state == BotTargetFence::QueueState::ALREADY_EFFECTIVE);
        assert(!fence.has_pending_for_test());
    }

    {
        BotTargetFence fence;
        assert(fence.begin(4));
        assert(fence.retire(4));
        assert(fence.begin(5));
        assert(!fence.retire(4));
        assert(fence.queue(450, 4, 500).state == BotTargetFence::QueueState::REJECTED);
        assert(fence.queue(450, 5, 500).state == BotTargetFence::QueueState::QUEUED);
        fence.reset();
        assert(fence.begin(1));
        assert(!fence.has_pending_for_test());
    }

    {
        BotTargetFence fence;
        assert(fence.queue(25, 0, 50).state == BotTargetFence::QueueState::QUEUED);
        fence.reset();
        assert(fence.begin(6));
        assert(fence.queue(25, 0, 50).state == BotTargetFence::QueueState::REJECTED);
        assert(fence.retire(6));
        assert(fence.queue(25, 0, 50).state == BotTargetFence::QueueState::QUEUED);
    }

    return 0;
}
