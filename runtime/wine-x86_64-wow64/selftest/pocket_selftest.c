/*
 * pocket_selftest.c — O06 G1 Wine self-test PE.
 *
 * A minimal, legally redistributable 32-bit Win32 program that exercises the
 * Wine display/input/audio lifecycle paths the G1 spike must prove, with NO
 * dependency on proprietary WoW content:
 *
 *   - registers a window class + creates a 1280x720 top-level window
 *     (exercises winex11.drv -> libX11; proves the X11/display path)
 *   - handles WM_KEYDOWN/WM_KEYUP/WM_MOUSEMOVE/WM_LBUTTONDOWN/WM_LBUTTONUP
 *     (proves the input bridge end-to-end via the X server's InputDeviceManager)
 *   - opens + immediately closes the winmm waveOut device, then prints whether
 *     audio init succeeded, so the spike can prove the audio-OFF path by
 *     observing that init is NOT attempted when audio is disabled
 *   - writes structured, parseable lines to stdout so the host driver can
 *     verify each lifecycle event:
 *       POCKET_SELFTEST_START pid=<windows-pid>
 *       POCKET_SELFTEST_WINDOW 1280x720
 *       POCKET_SELFTEST_KEY <vk>
 *       POCKET_SELFTEST_MOUSE x=<x> y=<y> btn=<l|r>
 *       POCKET_SELFTEST_AUDIO <init|skipped|err=<code>>
 *       POCKET_SELFTEST_FOCUS <gained|lost>
 *       POCKET_SELFTEST_OK
 *   - exits cleanly on WM_CLOSE/WM_DESTROY (proves the clean-close path)
 *
 * Built by tools/build_selftest_pe.py via mingw-w64-i686-gcc (32-bit PE, the
 * same bitness WoW.exe is, so it exercises the WoW64 thunk path). Project-owned
 * code; LGPL-clean (no proprietary data or headers).
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mmsystem.h>
#include <stdio.h>

static const char *CLASS_NAME = "PocketSelftestWnd";
static const char *WINDOW_TITLE = "Pocket Realm G1 Self-test";
static int g_vk_last = 0;
static int g_audio_attempted = 0;
static int g_audio_ok = 0;
static int g_painted = 0;
static int g_interactive = 0;
static char g_close_file[MAX_PATH * 4];

static void log_line(const char *fmt, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0) {
        /* WriteFile to GetStdHandle(STD_OUTPUT_HANDLE) keeps ordering correct
         * under Wine; plain fputs(stdout) can interleave with Wine debug. */
        HANDLE h = GetStdHandle(STD_OUTPUT_HANDLE);
        if (h && h != INVALID_HANDLE_VALUE) {
            DWORD written = 0;
            WriteFile(h, buf, (DWORD)n, &written, NULL);
            WriteFile(h, "\r\n", 2, &written, NULL);
        }
    }
}

static void probe_audio(void) {
    char mode[16] = {0};
    if (GetEnvironmentVariableA("POCKET_AUDIO_MODE", mode, sizeof(mode)) > 0 &&
        lstrcmpiA(mode, "off") == 0) {
        log_line("POCKET_SELFTEST_AUDIO skipped");
        return;
    }
    /* Open + immediately close the default waveOut. Under WineD3D-safe audio-OFF
     * mode the harness disables Wine's audio backends, so this should fail or
     * be a no-op; the log line records which, proving the audio-disabled path. */
    g_audio_attempted = 1;
    HWAVEOUT h = NULL;
    WAVEFORMATEX wfx = {0};
    wfx.wFormatTag = WAVE_FORMAT_PCM;
    wfx.nChannels = 1;
    wfx.nSamplesPerSec = 22050;
    wfx.wBitsPerSample = 16;
    wfx.nBlockAlign = 2;
    wfx.nAvgBytesPerSec = 22050 * 2;
    MMRESULT r = waveOutOpen(&h, WAVE_MAPPER, &wfx, 0, 0, CALLBACK_NULL);
    if (r == MMSYSERR_NOERROR) {
        g_audio_ok = 1;
        waveOutClose(h);
        log_line("POCKET_SELFTEST_AUDIO init");
    } else {
        log_line("POCKET_SELFTEST_AUDIO err=%d", (int)r);
    }
}

static LRESULT CALLBACK wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_CREATE:
            log_line("POCKET_SELFTEST_WINDOW 1280x720");
            /* Defer the audio probe until the window is up so the log order is
             * deterministic: START, WINDOW, AUDIO, then events. */
            probe_audio();
            return 0;
        case WM_SETFOCUS:
            log_line("POCKET_SELFTEST_FOCUS gained");
            return 0;
        case WM_KILLFOCUS:
            log_line("POCKET_SELFTEST_FOCUS lost");
            return 0;
        case WM_KEYDOWN:
            g_vk_last = (int)wp;
            log_line("POCKET_SELFTEST_KEY %d", (int)wp);
            return 0;
        case WM_KEYUP:
            log_line("POCKET_SELFTEST_KEYUP %d", (int)wp);
            return 0;
        case WM_MOUSEMOVE:
            /* Cheap: only log when a button is held, to avoid log flooding. */
            if (wp & (MK_LBUTTON | MK_RBUTTON)) {
                log_line("POCKET_SELFTEST_MOUSE x=%d y=%d btn=%s",
                         (int)(short)LOWORD(lp), (int)(short)HIWORD(lp),
                         (wp & MK_LBUTTON) ? "l" : "r");
            }
            return 0;
        case WM_LBUTTONDOWN:
            log_line("POCKET_SELFTEST_MOUSE x=%d y=%d btn=l",
                     (int)(short)LOWORD(lp), (int)(short)HIWORD(lp));
            return 0;
        case WM_LBUTTONUP:
            log_line("POCKET_SELFTEST_MOUSEUP x=%d y=%d btn=l",
                     (int)(short)LOWORD(lp), (int)(short)HIWORD(lp));
            return 0;
        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC dc = BeginPaint(hwnd, &ps);
            HBRUSH background = CreateSolidBrush(RGB(20, 30, 48));
            FillRect(dc, &ps.rcPaint, background);
            DeleteObject(background);
            SetBkMode(dc, TRANSPARENT);
            SetTextColor(dc, RGB(225, 235, 255));
            RECT title = {72, 72, 1200, 300};
            DrawTextA(dc,
                      "Pocket Realm\r\nO06 Wine lifecycle self-test\r\n"
                      "Win32 on x86_64 Android",
                      -1, &title, DT_LEFT | DT_TOP | DT_NOPREFIX);
            EndPaint(hwnd, &ps);
            if (!g_painted) {
                g_painted = 1;
                log_line("POCKET_SELFTEST_PAINT");
                /* Close only after the mapped window has painted. This keeps
                 * the headless instrumentation deterministic while exercising
                 * WM_CLOSE, WM_DESTROY, and a clean process exit. */
                SetTimer(hwnd, 1, g_interactive ? 100 : 250, NULL);
            }
            return 0;
        }
        case WM_TIMER:
            if (wp == 1) {
                if (!g_interactive ||
                    (g_close_file[0] && GetFileAttributesA(g_close_file) != INVALID_FILE_ATTRIBUTES)) {
                    KillTimer(hwnd, 1);
                    log_line("POCKET_SELFTEST_CLOSE requested");
                    SendMessageA(hwnd, WM_CLOSE, 0, 0);
                }
            }
            return 0;
        case WM_CLOSE:
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
        default:
            return DefWindowProcA(hwnd, msg, wp, lp);
    }
}

int WINAPI WinMain(HINSTANCE hInst, HINSTANCE hPrev, LPSTR cmd, int show) {
    (void)hPrev; (void)cmd; (void)show;

    {
        char interactive[8] = {0};
        g_interactive = GetEnvironmentVariableA(
            "POCKET_SELFTEST_INTERACTIVE", interactive, sizeof(interactive)) > 0 &&
            interactive[0] == '1';
        GetEnvironmentVariableA("POCKET_CLOSE_FILE", g_close_file, sizeof(g_close_file));
    }
    log_line("POCKET_SELFTEST_START pid=%lu interactive=%d",
             (unsigned long)GetCurrentProcessId(), g_interactive);

    WNDCLASSEXA wc = {0};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = wndproc;
    wc.hInstance = hInst;
    wc.lpszClassName = CLASS_NAME;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    if (!RegisterClassExA(&wc)) {
        log_line("POCKET_SELFTEST_FAIL RegisterClassEx err=%lu", (unsigned long)GetLastError());
        return 2;
    }

    HWND hwnd = CreateWindowExA(0, CLASS_NAME, WINDOW_TITLE,
                                WS_OVERLAPPEDWINDOW,
                                CW_USEDEFAULT, CW_USEDEFAULT, 1280, 720,
                                NULL, NULL, hInst, NULL);
    if (!hwnd) {
        log_line("POCKET_SELFTEST_FAIL CreateWindow err=%lu", (unsigned long)GetLastError());
        return 3;
    }
    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);

    MSG msg;
    while (GetMessageA(&msg, NULL, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageA(&msg);
    }

    log_line("POCKET_SELFTEST_OK audio_attempted=%d audio_ok=%d keys_seen=%d",
             g_audio_attempted, g_audio_ok, g_vk_last ? 1 : 0);
    return 0;
}
