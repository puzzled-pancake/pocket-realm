#include "vortek_safe_lane.h"

#define SPIRV_MAGIC 0x07230203U
#define SPIRV_HEADER_WORDS 5U

bool VortekSafeLane_validateSpirvEnvelope(const uint32_t* code, size_t codeSize) {
    if (!code || codeSize < SPIRV_HEADER_WORDS * sizeof(uint32_t) || codeSize > VORTEK_SAFE_MAX_SPIRV_SIZE) return false;
    if ((codeSize % sizeof(uint32_t)) != 0 || ((uintptr_t)code % _Alignof(uint32_t)) != 0) return false;
    if (code[0] != SPIRV_MAGIC) return false;

    const uint32_t version = code[1];
    const uint32_t major = (version >> 16) & 0xffU;
    const uint32_t minor = (version >> 8) & 0xffU;
    if ((version & 0xff0000ffU) != 0 || major != 1 || minor > 6) return false;
    if (code[3] == 0 || code[4] != 0) return false;
    return true;
}
