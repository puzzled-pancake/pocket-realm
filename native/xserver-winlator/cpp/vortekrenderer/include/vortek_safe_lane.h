#ifndef POCKETREALM_VORTEK_SAFE_LANE_H
#define POCKETREALM_VORTEK_SAFE_LANE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define VORTEK_SAFE_MAX_SPIRV_SIZE (16U * 1024U * 1024U)

bool VortekSafeLane_validateSpirvEnvelope(const uint32_t* code, size_t codeSize);
bool VortekSafeLane_validateSpirvForInspector(
        const uint32_t* code, size_t codeSize);

#endif
