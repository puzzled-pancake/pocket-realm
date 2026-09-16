package com.pocketrealm.llm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Warm-up/restart source contract. runSupervisor is a private
 * service method driving a real child process - not unit-drivable - so the
 * load-bearing behaviors are pinned against the source, the
 * AndroidPortAssetTest pattern. The probe detects the thinking-template
 * failure shape (reasoning_content, empty content, finish=length) and the
 * override restarts the child with the inline --chat-template CONTENT form,
 * the only form the vendored 6d05498 binary accepts.
 */
class LlmRuntimeServiceWarmUpTest {

    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/com/pocketrealm/llm/LlmRuntimeService.kt"),
            File("pocketrealm-llm/src/main/java/com/pocketrealm/llm/LlmRuntimeService.kt"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("LlmRuntimeService.kt not found from test working dir")
    }

    @Test fun `probe body is one tiny generation with no kwargs`() {
        // the probe must see the template's OWN default: kwargs could mask
        // the very failure being detected (measured: disable_thinking
        // kwargs do NOT fix the base gemma)
        assertTrue("\"max_tokens\":8" in source)
        assertTrue("chat_template_kwargs" !in source)
        assertTrue("enable_thinking" !in source)
    }

    @Test fun `detection is reasoning or empty content`() {
        assertTrue("reasoning.isNotEmpty() || content.isEmpty()" in source)
    }

    @Test fun `override uses the inline template content not the file flag`() {
        // the vendored 6d05498 binary predates --chat-template-file and
        // accepts only the inline --chat-template form, so the override
        // must never use the file-flag spelling against the shipped binary.
        assertTrue("listOf(\"--chat-template\", stagedTemplate)" in source)
        // the flag name may survive only inside explanatory comments - any
        // executable use must die
        val offending = source.lineSequence()
            .filter { "--chat-template-file" in it }
            .filterNot { it.trimStart().startsWith("//") }
            .toList()
        assertTrue("executable --chat-template-file use: $offending", offending.isEmpty())
    }

    @Test fun `exactly one retry and a revert when the override child stays unhealthy`() {
        assertTrue("templateRetryDone = true" in source)
        assertTrue("armedTemplate = true" in source)
        // the crash-loop guard: a never-healthy override child must disarm
        // back to the model's own template for the next exec
        assertTrue("if (armedTemplate && !healthy.get())" in source)
        assertTrue("effectiveConfig = config" in source)
        assertTrue("armedTemplate = false" in source)
    }

    @Test fun `a deliberate post-healthy kill is never an npu load death`() {
        // attribution must remain pre-healthy-only
        assertTrue("if (!healthy.get() && npuActive)" in source)
    }

    @Test fun `arming requires a readable staged template`() {
        assertTrue("File(path).isFile" in source)
        assertTrue("runCatching { File(path).readText() }.getOrNull()" in source)
    }
}
