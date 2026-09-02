#include "Common.h"
#include "World/World.h"
#include "WorldRunnable.h"
#include "Util/Timer.h"
#include "Database/DatabaseEnv.h"
#include "Log/Log.h"

#include <atomic>

#define WORLD_SLEEP_CONST 50

extern "C" void pocket_world_record_tick(uint32_t duration_ms);

// Companion-mode pause: the world loop keeps ticking its timer (so the resume
// diff stays one normal tick, never one giant spike) but stops updating the
// world while the sit-and-talk conversation owns the device.
static std::atomic<bool> s_worldPaused{false};

extern "C" void pocket_world_set_paused(int paused)
{
    s_worldPaused.store(paused != 0, std::memory_order_relaxed);
}

extern "C" int pocket_world_is_paused()
{
    return s_worldPaused.load(std::memory_order_relaxed) ? 1 : 0;
}

void WorldRunnable::run()
{
    WorldDatabase.ThreadStart();
    sWorld.InitResultQueue();
    uint32 diffTick = WorldTimer::tick();
    bool firstTick = true;
    // companion mode runs the world at a reduced duty cycle (1 Hz) instead of
    // freezing it: session packet processing, bot AI ticks and async DB
    // callbacks all live inside World::Update, so a hard skip would also kill
    // the sit-and-talk conversation the pause exists to serve. 1 Hz keeps the
    // machinery alive while freeing ~95% of the tick budget for the LLM.
    uint32 pausedAccumulator = 0;
    sLog.outString("POCKET_WORLD_LOOP starting stopped=%u", World::IsStopped() ? 1u : 0u);
    while (!World::IsStopped())
    {
        ++World::m_worldLoopCounter;
        diffTick = WorldTimer::tick();
        if (!s_worldPaused.load(std::memory_order_relaxed))
        {
            pausedAccumulator = 0;
            sWorld.Update(diffTick);
            const uint32 duration = WorldTimer::getMSTime() - WorldTimer::tickTime();
            pocket_world_record_tick(duration);
            if (firstTick)
            {
                sLog.outString("POCKET_WORLD_LOOP first tick duration=%u", duration);
                firstTick = false;
            }
            if (duration < WORLD_SLEEP_CONST)
                MaNGOS::Thread::Sleep(WORLD_SLEEP_CONST - duration);
        }
        else
        {
            pausedAccumulator += diffTick;
            if (pausedAccumulator >= 1000)
            {
                // clamp so a long pause gap never produces one giant diff
                sWorld.Update(pausedAccumulator > 1000 ? 1000 : pausedAccumulator);
                pausedAccumulator = 0;
            }
            if (firstTick)
            {
                sLog.outString("POCKET_WORLD_LOOP first tick duration=%u", 0u);
                firstTick = false;
            }
            MaNGOS::Thread::Sleep(WORLD_SLEEP_CONST);
        }
    }
    sLog.outString("POCKET_WORLD_LOOP exiting stopped=%u", World::IsStopped() ? 1u : 0u);
    sWorld.CleanupsBeforeStop();
    WorldDatabase.ThreadEnd();
}
