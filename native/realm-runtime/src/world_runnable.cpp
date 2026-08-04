#include "Common.h"
#include "World/World.h"
#include "WorldRunnable.h"
#include "Util/Timer.h"
#include "Database/DatabaseEnv.h"
#include "Log/Log.h"

#define WORLD_SLEEP_CONST 50

extern "C" void pocket_world_record_tick(uint32_t duration_ms);

void WorldRunnable::run()
{
    WorldDatabase.ThreadStart();
    sWorld.InitResultQueue();
    uint32 diffTick = WorldTimer::tick();
    bool firstTick = true;
    sLog.outString("POCKET_WORLD_LOOP starting stopped=%u", World::IsStopped() ? 1u : 0u);
    while (!World::IsStopped())
    {
        ++World::m_worldLoopCounter;
        diffTick = WorldTimer::tick();
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
    sLog.outString("POCKET_WORLD_LOOP exiting stopped=%u", World::IsStopped() ? 1u : 0u);
    sWorld.CleanupsBeforeStop();
    WorldDatabase.ThreadEnd();
}
