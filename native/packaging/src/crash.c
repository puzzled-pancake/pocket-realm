/* crash.c — the deterministic PKG-02 native fault. Runs in the isolated :pkg
 * child process, never in :main. kind 0 (abort) is the report's named trigger. */
#include "pocket_pkg.h"
#include <stdlib.h>
#include <string.h>

void pkg_crash(int32_t kind)
{
    switch (kind) {
    case 1: { /* NULL dereference — a representative memory fault */
        volatile int* p = (volatile int*)0;
        *p = 42;
        break;
    }
    case 2: { /* stack guard / infinite recursion -> guard page */
        volatile char buf[4096];
        memset((void*)buf, 0x5a, sizeof buf);
        pkg_crash(kind); /* recurse until the guard page faults */
        break;
    }
    case 0:
    default:
        abort(); /* SIGABRT — the deterministic, report-named trigger */
    }
}
