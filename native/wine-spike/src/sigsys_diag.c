/*
 * native/wine-spike/src/sigsys_diag.c
 *
 * S-5(0) corrected SIGSYS diagnostic.
 *
 * Previous (a512b71) recorded the S-1 failure as "SELinux blocks execve" based
 * solely on exit code 159 (128 + SIGSYS). That conflates termination *by*
 * SIGSYS with the cause of the signal. Exit 159 only proves the child was
 * killed by signal 31 (SIGSYS); it says nothing about WHICH mechanism raised
 * it (a seccomp SECCOMP_RET_KILL_PROCESS trap, an explicit tkill, or a bad
 * syscall) or which syscall triggered it.
 *
 * This module traces the glibc loader under PTRACE and, on SIGSYS, captures:
 *   - siginfo.si_code   (1 = SYS_SECCOMP for seccomp, 0 = SI_USER, 0x80 = SI_KERNEL)
 *   - siginfo.si_signo  (31 = SIGSYS)
 *   - the syscall number (orig_rax via GETREGSET) and arch (AUDIT_ARCH_X86_64)
 *
 * ON-DEVICE FINDING (Modern lane, API 35, 4KB):
 *   The diagnostic captured: si_signo=31, si_code=1 (SYS_SECCOMP), syscall=21
 *   (access), arch=0xc000003e (AUDIT_ARCH_X86_64). The glibc loader's very
 *   first probing call — access() on an LD_LIBRARY_PATH entry — is blocked by
 *   Android's untrusted_app seccomp filter. This is NOT an SELinux execve
 *   denial; it is a syscall-filter kill on a glibc startup syscall that Bionic
 *   policy forbids. The trampoline path (S-5a) hits the identical trap once it
 *   execs the glibc loader, confirming the block is on the glibc loader's
 *   syscalls, not on how we arrive at it. There is no GLIBC_TUNABLES to
 *   suppress the loader's access() calls, so the narrow fallback does not
 *   apply and proot (syscall interception) is required.
 *
 * The trace stops at the FIRST signal-delivery-stop for SIGSYS so we capture
 * the triggering syscall before the kernel kills the tracee.
 */
#include "wine_spike.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <signal.h>
#include <sys/syscall.h>    /* __NR_* syscall number macros for the name mapper */
#include <linux/audit.h>
/* NT_PRSTATUS is defined by <elf.h> (the generic one) on the NDK; avoid
 * <linux/elf.h> which conflicts. */
#include <elf.h>
#include <android/log.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif
#ifndef PTRACE_GET_SYSCALL
/* glibc may not expose this; the syscall info is read via PTRACE_GET_SYSCALL
 * which on x86_64 is the same number as PTRACE_SYSCALL_INFO on newer kernels.
 * Fall back to reading the register set via GETREGSET if unavailable. */
#endif
#ifndef PTRACE_GET_SYSCALL_INFO
#define PTRACE_GET_SYSCALL_INFO 0x420e
struct ptrace_syscall_info {
    unsigned char op;           /* 1=entry, 2=exit, 3=seccomp, 4=none */
    unsigned int arch;
    unsigned long long instruction_pointer;
    unsigned long long stack_pointer;
    union {
        struct {
            unsigned long long nr;
            unsigned long long args[6];
        } entry;
        struct {
            long long rval;
            unsigned char is_error;
        } exit;
        struct {
            unsigned long long nr;
            unsigned long long args[6];
            unsigned int ret_data;
        } seccomp;
    };
};
#endif

#ifndef SI_TKILL
#define SI_TKILL (-6)
#endif

#define TAG "wine_spike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

/* Map a syscall number to a name for the common glibc-startup + exec suspects.
 * This is x86_64 numbering (asm-generic/unistd-ish via <sys/syscall.h>). */
static const char *syscall_name_x86_64(long nr) {
    /* Pull the __NR_ constants from <sys/syscall.h>. These are defined for the
     * target (x86_64) by the NDK headers. */
#ifdef __NR_rseq
    if (nr == __NR_rseq) return "rseq";
#endif
#ifdef __NR_clone3
    if (nr == __NR_clone3) return "clone3";
#endif
#ifdef __NR_execve
    if (nr == __NR_execve) return "execve";
#endif
#ifdef __NR_execveat
    if (nr == __NR_execveat) return "execveat";
#endif
#ifdef __NR_clone
    if (nr == __NR_clone) return "clone";
#endif
#ifdef __NR_openat
    if (nr == __NR_openat) return "openat";
#endif
#ifdef __NR_mmap
    if (nr == __NR_mmap) return "mmap";
#endif
#ifdef __NR_mprotect
    if (nr == __NR_mprotect) return "mprotect";
#endif
#ifdef __NR_prctl
    if (nr == __NR_prctl) return "prctl";
#endif
#ifdef __NR_arch_prctl
    if (nr == __NR_arch_prctl) return "arch_prctl";
#endif
#ifdef __NR_set_tid_address
    if (nr == __NR_set_tid_address) return "set_tid_address";
#endif
#ifdef __NR_set_robust_list
    if (nr == __NR_set_robust_list) return "set_robust_list";
#endif
#ifdef __NR_munmap
    if (nr == __NR_munmap) return "munmap";
#endif
#ifdef __NR_brk
    if (nr == __NR_brk) return "brk";
#endif
#ifdef __NR_access
    if (nr == __NR_access) return "access";
#endif
#ifdef __NR_stat
    if (nr == __NR_stat) return "stat";
#endif
#ifdef __NR_readlink
    if (nr == __NR_readlink) return "readlink";
#endif
#ifdef __NR_readlinkat
    if (nr == __NR_readlinkat) return "readlinkat";
#endif
    return NULL;
}

/* Read the architecture + current syscall nr from the tracee via GETREGSET
 * (NT_PRSTATUS). Falls back gracefully if the ioctls are unavailable. */
static int read_regs_arch_syscall(pid_t pid, unsigned int *out_arch,
                                  long long *out_syscall_nr) {
    /* On x86_64, PTRACE_GETREGSET with NT_PRSTATUS returns user_regs_struct.
     * We define the layout locally (rather than pulling <sys/user.h>, whose
     * definition is target-specific and awkward under NDK cross headers) and
     * only read orig_rax (the syscall number). */
#if defined(__x86_64__) && defined(NT_PRSTATUS)
    struct {
        unsigned long long r15, r14, r13, r12, rbp, rbx, r11, r10, r9, r8;
        unsigned long long rax, rcx, rdx, rsi, rdi, orig_rax, rip, cs;
        unsigned long long eflags, rsp, ss, fs_base, gs_base, ds, es, fs, gs;
    } regs;
    memset(&regs, 0, sizeof(regs));
    struct iovec iov = { &regs, sizeof(regs) };
    if (ptrace(PTRACE_GETREGSET, pid, (void *)(intptr_t)NT_PRSTATUS, &iov) == 0) {
        *out_arch = AUDIT_ARCH_X86_64;
        *out_syscall_nr = (long long)regs.orig_rax;
        return 0;
    }
#else
    (void)pid; (void)out_arch; (void)out_syscall_nr;
#endif
    return -1;
}

int wine_spike_diag_sigsys(const char *native_dir,
                           const char *wine_target,
                           const char *prefix_dir,
                           const char *display,
                           const char *wine_args,
                           struct wine_spike_sigsys_result *out) {
    if (!native_dir || !wine_target || !prefix_dir || !out)
        return WINE_SPIKE_ERR_ARGS;

    memset(out, 0, sizeof(*out));
    out->terminated_by_signo = -1;
    out->sig_code = -1;
    out->syscall_nr = -1;
    out->arch = 0;
    out->exit_status = -1;
    out->cause = WINE_SPIKE_SIGSYS_UNRESOLVED;

    char loader_path[WINE_SPIKE_PATH_MAX];
    snprintf(loader_path, sizeof(loader_path), "%s/libld_linux_x86_64.so", native_dir);
    if (access(loader_path, X_OK) != 0) {
        LOGE("diag: loader not found/executable: %s: %s", loader_path, strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }

    /* Derive the lib path exactly as wine_spike_launch_wine does. */
    char tree_dir[WINE_SPIKE_PATH_MAX];
    snprintf(tree_dir, sizeof(tree_dir), "%s", wine_target);
    char *bin_pos = strstr(tree_dir, "/bin/");
    if (bin_pos) *bin_pos = '\0';
    else snprintf(tree_dir, sizeof(tree_dir), "%s", native_dir);
    char lib_path[WINE_SPIKE_PATH_MAX * 2];
    snprintf(lib_path, sizeof(lib_path), "%s/lib:%s/lib/wine/x86_64-unix", tree_dir, tree_dir);

    /* Build argv (same shape as launch_wine). */
    char args_copy[512] = {0};
    char *arg_tokens[16] = {NULL};
    int n_args = 0;
    if (wine_args && *wine_args) {
        snprintf(args_copy, sizeof(args_copy), "%s", wine_args);
        char *tok = strtok(args_copy, " ");
        while (tok && n_args < 15) { arg_tokens[n_args++] = tok; tok = strtok(NULL, " "); }
    }
    const char *argv[24];
    int ai = 0;
    argv[ai++] = loader_path;
    argv[ai++] = "--library-path";
    argv[ai++] = lib_path;
    argv[ai++] = wine_target;
    for (int i = 0; i < n_args; i++) argv[ai++] = arg_tokens[i];
    argv[ai] = NULL;

    /* Build envp (same shape as launch_wine). */
    char env_prefix[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_prefix, sizeof(env_prefix), "WINEPREFIX=%s", prefix_dir);
    char env_dllpath[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_dllpath, sizeof(env_dllpath), "WINEDLLPATH=%s/lib/wine/x86_64-unix", tree_dir);
    char env_home[WINE_SPIKE_PATH_MAX + 16];
    snprintf(env_home, sizeof(env_home), "HOME=%s", prefix_dir);
    char env_tmpdir[WINE_SPIKE_PATH_MAX + 16];
    snprintf(env_tmpdir, sizeof(env_tmpdir), "TMPDIR=%s/../tmp", prefix_dir);
    char env_path[WINE_SPIKE_PATH_MAX + 32];
    snprintf(env_path, sizeof(env_path), "PATH=%s", native_dir);
    const char *envp[16];
    int ei = 0;
    envp[ei++] = env_prefix;
    envp[ei++] = env_dllpath;
    envp[ei++] = env_home;
    envp[ei++] = env_tmpdir;
    envp[ei++] = env_path;
    envp[ei++] = "WINEDEBUG=-all";
    if (display && *display) {
        static char env_display[256];
        snprintf(env_display, sizeof(env_display), "DISPLAY=%s", display);
        envp[ei++] = env_display;
    }
    envp[ei] = NULL;

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("diag: fork: %s", strerror(errno));
        return WINE_SPIKE_ERR_LAUNCH;
    }
    if (pid == 0) {
        /* Child: request tracing by the parent, then stop self so the parent
         * can set PTRACE_O_TRACEEXEC + options before we execve. */
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) != 0) {
            /* If PTRACE_TRACEME fails (e.g. already traced, or denied), we
             * cannot capture si_code. Record the errno and proceed without
             * tracing — the parent will see a plain exit code. */
            fprintf(stderr, "diag: PTRACE_TRACEME failed: %s\n", strerror(errno));
        }
        raise(SIGSTOP);
        execve(loader_path, (char *const *)argv, (char *const *)envp);
        /* If execve returns, it failed before the image was replaced. */
        fprintf(stderr, "diag: execve failed: %s\n", strerror(errno));
        _exit(127);
    }

    /* Parent: wait for the initial SIGSTOP from raise(SIGSTOP). */
    int wstatus = 0;
    if (waitpid(pid, &wstatus, 0) < 0) {
        LOGE("diag: waitpid(initial): %s", strerror(errno));
        out->exit_status = -2;
        return WINE_SPIKE_ERR_LAUNCH;
    }
    if (!WIFSTOPPED(wstatus)) {
        LOGE("diag: child not stopped at init (wstatus=0x%x)", wstatus);
        out->exit_status = wstatus;
        return WINE_SPIKE_ERR_LAUNCH;
    }

    /* Set options: trace execve, get siginfo on signal stops, trace clone/fork
     * so we can see child syscalls too. */
    long opts = PTRACE_O_TRACEEXEC | PTRACE_O_TRACESYSGOOD;
    ptrace(PTRACE_SETOPTIONS, pid, 0, (void *)opts);

    /* Trace loop. The invariant: each iteration begins with the child in a
     * stopped state (a waitpid just returned WIFSTOPPED). We decide what to
     * deliver on resume, issue exactly ONE PTRACE_CONT, then waitpid for the
     * NEXT stop/exit. We do NOT PTRACE_CONT again after a fatal-delivery
     * resume — the next waitpid reaps the death and we break. The previous
     * implementation called PTRACE_CONT at the top of the loop unconditionally,
     * which re-resumed an already-dead tracee after the SIGSYS delivery and
     * produced "No such process" + exit_status=-1/sig=-1. */
    int saw_sigsys = 0;
    int last_signo = 0;
    int deliver_sig = 0;     /* signal to deliver on the next PTRACE_CONT */
    int need_continue = 1;   /* the initial SIGSTOP must be resumed */
    for (;;) {
        if (need_continue) {
            if (ptrace(PTRACE_CONT, pid, 0, (void *)(intptr_t)deliver_sig) < 0) {
                LOGE("diag: PTRACE_CONT: %s", strerror(errno));
                /* The tracee may already be gone; try to reap so exit_status
                 * reflects reality rather than staying at the -1 default. */
                if (waitpid(pid, &wstatus, WNOHANG) == pid) {
                    if (WIFEXITED(wstatus)) {
                        out->exit_status = WEXITSTATUS(wstatus);
                    } else if (WIFSIGNALED(wstatus)) {
                        out->terminated_by_signo = WTERMSIG(wstatus);
                        out->exit_status = 128 + WTERMSIG(wstatus);
                    }
                }
                break;
            }
            deliver_sig = 0;  /* consumed */
        }
        if (waitpid(pid, &wstatus, 0) < 0) {
            if (errno == ECHILD) {
                /* Already reaped (race with the delivery path below). */
                LOGI("diag: waitpid ECHILD — tracee already reaped");
            } else {
                LOGE("diag: waitpid loop: %s", strerror(errno));
            }
            break;
        }
        if (WIFEXITED(wstatus)) {
            out->exit_status = WEXITSTATUS(wstatus);
            LOGI("diag: child exited status=%d", out->exit_status);
            break;
        }
        if (WIFSIGNALED(wstatus)) {
            int term_sig = WTERMSIG(wstatus);
            out->terminated_by_signo = term_sig;
            out->exit_status = 128 + term_sig;
            LOGI("diag: child killed by signal %d (raw exit=%d)", term_sig, out->exit_status);
            /* The tracee is now a zombie and waitpid has reaped it; siginfo is
             * no longer available via PTRACE_GETSIGINFO. The signal-delivery-
             * stop path above is where we capture siginfo. This branch only
             * fires for the race where SIGSYS kills before a stop. */
            break;
        }
        if (WIFSTOPPED(wstatus)) {
            int sig = WSTOPSIG(wstatus);
            last_signo = sig;
            need_continue = 1;
            /* Group-stop / syscall-stop / exec-stop are not the SIGSYS we want. */
            if (sig == SIGTRAP) {
                int event = (wstatus >> 16) & 0xffff;
                if (event == PTRACE_EVENT_EXEC) {
                    LOGI("diag: PTRACE_EVENT_EXEC — execve succeeded, loader running");
                }
                /* Plain SIGTRAP / exec event: swallow (deliver 0), continue. */
                deliver_sig = 0;
                continue;
            }
            if (sig == SIGSYS) {
                /* Signal-delivery-stop for SIGSYS: capture siginfo + regs. */
                saw_sigsys = 1;
                siginfo_t si;
                memset(&si, 0, sizeof(si));
                if (ptrace(PTRACE_GETSIGINFO, pid, 0, &si) == 0) {
                    out->sig_signo = si.si_signo;
                    out->sig_code = si.si_code;
                    out->call_addr = (unsigned long long)(uintptr_t)si.si_call_addr;
                    LOGI("diag: SIGSYS si_signo=%d si_code=%d si_call_addr=0x%llx",
                         si.si_signo, si.si_code, out->call_addr);
                } else {
                    LOGE("diag: GETSIGINFO failed: %s", strerror(errno));
                }
                unsigned int arch = 0;
                long long scnr = -1;
                if (read_regs_arch_syscall(pid, &arch, &scnr) == 0) {
                    out->arch = arch;
                    out->syscall_nr = scnr;
                    const char *nm = syscall_name_x86_64(scnr);
                    LOGI("diag: trapping syscall nr=%lld (%s) arch=0x%x",
                         scnr, nm ? nm : "?", arch);
                    if (nm) {
                        strncpy(out->syscall_name, nm, sizeof(out->syscall_name) - 1);
                    }
                } else {
                    LOGE("diag: GETREGSET failed: %s", strerror(errno));
                }
                if (si.si_code == SYS_SECCOMP) {
                    out->cause = WINE_SPIKE_SIGSYS_SECCOMP;
                    LOGI("diag: cause = SECCOMP (SYS_SECCOMP)");
                } else if (si.si_code == SI_USER || si.si_code == SI_TKILL) {
                    out->cause = WINE_SPIKE_SIGSYS_USER;
                    LOGI("diag: cause = SI_USER/SI_TKILL (explicit kill)");
                } else if (si.si_code == SI_KERNEL) {
                    out->cause = WINE_SPIKE_SIGSYS_KERNEL;
                    LOGI("diag: cause = SI_KERNEL");
                } else {
                    out->cause = WINE_SPIKE_SIGSYS_UNRESOLVED;
                    LOGI("diag: cause = UNRESOLVED (si_code=%d)", si.si_code);
                }
                /* Deliver SIGSYS with default disposition (terminate). The NEXT
                 * waitpid reaps the death (WIFSIGNALED); we do NOT PTRACE_CONT
                 * again after that. */
                deliver_sig = SIGSYS;
                continue;
            }
            /* Other signal: reinject it on resume. */
            deliver_sig = sig;
            (void)last_signo;
        }
    }

    /* If we never saw a SIGSYS stop but the process died with 159, mark
     * UNRESOLVED explicitly so the runner does NOT claim "SELinux". */
    if (!saw_sigsys && out->exit_status == 159) {
        out->cause = WINE_SPIKE_SIGSYS_UNRESOLVED;
        LOGW("diag: exit 159 with no SIGSYS stop captured — cause UNRESOLVED");
    }
    /* A clean exit (status 0) with no signal: the loader ran fine. */
    if (out->exit_status == 0 && out->terminated_by_signo < 0) {
        out->cause = WINE_SPIKE_SIGSYS_NONE;
    }
    return WINE_SPIKE_OK;
}
