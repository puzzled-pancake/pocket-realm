#include "vortek_safe_lane.h"

#include <stdio.h>
#include <string.h>

static int expect(bool condition, const char* label) {
    if (condition) return 0;
    fprintf(stderr, "FAIL: %s\n", label);
    return 1;
}

int main(void) {
    int failures = 0;
    uint32_t valid[] = {0x07230203U, 0x00010600U, 0U, 1U, 0U};

    failures += expect(VortekSafeLane_validateSpirvEnvelope(valid, sizeof(valid)), "valid 1.6 header");
    failures += expect(!VortekSafeLane_validateSpirvEnvelope(NULL, sizeof(valid)), "null code");
    failures += expect(!VortekSafeLane_validateSpirvEnvelope(valid, sizeof(valid) - 1U), "unaligned byte size");
    failures += expect(!VortekSafeLane_validateSpirvEnvelope(valid, 4U * sizeof(uint32_t)), "short header");
    failures += expect(!VortekSafeLane_validateSpirvEnvelope(valid, VORTEK_SAFE_MAX_SPIRV_SIZE + sizeof(uint32_t)), "oversized module");

    uint32_t alignedStorage[6] = {0};
    unsigned char* unaligned = (unsigned char*)alignedStorage + 1U;
    memcpy(unaligned, valid, sizeof(valid));
    failures += expect(!VortekSafeLane_validateSpirvEnvelope((const uint32_t*)unaligned, sizeof(valid)), "unaligned pointer");

    uint32_t changed[5];
    memcpy(changed, valid, sizeof(changed));
    changed[0] = 0;
    failures += expect(!VortekSafeLane_validateSpirvEnvelope(changed, sizeof(changed)), "bad magic");
    memcpy(changed, valid, sizeof(changed));
    changed[1] = 0x00020000U;
    failures += expect(!VortekSafeLane_validateSpirvEnvelope(changed, sizeof(changed)), "unsupported major");
    memcpy(changed, valid, sizeof(changed));
    changed[1] = 0x00010700U;
    failures += expect(!VortekSafeLane_validateSpirvEnvelope(changed, sizeof(changed)), "unsupported minor");
    memcpy(changed, valid, sizeof(changed));
    changed[3] = 0;
    failures += expect(!VortekSafeLane_validateSpirvEnvelope(changed, sizeof(changed)), "zero id bound");
    memcpy(changed, valid, sizeof(changed));
    changed[4] = 1;
    failures += expect(!VortekSafeLane_validateSpirvEnvelope(changed, sizeof(changed)), "nonzero schema");

    if (failures == 0) puts("vortek_safe_lane_test: PASS");
    return failures == 0 ? 0 : 1;
}
