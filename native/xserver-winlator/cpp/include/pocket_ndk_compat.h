/*
 * native/xserver-winlator/cpp/include/pocket_ndk_compat.h
 *
 * Force-include for the vendored Winlator C sources. The pinned sources
 * (ca3d735) rely on transitive header inclusion that the upstream Android
 * Studio build provides but the standalone NDK build does not (e.g.
 * string_utils.h uses strtod/strcpy without including <stdlib.h>/<string.h>).
 *
 * Rather than edit the vendored sources (which would break the source-match
 * provenance record), this header is force-included via -include so the
 * canonical source compiles UNMODIFIED against the NDK. This is the only
 * build-time adaptation; the .c/.h content is byte-identical to the pinned
 * commit.
 */
#ifndef POCKET_NDK_COMPAT_H
#define POCKET_NDK_COMPAT_H

#include <stdlib.h>     /* strtod, strtol, atoi */
#include <string.h>     /* strcpy, strncpy, strlen, strcmp, strstr, memcpy */
#include <stdio.h>      /* snprintf, fopen, fread */
#include <time.h>       /* clock_gettime, nanosleep (time_utils.h uses these) */
#include <sys/time.h>   /* struct timeval */

#endif /* POCKET_NDK_COMPAT_H */
