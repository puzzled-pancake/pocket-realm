/*
 * native/wine-spike/trampoline/wine_trampoline.c
 *
 * S-5(a) Bionic trampoline PIE.
 *
 * This is a STANDALONE Android/Bionic executable (not a shared library), built
 * with the NDK as a PIE program. It is packaged into the APK as
 * libwine_trampoline.so (the lib*.so naming is required by AGP to extract into
 * nativeLibraryDir), but it is a PIE program — its PT_INTERP is the Bionic
 * linker (/system/bin/linker64) and its main() runs under Android's exec.
 *
 * Its sole job: re-execve the APK-managed glibc loader with the exact argv it
 * received. It is deliberately tiny and auditable — no logic, no libs beyond
 * Bionic. It never links glibc and never runs in the glibc namespace.
 *
 * Invocation (set up by trampoline_launcher.c):
 *   libwine_trampoline.so <loader> --library-path <lib> <wine> [wine_args...]
 *
 * The trampoline passes argv[1..] straight to execve as the new argv:
 *   execve(argv[1], &argv[1], environ)
 * i.e. it becomes the glibc loader with the rest of the command line intact.
 *
 * Why a separate process: see trampoline_launcher.c. This tests whether the
 * SIGSYS observed on the direct path is specific to exec'ing the glibc ELF
 * from the app's own forked child vs. from a clean execve'd Bionic process.
 *
 * NOTE on the .so packaging: AGP only extracts lib<name>.so into
 * nativeLibraryDir with the +x bit. A file named "wine_trampoline" (no lib/.so)
 * would be dropped or placed in the APK without +x. We name it libwine_trampoline.so
 * and build it as PIE; the loader invocation works because execve does not care
 * about the suffix, only the file's ELF type.
 */
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <android/log.h>

#define TAG "wine_spike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

int main(int argc, char **argv, char **envp) {
    /* argv[0] = this trampoline. argv[1] = the glibc loader to exec. */
    if (argc < 5) {
        LOGE("trampoline: too few args (got %d, need >=5: self loader --library-path LIB wine ...)", argc);
        fprintf(stderr, "wine_trampoline: usage: %s <loader> --library-path <lib> <wine> [args...]\n",
                argv[0]);
        return 127;
    }

    LOGI("trampoline: re-execve %s (argc=%d)", argv[1], argc - 1);

    /* Re-execve argv[1] with argv[1..] as the new argv and the inherited env.
     * The trampoline IS the exec target from the app process; the glibc loader
     * is the exec target from THIS process. */
    execve(argv[1], &argv[1], envp);

    /* If execve returns, it failed. */
    LOGE("trampoline: execve(%s) failed: %s", argv[1], strerror(errno));
    fprintf(stderr, "wine_trampoline: execve failed: %s\n", strerror(errno));
    return 127;
}
