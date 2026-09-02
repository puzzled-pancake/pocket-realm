// JNI exec wrapper: forks, applies CPU affinity + nice in the child, then
// execve's the LLM server binary. Keeps the runtime confined to chosen cores
// (coexistence rule: never compete with the game client / wineserver).
#define _GNU_SOURCE 1 // bionic: cpu_set_t / sched_setaffinity need this
#include <jni.h>
#include <sched.h>
#include <signal.h>
#include <sys/prctl.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <sys/wait.h>

// Returns the child pid, or -errno on failure.
JNIEXPORT jint JNICALL
Java_com_pocketrealm_llm_LlmExec_nativeExec(
        JNIEnv *env, jclass clazz,
        jobjectArray jargv, jobjectArray jenvp,
        jint nice, jlong cpuMaskHex) {
    (void) clazz;

    int argc = (*env)->GetArrayLength(env, jargv);
    char *argv[argc + 1];
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jargv, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;

    int envc = (*env)->GetArrayLength(env, jenvp);
    char *envp[envc + 1];
    for (int i = 0; i < envc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jenvp, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        envp[i] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    envp[envc] = NULL;

    pid_t pid = fork();
    if (pid == 0) {
        // Child: apply constraints before exec so no instruction runs wild.
        // PDEATHSIG first: if the :llm service process dies, the exec'd
        // server must receive SIGTERM (its graceful path releases the DSP
        // session and the loopback port) instead of surviving as an orphan
        // that no later generation can stop or supersede. The setting
        // survives execve (the binary is not setuid); the getppid check
        // closes the fork->prctl race where the parent died in between.
        prctl(PR_SET_PDEATHSIG, SIGTERM);
        if (getppid() == 1) {
            _exit(124); // service process already gone
        }
        if (nice != 0) {
            if (setpriority(PRIO_PROCESS, 0, nice) != 0) {
                _exit(125);
            }
        }
        if (cpuMaskHex != 0) {
            cpu_set_t set;
            CPU_ZERO(&set);
            for (int c = 0; c < 64; c++) {
                if (cpuMaskHex & (1ULL << c)) CPU_SET(c, &set);
            }
            if (sched_setaffinity(0, sizeof(set), &set) != 0) {
                _exit(126);
            }
        }
        execve(argv[0], argv, envp);
        _exit(127); // exec failed
    }

    for (int i = 0; i < argc; i++) free(argv[i]);
    for (int i = 0; i < envc; i++) free(envp[i]);

    if (pid < 0) return -errno;
    return pid;
}

JNIEXPORT jint JNICALL
Java_com_pocketrealm_llm_LlmExec_nativeWaitPid(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
    if (r == 0) return 0;          // still running
    if (r < 0) return -errno;
    if (WIFEXITED(status)) return 10000 + WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}
