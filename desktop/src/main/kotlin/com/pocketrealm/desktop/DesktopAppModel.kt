package com.pocketrealm.desktop

import com.pocketrealm.bots.BotCustomPresets
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.AccountProvisionResult
import com.pocketrealm.supervisor.DurableRuntimeSupervisor
import com.pocketrealm.supervisor.RuntimeOperation
import com.pocketrealm.supervisor.RuntimeSnapshot
import com.pocketrealm.supervisor.StopMode
import com.pocketrealm.supervisor.UserAccountStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The desktop app's state holder: the shared DurableRuntimeSupervisor over
 * the DesktopRuntimeBackend (one JVM, no IPC — the Android app's
 * RealmService/RuntimeSupervisorClient layer collapses into direct calls),
 * the settings store, the stored account, and the bot preset store.
 *
 * Every screen reads from this model; mutations go through its verbs so
 * the supervisor's operation serialization stays intact.
 */
@Suppress("TooManyFunctions") // the app-model seam: one verb per supervisor/store operation
class DesktopAppModel(
    val roots: DesktopStorageRoots = DesktopStorageRoots(),
    private val clientDirOverride: File? = null,
) : AutoCloseable {
    private val settingsStore = DesktopSettingsStore(roots.settingsFile)
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<Settings.Snapshot> = _settings.asStateFlow()

    /** Serializes read-transform-write settings updates: the file-picker
     * worker thread writes from outside the UI thread, and two concurrent
     * updates must not interleave their read-modify-write (the store's
     * temp file is unique per call; the lost-update race is what this
     * lock closes). */
    private val settingsLock = Any()

    val accountStore = UserAccountStore(File(roots.root, "user-account"))
    private val _storedAccount = MutableStateFlow(accountStore.loadOrQuarantine())
    val storedAccount: StateFlow<UserAccountStore.UserAccount?> = _storedAccount.asStateFlow()

    val backend = DesktopRuntimeBackend(roots, clientDirOverride)
    val supervisor = DurableRuntimeSupervisor(
        backend,
        DesktopSupervisorJournal(roots.supervisorJournalDir),
    )
    val state: StateFlow<RuntimeSnapshot> = supervisor.state

    /** The bot preset store installed by the backend (filesDir/bots twin). */
    val presets: com.pocketrealm.bots.BotPresetStore
        get() = requireNotNull(BotCustomPresets.store()) { "preset store not installed" }

    init {
        roots.ensureDirectories()
    }

    /** Resolve the folder WoW.exe launches from, for the Home status line. */
    fun resolveClientDir(): File? = with(settings.value) {
        when {
            clientDirOverride != null -> clientDirOverride
            clientDir.isNotBlank() -> File(clientDir)
            else -> File("C:/Vanilla wow 1.12.1")
        }.takeIf { File(it, "WoW.exe").isFile }
    }

    fun updateSettings(transform: (Settings.Snapshot) -> Settings.Snapshot) {
        synchronized(settingsLock) {
            val next = transform(_settings.value)
            settingsStore.save(next)
            _settings.value = next
        }
    }

    suspend fun startRealm(includeClient: Boolean): RuntimeOperation =
        supervisor.start(DESKTOP_PROFILE, includeClient)

    suspend fun relaunchClient(): RuntimeOperation = supervisor.relaunchClient()

    suspend fun saveAndExit(): RuntimeOperation = supervisor.stop(StopMode.GRACEFUL)

    suspend fun recover(): RuntimeOperation = supervisor.recover()

    suspend fun consentedForceStopOrphanStack(): RuntimeOperation =
        supervisor.consentedForceStopOrphanStack()

    /**
     * Create (or verify) the local account through the supervisor's control
     * channel and remember it for auto-login on success — the Android Home
     * account-card contract. Secrets never leave this model's callers.
     */
    suspend fun createAccount(
        username: String,
        password: String,
        gmLevel: Int,
    ): AccountProvisionResult {
        val result = supervisor.provisionAccount(username, password, gmLevel)
        if (result.ok && result.accountId > 0 &&
            result.code in setOf("ACCOUNT_CREATED", "ACCOUNT_VERIFIED")
        ) {
            withContext(Dispatchers.IO) {
                _storedAccount.value = accountStore.save(username, password, result.accountId, result.gmLevel)
            }
        }
        return result
    }

    fun clearAccount() {
        accountStore.clear()
        _storedAccount.value = null
    }

    /**
     * Best-effort auto-login after a client launch: waits for the client
     * component to be observable, then types the stored credentials into
     * the launched WoW.exe (Win32 SendInput — the Android client stack's
     * synthetic-input twin). Runs on IO; every failure degrades to the
     * manual login screen and is logged, never thrown.
     */
    suspend fun autoLoginIfEnabled() {
        val snapshot = _settings.value
        if (!snapshot.autoLoginOnLaunch) return
        val account = _storedAccount.value ?: return
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + AUTO_LOGIN_WAIT_MS
            var pid: Int? = null
            while (System.currentTimeMillis() < deadline) {
                val client = runCatching {
                    backend.observe(com.pocketrealm.supervisor.RuntimeComponent.CLIENT)
                }.getOrNull()
                if (client != null && client.ready) {
                    pid = client.pid
                    break
                }
                Thread.sleep(AUTO_LOGIN_POLL_MS)
            }
            val target = pid ?: run {
                DesktopLog.w("AutoLogin", "client never became observable; skipping")
                return@withContext
            }
            // Give the client's window a moment to reach its login screen.
            Thread.sleep(AUTO_LOGIN_SETTLE_MS)
            val failure = Win32AutoLogin.tryLogin(target, account.username, account.password)
            if (failure != null) {
                DesktopLog.w("AutoLogin", "auto-login skipped: $failure")
            } else {
                DesktopLog.i("AutoLogin", "auto-login keystrokes delivered")
            }
        }
    }

    override fun close() {
        supervisor.close()
    }

    companion object {
        /** The supervisor launch profile id (not the bot profile — the bot
         * selection rides the settings snapshot into world start). */
        const val DESKTOP_PROFILE = "local"

        private const val AUTO_LOGIN_WAIT_MS = 60_000L
        private const val AUTO_LOGIN_POLL_MS = 1_000L
        private const val AUTO_LOGIN_SETTLE_MS = 3_000L
    }
}
