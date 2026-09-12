/*
 * win_exception_shim.cpp — Windows/MSVC lane only.
 *
 * Two jobs:
 *
 * 1. Boost's exception-disabled declaration path (selected when a TU is
 *    compiled without unwind semantics, e.g. through a precompiled header
 *    created before /EHsc applies) leaves out-of-line declarations of
 *    boost::throw_exception referenced by asio detail objects. On the
 *    Android/NDG lane every TU compiles with exceptions enabled and the
 *    inline definitions win; on MSVC the linker can still see the
 *    out-of-line references from such objects. This TU is compiled with
 *    BOOST_NO_EXCEPTIONS so the header only DECLARES the overloads, and
 *    the definitions below satisfy them.
 *
 * 2. Fail-fast tracer: a 0xC0000409 (abort/__fastfail) in the embedded
 *    world kills the host JVM silently - no hs_err (that is JVM-fatal
 *    only), no C++ exception, nothing on stderr that survives the gradle
 *    pipe. The handlers below append a diagnosis line (kind, thread,
 *    exception code, stack frames as module!offset) to
 *    %LOCALAPPDATA%\PocketRealm\crash-trace.txt so a repro run names the
 *    failing module. Diagnosis tooling, not recovery: every handler
 *    terminates the process exactly as it would have.
 */

#define BOOST_NO_EXCEPTIONS 1

#include <boost/throw_exception.hpp>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <csignal>
#include <exception>
#include <windows.h>
#include <dbghelp.h>

namespace boost {

void throw_exception(std::exception const& e)
{
    std::fprintf(stderr, "boost::throw_exception reached (no-exceptions path): %s\n",
                 e.what());
    std::fflush(stderr);
    std::abort();
}

#if defined(BOOST_NO_EXCEPTIONS)
void throw_exception(std::exception const& e, boost::source_location const&)
{
    std::fprintf(stderr, "boost::throw_exception reached (no-exceptions path): %s\n",
                 e.what());
    std::fflush(stderr);
    std::abort();
}
#endif

} // namespace boost

namespace {

// dbghelp resolved lazily: crash triage must not add a hard link
// dependency. The initializer is an ATOMIC TRY-CLAIM, never a magic
// static: two threads crashing at once must not serialize on the
// static-init guard inside their crash handlers (a hang here is worse
// than the silent death this file exists to diagnose), and a fault
// inside SymInitialize must not recursively re-enter the tracer.
enum PocketSymState { POCKET_SYM_UNTRIED = 0, POCKET_SYM_INITIALIZING = 1,
                      POCKET_SYM_READY = 2, POCKET_SYM_FAILED = 3 };
volatile long g_pocketSymState = POCKET_SYM_UNTRIED;
HMODULE g_pocketSymModule = nullptr;
HANDLE g_pocketSymProcess = nullptr;
BOOL (WINAPI* g_pocketSymFromAddr)(HANDLE, DWORD64, PDWORD64, PSYMBOL_INFO) = nullptr;

bool pocket_sym_ready()
{
    long expected = POCKET_SYM_UNTRIED;
    if (InterlockedCompareExchange(&g_pocketSymState, POCKET_SYM_INITIALIZING,
                                   expected) == POCKET_SYM_UNTRIED)
    {
        // this thread owns the one and only initialization attempt
        bool ok = false;
        g_pocketSymModule = LoadLibraryA("dbghelp.dll");
        if (g_pocketSymModule)
        {
            auto initialize = (BOOL (WINAPI*)(HANDLE, LPCSTR, BOOL))
                GetProcAddress(g_pocketSymModule, "SymInitialize");
            auto setOptions = (BOOL (WINAPI*)(DWORD))
                GetProcAddress(g_pocketSymModule, "SymSetOptions");
            g_pocketSymFromAddr = (BOOL (WINAPI*)(HANDLE, DWORD64, PDWORD64, PSYMBOL_INFO))
                GetProcAddress(g_pocketSymModule, "SymFromAddr");
            if (initialize && g_pocketSymFromAddr)
            {
                if (setOptions) setOptions(SYMOPT_UNDNAME | SYMOPT_DEFERRED_LOADS);
                g_pocketSymProcess = GetCurrentProcess();
                ok = initialize(g_pocketSymProcess, nullptr, TRUE) &&
                     g_pocketSymFromAddr != nullptr;
            }
        }
        InterlockedExchange(&g_pocketSymState,
                            ok ? POCKET_SYM_READY : POCKET_SYM_FAILED);
        return ok;
    }
    // another thread owns (or owned) initialization: never wait - use its
    // result only if it already settled
    return g_pocketSymState == POCKET_SYM_READY;
}

// separate name storage: the SYMBOL_INFO name area and the printf
// destination must never overlap (long undecorated names made them do so)
void pocket_resolve_frame(void* address, char* nameOut, size_t nameCap,
                          char* suffixOut, size_t suffixCap)
{
    suffixOut[0] = '\0';
    if (!pocket_sym_ready() || !g_pocketSymFromAddr)
        return;
    SYMBOL_INFO* symbol = (SYMBOL_INFO*)nameOut;
    symbol->SizeOfStruct = sizeof(SYMBOL_INFO);
    symbol->MaxNameLen = (ULONG)nameCap - sizeof(SYMBOL_INFO) - 1;
    DWORD64 displacement = 0;
    if (g_pocketSymFromAddr(g_pocketSymProcess, (DWORD64)address,
                            &displacement, symbol))
    {
        _snprintf(suffixOut, suffixCap, " %s+0x%llx", symbol->Name,
                  (unsigned long long)displacement);
        suffixOut[suffixCap - 1] = '\0';
    }
}

HANDLE pocket_crash_file()
{
    char path[MAX_PATH];
    const char* base = std::getenv("LOCALAPPDATA");
    if (!base || std::strlen(base) + 64 >= MAX_PATH)
        return INVALID_HANDLE_VALUE;
    std::snprintf(path, sizeof(path), "%s\\PocketRealm\\crash-trace.txt", base);
    return CreateFileA(path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE,
                       nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
}

void pocket_write_trace(HANDLE file, char const* line)
{
    if (file != INVALID_HANDLE_VALUE)
    {
        DWORD written = 0;
        WriteFile(file, line, (DWORD)std::strlen(line), &written, nullptr);
    }
    std::fputs(line, stderr);
}

// re-entry guard: a fault inside the tracer itself must never recurse
// (the deepest recursion path - SymInitialize walking module memory -
// lands right back in the SEH filter)
__declspec(thread) int t_pocketInTrace = 0;

void pocket_crash_trace(char const* kind, unsigned long code)
{
    if (t_pocketInTrace)
        return;
    ++t_pocketInTrace;

    HANDLE file = pocket_crash_file();
    bool const have_file = file != INVALID_HANDLE_VALUE;

    char line[512];
    unsigned long const tid = GetCurrentThreadId();
    std::snprintf(line, sizeof(line),
                  "POCKET-CRASH kind=%s code=0x%08lX tid=%lu\r\n",
                  kind, code, tid);
    pocket_write_trace(file, line);

    void* frames[48];
    unsigned short const count = CaptureStackBackTrace(1, 48, frames, nullptr);
    char symbolArea[sizeof(SYMBOL_INFO) + 512];
    char suffix[512];
    for (unsigned short i = 0; i < count; ++i)
    {
        HMODULE module = nullptr;
        char const* module_name = "?";
        uintptr_t module_base = 0;
        if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                   GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                               (LPCSTR)frames[i], &module) && module)
        {
            char name_storage[MAX_PATH]; // per-frame local: concurrent
            // traces must not race shared storage
            if (GetModuleFileNameA(module, name_storage, MAX_PATH))
            {
                // keep just the tail - the frames share one or two modules
                char const* slash = std::strrchr(name_storage, '\\');
                module_name = slash ? slash + 1 : name_storage;
            }
            module_base = reinterpret_cast<uintptr_t>(module);
        }
        pocket_resolve_frame(frames[i], symbolArea, sizeof(symbolArea),
                             suffix, sizeof(suffix));
        std::snprintf(line, sizeof(line), "  #%02u %s!%p%s\r\n", i, module_name,
                      (void*)(reinterpret_cast<uintptr_t>(frames[i]) - module_base),
                      suffix);
        pocket_write_trace(file, line);
    }
    std::fflush(stderr);
    if (have_file)
        CloseHandle(file);
    --t_pocketInTrace;
}

LONG WINAPI pocket_seh_handler(EXCEPTION_POINTERS* pointers)
{
    EXCEPTION_RECORD const* record = pointers->ExceptionRecord;
    char kind[32];
    std::snprintf(kind, sizeof(kind), "unhandled-0x%08lX", record->ExceptionCode);
    pocket_crash_trace(kind, record->ExceptionCode);
    return EXCEPTION_CONTINUE_SEARCH;
}

void pocket_signal_handler(int signal_code)
{
    pocket_crash_trace(signal_code == SIGABRT ? "SIGABRT" : "signal", (unsigned long)signal_code);
    // restore the default and re-raise so the process still dies loudly
    std::signal(signal_code, SIG_DFL);
    std::raise(signal_code);
}

// The uncaught-exception terminate path is how a worker-thread throw
// (map updater, async LLM worker) kills the whole process: name the
// exception before dying - e.what() is the actual diagnosis.
void pocket_terminate_handler()
{
    if (std::exception_ptr current = std::current_exception())
    {
        try
        {
            std::rethrow_exception(current);
        }
        catch (std::exception const& e)
        {
            pocket_crash_trace("std-terminate-exception", 0);
            char line[512];
            std::snprintf(line, sizeof(line), "  what: %s\r\n", e.what());
            HANDLE file = pocket_crash_file();
            pocket_write_trace(file, line);
            if (file != INVALID_HANDLE_VALUE)
                CloseHandle(file);
        }
        catch (...)
        {
            pocket_crash_trace("std-terminate-unknown", 0);
        }
    }
    else
    {
        pocket_crash_trace("std-terminate", 0);
    }
    std::abort();
}

void pocket_invalid_parameter(wchar_t const*, wchar_t const*, wchar_t const*,
                              unsigned int, uintptr_t)
{
    pocket_crash_trace("invalid-parameter", 0);
    std::abort();
}

struct PocketCrashTracerInstall
{
    PocketCrashTracerInstall()
    {
        std::signal(SIGABRT, pocket_signal_handler);
        std::signal(SIGSEGV, pocket_signal_handler);
        std::signal(SIGILL, pocket_signal_handler);
        std::signal(SIGFPE, pocket_signal_handler);
        SetUnhandledExceptionFilter(pocket_seh_handler);
        std::set_terminate(pocket_terminate_handler);
        _set_invalid_parameter_handler(pocket_invalid_parameter);
    }
};

PocketCrashTracerInstall g_pocket_crash_tracer_install;

} // namespace
