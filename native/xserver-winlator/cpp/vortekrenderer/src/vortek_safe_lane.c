#include "vortek_safe_lane.h"
#include "spirv.h"

#define SPIRV_MAGIC 0x07230203U
#define SPIRV_HEADER_WORDS 5U
#define SPIRV_WORD_COUNT_SHIFT 16U
#define SPIRV_OPCODE_MASK 0xffffU
#define SPIRV_MAX_ID_BOUND 0x003fffffU

static size_t minimumInspectedInstructionWords(uint32_t opcode) {
    switch ((SpvOp)opcode) {
        case SpvOpName: return 3U;
        case SpvOpEntryPoint: return 4U;
        case SpvOpCapability: return 2U;
        case SpvOpDecorate: return 3U;
        case SpvOpVariable: return 4U;
        case SpvOpTypePointer: return 4U;
        case SpvOpTypeVector: return 4U;
        case SpvOpTypeFloat: return 3U;
        case SpvOpTypeInt: return 4U;
        case SpvOpConstant: return 4U;
        case SpvOpConstantComposite: return 4U;
        case SpvOpStore: return 3U;
        case SpvOpLoad: return 4U;
        case SpvOpFunction: return 5U;
        case SpvOpSelect: return 6U;
        default:
            return opcode >= (uint32_t)SpvOpTypeVoid &&
                    opcode <= (uint32_t)SpvOpTypeForwardPointer ? 2U : 1U;
    }
}

bool VortekSafeLane_validateSpirvEnvelope(const uint32_t* code, size_t codeSize) {
    if (!code || codeSize < SPIRV_HEADER_WORDS * sizeof(uint32_t) || codeSize > VORTEK_SAFE_MAX_SPIRV_SIZE) return false;
    if ((codeSize % sizeof(uint32_t)) != 0 || ((uintptr_t)code % _Alignof(uint32_t)) != 0) return false;
    if (code[0] != SPIRV_MAGIC) return false;

    const uint32_t version = code[1];
    const uint32_t major = (version >> 16) & 0xffU;
    const uint32_t minor = (version >> 8) & 0xffU;
    if ((version & 0xff0000ffU) != 0 || major != 1 || minor > 6) return false;
    if (code[3] == 0 || code[3] > SPIRV_MAX_ID_BOUND || code[4] != 0) return false;

    /* The Winlator compatibility inspector walks instructions directly.  Do
     * not let a malformed guest module turn a zero/truncated word count into
     * an infinite loop or an out-of-bounds operand read. */
    const size_t wordCount = codeSize / sizeof(uint32_t);
    for (size_t cursor = SPIRV_HEADER_WORDS; cursor < wordCount;) {
        const size_t instructionWords = code[cursor] >> SPIRV_WORD_COUNT_SHIFT;
        if (instructionWords == 0 || instructionWords > wordCount - cursor) {
            return false;
        }
        const uint32_t opcode = code[cursor] & SPIRV_OPCODE_MASK;
        size_t minimumWords = minimumInspectedInstructionWords(opcode);
        if (opcode == (uint32_t)SpvOpDecorate && instructionWords >= 3U &&
                (code[cursor + 2U] == (uint32_t)SpvDecorationLocation ||
                 code[cursor + 2U] == (uint32_t)SpvDecorationBuiltIn)) {
            minimumWords = 4U;
        }
        if (instructionWords < minimumWords) {
            return false;
        }
        cursor += instructionWords;
    }
    return true;
}

bool VortekSafeLane_validateSpirvForInspector(
        const uint32_t* code, size_t codeSize) {
    if (!VortekSafeLane_validateSpirvEnvelope(code, codeSize)) return false;
    const size_t wordCount = codeSize / sizeof(uint32_t);
    bool hasEntryPoint = false;
    bool hasFunction = false;
    for (size_t cursor = SPIRV_HEADER_WORDS; cursor < wordCount;) {
        const uint32_t opcode = code[cursor] & SPIRV_OPCODE_MASK;
        if (opcode == (uint32_t)SpvOpEntryPoint) hasEntryPoint = true;
        if (opcode == (uint32_t)SpvOpFunction) hasFunction = true;
        cursor += code[cursor] >> SPIRV_WORD_COUNT_SHIFT;
    }
    return hasEntryPoint && hasFunction;
}
