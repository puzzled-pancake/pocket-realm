package com.pocketrealm.llm

/**
 * JNI bridge to fork/exec the LLM server with CPU affinity and nice applied
 * in the child before exec. Implemented in llmexec.c.
 */
internal object LlmExec {
    init {
        System.loadLibrary("llmexec")
    }

    /** Returns child pid, or negative errno on failure. */
    external fun nativeExec(argv: Array<String>, envp: Array<String>, nice: Int, cpuMaskHex: Long): Int

    /** 0 = still running; 10000+exit for normal exit; negative = signal; -errno = waitpid error. */
    external fun nativeWaitPid(pid: Int): Int
}
