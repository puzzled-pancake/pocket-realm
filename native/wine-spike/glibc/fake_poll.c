#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <time.h>
#include <sysdep-cancel.h>

#define MSEC_PER_SEC 1000L
#define NSEC_PER_MSEC 1000000L

/*
 * Android's app seccomp policy blocks the legacy x86_64 poll(2) syscall but
 * permits ppoll(2).  Removing __NR_poll from glibc's active arch-syscall.h
 * makes glibc's internal poll implementation select its ppoll fallback.  This
 * helper preserves the same behaviour for callers that use syscall(__NR_poll)
 * explicitly and are routed through Termux glibc's fakesyscall dispatcher.
 */
static __attribute__((unused)) int
fake_poll (struct pollfd *fds, nfds_t nfds, int timeout)
{
  struct timespec timeout_ts;
  struct timespec *timeout_ptr = NULL;

  if (timeout >= 0)
    {
      timeout_ts.tv_sec = timeout / MSEC_PER_SEC;
      timeout_ts.tv_nsec = (timeout % MSEC_PER_SEC) * NSEC_PER_MSEC;
      timeout_ptr = &timeout_ts;
    }

  return SYSCALL_CANCEL (ppoll, fds, nfds, timeout_ptr, NULL, __NSIG_BYTES);
}
