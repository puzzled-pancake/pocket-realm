/*
 * native/wine-spike/src/err_str.c
 *
 * Human-readable strings for wine_spike result codes.
 */
#include "wine_spike.h"

const char *wine_spike_err_str(int code) {
    switch (code) {
        case WINE_SPIKE_OK:          return "OK";
        case WINE_SPIKE_ERR_ARGS:    return "bad argument";
        case WINE_SPIKE_ERR_IO:      return "I/O error";
        case WINE_SPIKE_ERR_LAUNCH:  return "launch (fork/execve) error";
        case WINE_SPIKE_ERR_TIMEOUT: return "timeout";
        case WINE_SPIKE_ERR_VERIFY:  return "verification failed (loader/PE mismatch)";
        default:                     return "unknown error";
    }
}
