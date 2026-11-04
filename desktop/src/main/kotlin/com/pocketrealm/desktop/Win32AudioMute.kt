package com.pocketrealm.desktop

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Ole32Util
import com.sun.jna.platform.win32.W32Errors
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * the per-process game-audio mute seam — production walks the
 * Windows Core Audio session list (see [Win32AudioMute]); tests fake it.
 * Public so the desktop backend's constructor can expose it.
 */
fun interface AudioSessionMuter {
    /** null = every session of [pid] now carries [mute]; else the detail. */
    fun setSessionMute(pid: Int, mute: Boolean): String?
}

/**
 * the per-process game-audio mute twin. The Wine/ALSA mechanism is
 * android-specific; the user-visible capability — mute/unmute the game from
 * the app — is portable through the Windows Core Audio session API, reached
 * with the same JNA technique the app already uses for SendInput and the
 * window verbs.
 *
 * The launch walk: default render device → its
 * IAudioSessionManager2 → session enumerator → the session whose
 * IAudioSessionControl2 process id equals the client pid →
 * ISimpleAudioVolume.SetMute. Every outcome is honest: null on success, a
 * human detail otherwise (no session YET for the pid, COM refused, no
 * render device) — never a throw and never a faked success. The toggle
 * applies per launch (the Android prepare-time semantics), so callers poll
 * until the client's audio session appears or the deadline passes.
 */
internal object Win32AudioMute {

    val SYSTEM: AudioSessionMuter = AudioSessionMuter { pid, mute -> applyToProcess(pid, mute) }

    /**
     * Polls [muter] until the client's audio session exists and carries
     * [mute], or the deadline passes. Returns null on confirmed success,
     * else the last honest detail (the "no audio session found" refusal is
     * a RESULT, never a throw — the plan's contract for a client that never
     * opens an audio session).
     */
    fun applyLaunchMode(
        pid: Int,
        mute: Boolean,
        muter: AudioSessionMuter = SYSTEM,
        nowMs: () -> Long = System::currentTimeMillis,
        sleepMs: (Long) -> Unit = { Thread.sleep(it) },
        deadlineMs: Long = SESSION_WAIT_TIMEOUT_MS,
        pollMs: Long = SESSION_POLL_MS,
    ): String? {
        var lastDetail: String? = "audio session not probed"
        val deadline = nowMs() + deadlineMs
        while (true) {
            lastDetail = runCatching { muter.setSessionMute(pid, mute) }
                .getOrElse { failure -> "audio mute failed: ${failure.message}" }
            if (lastDetail == null) return null
            if (nowMs() >= deadline) return lastDetail
            sleepMs(pollMs)
        }
    }

    /** The Core Audio walk (Windows-only; any failure is the honest detail). */
    private fun applyToProcess(pid: Int, mute: Boolean): String? {
        if (pid <= 0) return "client pid unknown"
        val hrInit = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED)
        val mustUninit = W32Errors.SUCCEEDED(hrInit)
        try {
            val enumeratorPtr = PointerByReference()
            val hrCreate = Ole32.INSTANCE.CoCreateInstance(
                Ole32Util.getGUIDFromString(CLSID_MM_DEVICE_ENUMERATOR),
                null,
                CLSCTX_ALL,
                Ole32Util.getGUIDFromString(IID_IMM_DEVICE_ENUMERATOR),
                enumeratorPtr,
            )
            if (W32Errors.FAILED(hrCreate)) return "COM refused MMDeviceEnumerator: ${hrCreate.toInt()}"
            val enumerator = ComInterface(enumeratorPtr.value)
            val devicePtr = PointerByReference()
            // GetDefaultAudioEndpoint(EDataFlow, ERole, IMMDevice**): the
            // enums pass BY VALUE (raw ints), only the out-param is a ref.
            val hrDevice = enumerator.invokeHresult(
                VT_ENUMERATOR_GET_DEFAULT_ENDPOINT,
                ENDPOINT_RENDER,
                ROLE_MULTIMEDIA,
                devicePtr,
            )
            if (W32Errors.FAILED(hrDevice) || devicePtr.value == null) {
                return "no default audio render device: ${hrDevice.toInt()}"
            }
            val device = ComInterface(devicePtr.value)
            val managerPtr = PointerByReference()
            // Activate(REFIID, DWORD, PROPSPEC*, void**): the IID rides by
            // reference (JNA passes Structures as pointers), CLSCTX by value.
            val hrActivate = device.invokeHresult(
                VT_DEVICE_ACTIVATE,
                Ole32Util.getGUIDFromString(IID_AUDIO_SESSION_MANAGER2),
                CLSCTX_ALL,
                null,
                managerPtr,
            )
            if (W32Errors.FAILED(hrActivate) || managerPtr.value == null) {
                return "client device has no audio session manager: ${hrActivate.toInt()}"
            }
            val manager = ComInterface(managerPtr.value)
            val sessionsPtr = PointerByReference()
            val hrSessions = manager.invokeHresult(VT_MANAGER_GET_SESSION_ENUMERATOR, sessionsPtr)
            if (W32Errors.FAILED(hrSessions) || sessionsPtr.value == null) {
                return "audio session enumeration refused: ${hrSessions.toInt()}"
            }
            val sessions = ComInterface(sessionsPtr.value)
            val count = IntByReference()
            val hrCount = sessions.invokeHresult(VT_SESSIONS_GET_COUNT, count)
            if (W32Errors.FAILED(hrCount)) return "audio session count refused: ${hrCount.toInt()}"
            var matched = false
            var mutedAny = false
            val failures = mutableListOf<String>()
            for (index in 0 until count.value) {
                val sessionPtr = PointerByReference()
                // GetSession(int, IAudioSessionControl**): the index by value.
                val hrSession = sessions.invokeHresult(
                    VT_SESSIONS_GET_SESSION,
                    index,
                    sessionPtr,
                )
                if (W32Errors.FAILED(hrSession) || sessionPtr.value == null) continue
                val session = ComInterface(sessionPtr.value)
                try {
                    val control2 = session.queryInterface(IID_AUDIO_SESSION_CONTROL2) ?: continue
                    try {
                        val ownerPid = IntByReference()
                        val hrPid = control2.invokeHresult(VT_CONTROL2_GET_PROCESS_ID, ownerPid)
                        if (W32Errors.FAILED(hrPid) || ownerPid.value != pid) continue
                        matched = true
                        val simpleVolume = session.queryInterface(IID_SIMPLE_AUDIO_VOLUME)
                        if (simpleVolume == null) {
                            failures += "session volume interface refused"
                            continue
                        }
                        try {
                            // SetMute(BOOL, LPCGUID): BOOL by value, context guid nullable.
                            val hrMute = simpleVolume.invokeHresult(
                                VT_SIMPLE_VOLUME_SET_MUTE,
                                if (mute) 1 else 0,
                                null,
                            )
                            if (W32Errors.SUCCEEDED(hrMute)) mutedAny = true
                            else failures.add("SetMute refused: ${hrMute.toInt()}")
                        } finally {
                            simpleVolume.release()
                        }
                    } finally {
                        control2.release()
                    }
                } finally {
                    session.release()
                }
            }
            return when {
                !matched -> "no audio session found for pid $pid yet"
                failures.isNotEmpty() -> failures.joinToString("; ")
                !mutedAny -> "no audio session was muted"
                else -> null
            }
        } finally {
            if (mustUninit) Ole32.INSTANCE.CoUninitialize()
        }
    }

    /** Thin COM wrapper over JNA's Unknown with vtable-index calls. */
    private class ComInterface(pointer: Pointer) : Unknown(pointer) {
        fun queryInterface(iid: String): ComInterface? {
            val out = PointerByReference()
            val hr = QueryInterface(Guid.REFIID(Guid.IID(iid)), out)
            if (W32Errors.FAILED(hr) || out.value == null) return null
            return ComInterface(out.value)
        }

        fun invokeHresult(vtableIndex: Int, vararg args: Any?): WinNT.HRESULT =
            WinNT.HRESULT(_invokeNativeInt(vtableIndex, args))

        fun release() {
            Release()
        }
    }

    // --- fixed Core Audio ABI facts (mmdeviceapi.h / audiopolicy.h) --------

    private const val CLSID_MM_DEVICE_ENUMERATOR = "{BCDE0395-E52F-467C-8E3D-C4579291692E}"
    private const val IID_IMM_DEVICE_ENUMERATOR = "{A95664D2-9614-4F35-A746-DE8DB63617E6}"
    private const val IID_AUDIO_SESSION_MANAGER2 = "{77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}"
    private const val IID_AUDIO_SESSION_CONTROL2 = "{BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D}"
    private const val IID_SIMPLE_AUDIO_VOLUME = "{87CE5498-68D6-44E5-9215-6DA47EF883D8}"

    // Vtable slots after IUnknown(0-2).
    private const val VT_ENUMERATOR_GET_DEFAULT_ENDPOINT = 4
    private const val VT_DEVICE_ACTIVATE = 3
    private const val VT_MANAGER_GET_SESSION_ENUMERATOR = 5
    private const val VT_SESSIONS_GET_COUNT = 3
    private const val VT_SESSIONS_GET_SESSION = 4
    private const val VT_CONTROL2_GET_PROCESS_ID = 14
    private const val VT_SIMPLE_VOLUME_SET_MUTE = 5

    private const val ENDPOINT_RENDER = 0 // eRender
    private const val ROLE_MULTIMEDIA = 1 // eMultimedia

    /** CLSCTX_ALL (not exported by jna-platform): INPROC_SERVER | INPROC_HANDLER | LOCAL_SERVER | REMOTE_SERVER. */
    private const val CLSCTX_ALL = 0x17

    /** The client's audio session appears once DirectSound initializes; the
     * poll budget covers a cold first boot without stalling teardown. */
    private const val SESSION_WAIT_TIMEOUT_MS = 20_000L
    private const val SESSION_POLL_MS = 500L
}
