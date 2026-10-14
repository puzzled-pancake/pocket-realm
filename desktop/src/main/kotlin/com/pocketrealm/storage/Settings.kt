package com.pocketrealm.storage

import com.pocketrealm.bots.BotAdvancedSettings
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.client.TimeframeStages
import com.pocketrealm.desktop.realmstatus.RealmStatusVisibility
import com.pocketrealm.server.NearbyInteractPolicy
import com.pocketrealm.supervisor.RuntimeMode
import org.json.JSONObject

/**
 * Desktop twin of the Android app's storage/Settings. Carries the
 * Windows subset: the Wine-era display/renderer/Vulkan fields have
 * no meaning here (the WoW client runs natively), and the LLM lane is
 * EXTERNAL-ONLY (no embedded model manager, no NPU/decode-core fields —
 * the desktop conf emission always takes the external-endpoint branch).
 *
 * Defaults match the Android product's topology invariants — LOCAL mode,
 * LAN off, the default bot profile selected (a first boot has bots), and
 * auto-login ON (safely inert until an account is provisioned, per the
 * shared AutoLoginPolicy gate — DESK-134's shipped-defaults alignment).
 * The fromJson fallbacks stay the pre-DESK-134 values ("", false) on
 * purpose: defaults apply to fresh installs only, never to existing
 * settings files whose explicit keys must keep decoding as written.
 */
object Settings {

    /**
     * The game-audio mode (the Android `Settings.AudioMode` twin, DESK-139):
     * ON is the game with sound, OFF mutes the client for the launch (the
     * Config.wtf master-sound overlay leg plus the per-process OS audio
     * session mute). Default ON, like Android.
     */
    enum class AudioMode { OFF, ON }

    /**
     * DESK-5/47/78: the database provider lane the user selects. MARIADB is
     * the shipped default (the default-flip: desktop matches the on-device
     * product, whose default is already MariaDB) — first boot initializes
     * the MariaDB datadir through the engine lane (DesktopMariaDbEngine via
     * DesktopDatabaseService), and an unstaged provider refuses with the
     * honest DB-LINK copy rather than silently falling back. SQLITE remains
     * SELECTABLE as the explicit dev/compat provider: an explicit persisted
     * SQLITE selection keeps the seeded-datadir materialization serving
     * (the legacy-adoption path for pre-flip desktop state), so nothing
     * that decoded as written ever flips underneath the user.
     */
    enum class DatabaseProvider { SQLITE, MARIADB }

    /**
     * client_tweaks schema history (the Android twin's history, verbatim):
     *  1 — explicitly versioned user choices (the unversioned era's
     *      accidental enable-everything state was migrated once to pristine
     *      Vanilla);
     *  2 — one-time first-boot defaults for widescreen displays (fov,
     *      quick-loot, camera-skip fix, max camera distance). Only
     *      never-configured keys (schema < 1) gain the new defaults;
     *      schema-1 explicit choices are preserved untouched. Every settings
     *      write keeps stamping this constant ([Snapshot.toJson] writes it)
     *      or every write would regress the stamp and re-run the migration.
     */
    internal const val TWEAKS_SCHEMA_VERSION = 2

    /**
     * DESK-13: the schema-v2 defaults migration — the desktop twin of the
     * Android `clientTweaksDefaultsMigration` (the DataStore migration
     * shell is android-side; the decision logic is this pure function).
     * A stored schema below [TWEAKS_SCHEMA_VERSION] migrates once:
     * never-configured stores (schema < 1) on a widescreen display gain
     * the recommended tweak set (fov, quick-loot, camera-skip fix, max
     * camera distance) on top of the stored request; schema-1 explicit
     * choices are preserved and only stamped. The 4:3 coupling and the
     * user's later toggles always win — this runs at most once, ever
     * (the first write after the migration persists the fresh stamp).
     *
     * [storedSchema] null (a missing stamp — every pre-DESK-13 desktop
     * settings file, and the missing-file fresh install) reads as 0.
     */
    internal fun clientTweaksDefaultsMigration(
        resolvesWidescreen: () -> Boolean,
        storedSchema: Int?,
        storedTweaksJson: String?,
    ): ClientTweaksConfig {
        val priorSchema = storedSchema ?: 0
        val current = ClientTweaksConfig.fromJson(storedTweaksJson)
        if (priorSchema >= TWEAKS_SCHEMA_VERSION) return current
        val neverConfigured = priorSchema < 1
        return if (neverConfigured && resolvesWidescreen()) {
            current.copy(
                fovEnabled = true,
                quicklootEnabled = true,
                cameraSkipFixEnabled = true,
                maxCameraDistanceEnabled = true,
            )
        } else {
            current
        }
    }

    /**
     * The desktop mapping of Android's `resolvesWidescreenVirtualDisplay`:
     * the primary display is landscape (width > height). The Windows client
     * has no virtual-display profiles, so the physical aspect decides — a
     * portrait display maps Android's fixed-aspect Classic profile, which
     * disables the FoV tweak at selection time. Fails open to true exactly
     * like the Android posture (`runCatching { ... }.getOrDefault(true)`):
     * a failed display read must not block the one-time defaults.
     */
    internal fun resolvesWidescreenDisplay(): Boolean = runCatching {
        val size = java.awt.Toolkit.getDefaultToolkit().screenSize
        size.width > size.height
    }.getOrDefault(true)

    /**
     * Resolved once per process (the Android resolver is captured once at
     * DataStore creation; the physical display's aspect is not a per-read
     * input) and injected into every decode so the migration is pure.
     */
    private val widescreenResolver: () -> Boolean by lazy { ::resolvesWidescreenDisplay }

    /**
     * Tunable auto-login timing set — the desktop JVM twin of the Android
     * `Settings.AutoLoginTimings` (same field names, defaults, and clamps;
     * DESK-133). Defaults equal the historical desktop companion constants'
     * role in `DesktopAppModel`/`Win32AutoLogin`, so fresh and existing
     * installs resolve to the documented bounded-wait behavior unless a
     * user edits the persisted `autoLoginTimings` block. Persisted inside
     * the settings JSON as one nested object (`autoLoginTimings`).
     */
    data class AutoLoginTimings(
        val pollIntervalMs: Long = 250L,
        val requiredStablePolls: Int = 4,
        val loginUiSettleMs: Long = 8_000L,
        val sessionTimeoutMs: Long = 300_000L,
        val drainPollMs: Long = 50L,
        val inputDrainTimeoutMs: Long = 5_000L,
        val imeKeyDwellMs: Long = 50L,
        val imeKeyGapMs: Long = 10L,
        val fieldSettleMs: Long = 300L,
        val pointerDwellMs: Long = 80L,
    ) {
        fun minimumInputDrainTimeoutMs(): Long =
            3L * (pointerDwellMs + fieldSettleMs) +
                32L * (imeKeyDwellMs + imeKeyGapMs) +
                2L * drainPollMs

        fun normalized(): AutoLoginTimings {
            val ranged = copy(
                pollIntervalMs = pollIntervalMs.coerceIn(100, 1_000),
                requiredStablePolls = requiredStablePolls.coerceIn(1, 12),
                loginUiSettleMs = loginUiSettleMs.coerceIn(1_000, 30_000),
                sessionTimeoutMs = sessionTimeoutMs.coerceIn(60_000, 900_000),
                drainPollMs = drainPollMs.coerceIn(25, 200),
                inputDrainTimeoutMs = inputDrainTimeoutMs.coerceIn(1_000, 30_000),
                imeKeyDwellMs = imeKeyDwellMs.coerceIn(20, 200),
                imeKeyGapMs = imeKeyGapMs.coerceIn(0, 100),
                fieldSettleMs = fieldSettleMs.coerceIn(50, 2_000),
                pointerDwellMs = pointerDwellMs.coerceIn(20, 500),
            )
            return ranged.copy(inputDrainTimeoutMs = ranged.inputDrainTimeoutMs
                .coerceAtLeast(ranged.minimumInputDrainTimeoutMs()))
        }

        fun toControlJson(): String = JSONObject()
            .put("pollIntervalMs", pollIntervalMs)
            .put("requiredStablePolls", requiredStablePolls)
            .put("loginUiSettleMs", loginUiSettleMs)
            .put("sessionTimeoutMs", sessionTimeoutMs)
            .put("drainPollMs", drainPollMs)
            .put("inputDrainTimeoutMs", inputDrainTimeoutMs)
            .put("imeKeyDwellMs", imeKeyDwellMs)
            .put("imeKeyGapMs", imeKeyGapMs)
            .put("fieldSettleMs", fieldSettleMs)
            .put("pointerDwellMs", pointerDwellMs)
            .toString()

        companion object {
            private val CONTROL_KEYS = setOf(
                "pollIntervalMs", "requiredStablePolls", "loginUiSettleMs",
                "sessionTimeoutMs", "drainPollMs", "inputDrainTimeoutMs",
                "imeKeyDwellMs", "imeKeyGapMs", "fieldSettleMs", "pointerDwellMs",
            )

            /**
             * The strict control-channel decoder (the Android twin's
             * contract, kept verbatim): exact key set, in-bounds values.
             * The persisted-settings codec uses the lenient
             * [decodePersisted] instead — one bad timing must never reset
             * the whole snapshot.
             */
            fun fromControlJson(raw: String): AutoLoginTimings {
                require(raw.toByteArray(Charsets.UTF_8).size <= 4_096) {
                    "auto-login timing payload is too large"
                }
                val value = JSONObject(raw)
                val actual = buildSet { value.keys().forEachRemaining { add(it) } }
                require(actual == CONTROL_KEYS) { "auto-login timing schema mismatch" }
                val requested = AutoLoginTimings(
                    pollIntervalMs = value.getLong("pollIntervalMs"),
                    requiredStablePolls = value.getInt("requiredStablePolls"),
                    loginUiSettleMs = value.getLong("loginUiSettleMs"),
                    sessionTimeoutMs = value.getLong("sessionTimeoutMs"),
                    drainPollMs = value.getLong("drainPollMs"),
                    inputDrainTimeoutMs = value.getLong("inputDrainTimeoutMs"),
                    imeKeyDwellMs = value.getLong("imeKeyDwellMs"),
                    imeKeyGapMs = value.getLong("imeKeyGapMs"),
                    fieldSettleMs = value.getLong("fieldSettleMs"),
                    pointerDwellMs = value.getLong("pointerDwellMs"),
                )
                val normalized = requested.normalized()
                require(normalized == requested) { "auto-login timings are outside supported bounds" }
                return requested
            }

            /**
             * Lenient persisted-settings decoder (the desktop snapshot
             * codec's degrade-per-field posture): a missing or empty block
             * yields the defaults; each field falls back individually and
             * the result is normalized into the supported bounds. Never
             * throws on user-edited JSON.
             */
            fun decodePersisted(value: JSONObject?): AutoLoginTimings {
                if (value == null) return AutoLoginTimings()
                return AutoLoginTimings(
                    pollIntervalMs = value.optLong("pollIntervalMs", 250L),
                    requiredStablePolls = value.optInt("requiredStablePolls", 4),
                    loginUiSettleMs = value.optLong("loginUiSettleMs", 8_000L),
                    sessionTimeoutMs = value.optLong("sessionTimeoutMs", 300_000L),
                    drainPollMs = value.optLong("drainPollMs", 50L),
                    inputDrainTimeoutMs = value.optLong("inputDrainTimeoutMs", 5_000L),
                    imeKeyDwellMs = value.optLong("imeKeyDwellMs", 50L),
                    imeKeyGapMs = value.optLong("imeKeyGapMs", 10L),
                    fieldSettleMs = value.optLong("fieldSettleMs", 300L),
                    pointerDwellMs = value.optLong("pointerDwellMs", 80L),
                ).normalized()
            }
        }
    }

    data class Snapshot(
        val runtimeMode: RuntimeMode = RuntimeMode.LOCAL,
        val allowLanPlayers: Boolean = false,
        /** DESK-15/24/42: the canonical LAN IPv4 endpoint (private or
         * link-local) the LAN modes bind to / join against. Empty in LOCAL
         * mode; validated through the shared RealmEndpoint parsing before a
         * launch spec is built. */
        val lanAddress: String = "",
        /**
         * Design-tunnel §4.4/§5: the optional ADVERTISED address for
         * internet (tunnel) hosting — the tailnet/zone numeric IPv4 the
         * realmlist row carries while the servers bind the wildcard.
         * Persisted verbatim (validated for display through the shared
         * AdvertisedAddress parsing, exactly the lanAddress posture);
         * empty = plain LAN hosting (the schema-1 behavior, byte-identical).
         */
        val tunnelAdvertisedAddress: String = "",
        val setupComplete: Boolean = false,
        /** DESK-134: the default bot profile — a first boot has bots, as on
         * Android. The fromJson fallback stays "" (existing files decode
         * exactly as written; only absent-file installs get this default). */
        val botProfileId: String = BotProfiles.defaultProfile.id,
        val botPopulationTarget: Int = 0,
        val botSavedPresetId: String? = null,
        /** DESK-124: the launcher-level advanced tuning override (the
         * Android storage/Settings.kt:366-367 twin). When enabled and no
         * saved preset is selected, the shared BotSelection.resolve takes
         * the advanced (non-preset) branch — the launch-time advanced
         * lane desktop could never reach before. Default OFF, values
         * defaulting to the default profile's shape. */
        val botAdvancedEnabled: Boolean = false,
        val botAdvanced: BotAdvancedSettings =
            BotAdvancedSettings.fromProfile(BotProfiles.defaultProfile),
        /** DESK-134: auto-login ships ON (inert until an account is
         * provisioned — the shared AutoLoginPolicy gate refuses without
         * one). The fromJson fallback stays false. */
        val autoLoginOnLaunch: Boolean = true,
        /** DESK-133: the persisted auto-login timing surface; see
         * [effectiveAutoLoginTimings]. */
        val autoLoginTimings: AutoLoginTimings = AutoLoginTimings(),
        val lastActiveGeneration: Int = 0,
        val worldDebugLogs: Boolean = false,
        val clientDir: String = "",
        val llmEnabled: Boolean = false,
        val llmExternalUrl: String = "",
        val llmExternalModel: String = "",
        val llmExternalApiKey: String = "",
        val llmCloudChatter: Boolean = false,
        val llmBanter: Boolean = true,
        val llmAmbience: Boolean = false,
        val llmMaxNewTokens: Int = 0,
        val llmGenerationTimeout: Int = 0,
        /**
         * DESK-48/80/103: the LLM Source selection (the Android
         * `llmExternalMode` twin). TRUE (the desktop default — the reviewed
         * posture, so an old settings file loads with byte-identical
         * behavior) = the external endpoint fields drive the conf; FALSE =
         * the on-device lane: a staged registry GGUF served by the local
         * llama-server sidecar, whose health-probed loopback URL feeds the
         * same conf plumbing.
         */
        val llmExternalMode: Boolean = true,
        /** DESK-103: the selected LlmModelRegistry id ("": the registry
         * default — byId never throws on unknown ids). */
        val llmModelId: String = "",
        /** DESK-13/27/46/58/89: the client-tweak lane's persisted request
         * (the Android `Settings.tweaks` twin; fresh and never-configured
         * installs gain the schema-v2 widescreen first-boot set through
         * [clientTweaksDefaultsMigration]). Toggling any patch on IS the
         * consent to patch the user's client copy: launch publishes the
         * verified `WoW.exe.patched` when the executable layout is the
         * authorized vanilla-build target. The world conf's
         * PocketRealm.NearbyInteract knob follows that same AUTHORIZED-
         * layout EFFECTIVE resolution at world start — a requested set on
         * an unqualified layout keeps the knob at 0 (DESK-58). */
        val tweaks: ClientTweaksConfig = ClientTweaksConfig(),
        /** DESK-139: the game-audio mode, default ON (the Android twin's
         * default); OFF enforces the Config.wtf master-sound overlay leg and
         * applies the per-process Core Audio mute to the spawned client. */
        val audioMode: AudioMode = AudioMode.ON,
        /** DESK-5/47/78: the database provider lane, default MARIADB (the
         * default-flip — see [DatabaseProvider]; the on-device product's
         * default, now the desktop's too). One unknown enum value must not
         * throw (the audioMode posture). */
        val databaseProvider: DatabaseProvider = DatabaseProvider.MARIADB,
        /** R1-1 (desktop-twin round 1): the persisted flavor-switcher pick —
         * the Android storage twin's field (android storage/Settings.kt), so
         * the desktop lane survives a restart at the W4 multi-qualified flip
         * instead of every fail-loud resolver refusing until a re-pick. ""
         * = no persisted pick (the sole-qualified default resolution still
         * answers; the absent-key decode stays "" byte-stably). */
        val selectedFlavorId: String = "",
        /** DESK-132: the nearby-use debounce, normalized on load and on write
         * through the shared NearbyInteractPolicy (the Android :801/:1077
         * twin) and threaded into the world conf's
         * PocketRealm.NearbyInteractCooldownMs. */
        val nearbyInteractTriggerGuardMs: Int = NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
        /** DESK-26: the player's edited prompt pack JSON ("": the default
         * pack). Staged as the conf's promptPackFile through
         * LlmPromptPack.resolve at every world start. */
        val llmPromptPackJson: String = "",
        /** DESK-9 embedded-sidecar decode threads (-t), the Android band. */
        val llmThreads: Int = DEFAULT_LLM_THREADS,
        /** DESK-9 embedded-sidecar offload layers (-ngl); 0 = CPU-only
         *  (the desktop default — LlmRuntimePolicy.DEFAULT_OFFLOAD_LAYERS). */
        val llmOffloadLayers: Int = DEFAULT_LLM_OFFLOAD_LAYERS,
        /** DESK-9 the advanced engine-settings gate (the Android tier). */
        val llmAdvanced: Boolean = false,
        /**
         * DESK-84: the input safe-mode gate (the desktop twin of the Android
         * `input_safe_mode` preference). When true, the launch-boundary addon
         * projection projects NONE of the managed add-on folders (and skips
         * the binding journal capture), so a misbehaving add-on can be
         * excluded from the client without uninstalling it.
         */
        val inputSafeMode: Boolean = false,
        /**
         * design-remote-accounts: friend access for a LAN_HOST session —
         * when true AND the realm hosts on the LAN, the start brings up the
         * provisioning listener (ServerRuntimeContract.PROVISION_PORT) so
         * app-equipped friends can submit invite-token requests. Off (the
         * default) never binds the socket (§6: friend access off = the
         * closed state). Applies at the next realm start, like every
         * topology-adjacent lane.
         */
        val friendAccessEnabled: Boolean = false,
        /**
         * Design-timeframe §4.1: the persisted content-patch stage id
         * ("" = the qualified flavor's final patch). Validation happens at
         * read time against the qualified flavor through the shared
         * [TimeframeStages.resolveStored] — a value that is not a registered
         * stage of the qualified lane (a stale stage of an unqualified
         * flavor, a typo, a retired id) degrades to "" with the same
         * one-field discipline the provider selection uses, so a flavor
         * change can never strand a world silently. Applies at the next
         * realm start as the conf's PocketRealm.TimeframeStage.
         */
        val timeframeStage: String = "",
        /**
         * Realm-status browser (design realm-status-browser.md §4/§6.3): the
         * page-visibility switch. OFF is the FAIL-CLOSED default — no page
         * server exists, nothing listens (§1). The boot semantics live in
         * the visibility machine: a persisted FRIENDS restores, a persisted
         * PUBLIC re-arms WITHOUT auto-resuming (the app never auto-starts
         * cloudflared at launch). One unknown enum value must not throw (the
         * audioMode posture) and degrades to OFF.
         */
        val realmStatusVisibility: RealmStatusVisibility = RealmStatusVisibility.OFF,
        /**
         * Realm-status browser §6.3: the host-set page description (MOTD).
         * Clamped AT THE SETTINGS WRITE path (≤280 chars, control characters
         * stripped — the shared [com.pocketrealm.desktop.realmstatus]
         * clamp); the payload and page render additionally escape whatever
         * they receive (defense in depth).
         */
        val realmDescription: String = "",
        /**
         * Realm-status browser §3.4/§6.3: explicit consent gating
         * `players.characterNames` in the public roster. Default OFF.
         */
        val showCharacterNames: Boolean = false,
        /**
         * Realm-status browser §5.7/§6.3: optional explicit cloudflared
         * binary path; missing/non-executable → the §5.7 outcome (the PUBLIC
         * transition lands with tunnelState = "binary-missing" and the
         * failure copy). Empty resolves the PATH lookup.
         */
        val cloudflaredPath: String = "",
        /**
         * Realm-status browser §6.2/§6.3: the PUBLIC per-visitor rate-limit
         * test hook (default 60/min) — lets the app-side suite trip the
         * limiter with a small value instead of 61 requests.
         */
        val pageRateLimitPerMinute: Int = DEFAULT_PAGE_RATE_LIMIT_PER_MINUTE,
        /**
         * W6-N1 (docs/NOSTR-IROH-BUILD-PLAN.md): the host-side Nostr
         * listing heartbeat switch. FAIL-CLOSED default OFF — publishing
         * a world-readable listing is an explicit host choice, gated
         * further by the realm-status PUBLIC visibility (never published
         * while OFF/FRIENDS) and a live world.
         */
        val nostrDiscoveryEnabled: Boolean = false,
        /**
         * W6-N1: the user-editable relay set (owner decision 4). Empty
         * means the shipped defaults (damus.io, nos.lol, primal,
         * nostr.band) apply; a stored list is the effective set verbatim.
         */
        val nostrRelays: List<String> = emptyList(),
        /**
         * W6-N1: the realm's Nostr identity (a BIP-340 key, not a user
         * account). Generated once at first enable, persisted here, never
         * displayed. Empty = not generated yet.
         */
        val nostrPrivateKeyHex: String = "",
    ) {
        /** The timings the auto-login automation runs with: the persisted
         * set, normalized into the supported bounds (the desktop has no
         * separate advanced toggle — the surface is always live). */
        fun effectiveAutoLoginTimings(): AutoLoginTimings = autoLoginTimings.normalized()

        /** R1-1: the persisted pick as the resolver seam reads it — null
         * when nothing was ever persisted (the Android storage twin's
         * helper). */
        fun selectedFlavorOrNull(): String? = selectedFlavorId.takeIf { it.isNotEmpty() }

        companion object {
            /**
             * Lenient per-field decode (one unknown value must never reset
             * the WHOLE snapshot) carrying the DESK-13 schema-v2 defaults
             * migration: a persisted stamp below [TWEAKS_SCHEMA_VERSION]
             * migrates the tweaks lane once (never-configured widescreen
             * installs gain the recommended set), and the returned snapshot
             * carries the fresh stamp so the next write persists it. Reads
             * before that write re-apply deterministically (same stored
             * state, same migrated result). [resolvesWidescreen] is
             * injectable so the migration stays testable.
             */
            @Suppress("LongMethod") // one reviewed decode literal (the toJson twin's suppression)
            fun fromJson(
                text: String,
                resolvesWidescreen: () -> Boolean = widescreenResolver,
            ): Snapshot {
                val value = org.json.JSONObject(text)
                return Snapshot(
                    // Degrade per field (the Android twin's posture): one
                    // unknown enum value must not throw and reset the
                    // WHOLE snapshot to defaults on the next load.
                    runtimeMode = runCatching {
                        RuntimeMode.valueOf(value.optString("runtimeMode", RuntimeMode.LOCAL.name))
                    }.getOrDefault(RuntimeMode.LOCAL),
                    allowLanPlayers = value.optBoolean("allowLanPlayers", false),
                    lanAddress = value.optString("lanAddress", ""),
                    tunnelAdvertisedAddress = value.optString("tunnelAdvertisedAddress", ""),
                    setupComplete = value.optBoolean("setupComplete", false),
                    // DESK-134: the fallbacks keep the pre-alignment values —
                    // an existing file (or an absent key) must not flip to
                    // the new shipped defaults; they apply to fresh installs
                    // only (a missing file loads via the constructor defaults).
                    botProfileId = value.optString("botProfileId", ""),
                    botPopulationTarget = value.optInt("botPopulationTarget", 0),
                    botSavedPresetId = if (value.isNull("botSavedPresetId")) null
                    else value.optString("botSavedPresetId").ifEmpty { null },
                    // DESK-124: byte-stable fallbacks for existing files —
                    // the override stays OFF and the block decodes per field
                    // (an invalid block degrades to the default profile's
                    // shape, never resetting the whole snapshot).
                    botAdvancedEnabled = value.optBoolean("botAdvancedEnabled", false),
                    botAdvanced = decodeBotAdvanced(value.optJSONObject("botAdvanced")),
                    autoLoginOnLaunch = value.optBoolean("autoLoginOnLaunch", false),
                    autoLoginTimings = AutoLoginTimings.decodePersisted(
                        value.optJSONObject("autoLoginTimings"),
                    ),
                    lastActiveGeneration = value.optInt("lastActiveGeneration", 0),
                    worldDebugLogs = value.optBoolean("worldDebugLogs", false),
                    clientDir = value.optString("clientDir", ""),
                    llmEnabled = value.optBoolean("llmEnabled", false),
                    llmExternalUrl = value.optString("llmExternalUrl", ""),
                    llmExternalModel = value.optString("llmExternalModel", ""),
                    llmExternalApiKey = value.optString("llmExternalApiKey", ""),
                    llmCloudChatter = value.optBoolean("llmCloudChatter", false),
                    llmBanter = value.optBoolean("llmBanter", true),
                    llmAmbience = value.optBoolean("llmAmbience", false),
                    llmMaxNewTokens = value.optInt("llmMaxNewTokens", 0),
                    llmGenerationTimeout = value.optInt("llmGenerationTimeout", 0),
                    // DESK-48/103: the Source toggle (default TRUE = the
                    // reviewed external-only posture for pre-toggle files).
                    llmExternalMode = value.optBoolean("llmExternalMode", true),
                    llmModelId = value.optString("llmModelId", ""),
                    // The persisted tweaks JSON decodes leniently (one corrupt
                    // edit falls back to the pristine-Vanilla request, never
                    // resetting the whole snapshot — the Android twin's
                    // ClientTweaksConfig.fromJson posture) and rides the
                    // DESK-13 schema-v2 defaults migration.
                    tweaks = clientTweaksDefaultsMigration(
                        resolvesWidescreen = resolvesWidescreen,
                        storedSchema = value.optInt("tweaksSchema", 0),
                        storedTweaksJson = value.optString("tweaks"),
                    ),
                    // DESK-139: one unknown enum value must not throw (the
                    // Android :799 posture).
                    audioMode = runCatching {
                        AudioMode.valueOf(value.optString("audioMode", AudioMode.ON.name))
                    }.getOrDefault(AudioMode.ON),
                    // DESK-5/47/78 (default-flip): same lenient enum posture;
                    // the absent key decodes to the shipped MARIADB default
                    // (a store that never chose a provider is a fresh init on
                    // MariaDB), while an EXPLICIT value decodes as written —
                    // a pre-flip file's "SQLITE" keeps serving the SQLite
                    // lane (the legacy-adoption-by-persisted-selection path).
                    databaseProvider = runCatching {
                        DatabaseProvider.valueOf(
                            value.optString("databaseProvider", DatabaseProvider.MARIADB.name),
                        )
                    }.getOrDefault(DatabaseProvider.MARIADB),
                    // R1-1: absent key decodes "" (byte-stable for existing
                    // files; the sole-qualified default resolution answers).
                    selectedFlavorId = value.optString("selectedFlavorId", ""),
                    // DESK-132: normalized exactly like the Android :801 load leg.
                    nearbyInteractTriggerGuardMs = NearbyInteractPolicy.normalizeTriggerGuardMs(
                        value.optInt(
                            "nearbyInteractTriggerGuardMs",
                            NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS,
                        ),
                    ),
                    llmPromptPackJson = value.optString("llmPromptPackJson", ""),
                    llmThreads = value.optInt("llmThreads", DEFAULT_LLM_THREADS),
                    llmOffloadLayers = value.optInt("llmOffloadLayers", DEFAULT_LLM_OFFLOAD_LAYERS),
                    llmAdvanced = value.optBoolean("llmAdvanced", false),
                    inputSafeMode = value.optBoolean("inputSafeMode", false),
                    friendAccessEnabled = value.optBoolean("friendAccessEnabled", false),
                    // Design-timeframe §4.1: validated at read time against the
                    // qualified flavor — an impossible/stale stage degrades to ""
                    // (the final-patch default), never throws.
                    timeframeStage = TimeframeStages.resolveStored(
                        value.optString("timeframeStage", ""),
                    ),
                    // Realm-status browser §6.3: one unknown enum value must
                    // not throw (the audioMode posture) and degrades to the
                    // fail-closed OFF — a corrupt value can never re-arm the
                    // page server or a tunnel child on its own.
                    realmStatusVisibility = runCatching {
                        RealmStatusVisibility.valueOf(
                            value.optString("realmStatusVisibility", RealmStatusVisibility.OFF.name),
                        )
                    }.getOrDefault(RealmStatusVisibility.OFF),
                    realmDescription = value.optString("realmDescription", ""),
                    showCharacterNames = value.optBoolean("showCharacterNames", false),
                    cloudflaredPath = value.optString("cloudflaredPath", ""),
                    pageRateLimitPerMinute = value.optInt(
                        "pageRateLimitPerMinute",
                        DEFAULT_PAGE_RATE_LIMIT_PER_MINUTE,
                    ),
                    // W6-N1: the relay list decodes leniently (blank
                    // entries drop; a corrupt array degrades to the
                    // defaults); a corrupt KEY never throws the snapshot.
                    // R6-6: a valid array of GARBAGE STRINGS degrades to
                    // the defaults too — each entry re-validates through
                    // the shared RelayUrls shape/parser gate, so a
                    // hand-edited settings.json cannot arm an unparseable
                    // relay for the next browse/publish cycle.
                    nostrDiscoveryEnabled = value.optBoolean("nostrDiscoveryEnabled", false),
                    nostrRelays = runCatching {
                        val arr = value.optJSONArray("nostrRelays") ?: org.json.JSONArray()
                        val raw = (0 until arr.length()).map { arr.optString(it) }
                            .filter { it.isNotBlank() }
                        if (raw.isEmpty()) {
                            emptyList()
                        } else {
                            com.pocketrealm.discovery.nostr.RelayUrls.sanitizeList(raw)
                        }
                    }.getOrDefault(emptyList()),
                    nostrPrivateKeyHex = value.optString("nostrPrivateKeyHex", ""),
                )
            }

            /**
             * DESK-124: the per-field lenient decode of the persisted
             * advanced block; a block whose combination fails the shared
             * BotAdvancedSettings validation degrades to the default
             * profile's shape (the AutoLoginTimings.decodePersisted
             * posture).
             */
            private fun decodeBotAdvanced(block: JSONObject?): BotAdvancedSettings {
                val defaults = BotAdvancedSettings.fromProfile(BotProfiles.defaultProfile)
                if (block == null) return defaults
                return runCatching {
                    BotAdvancedSettings(
                        nearbyBotLimit = block.optInt("nearbyBotLimit", defaults.nearbyBotLimit),
                        nearbyRadius = block.optInt("nearbyRadius", defaults.nearbyRadius),
                        loginBatchSize = block.optInt("loginBatchSize", defaults.loginBatchSize),
                        maintenanceBatchSize = block.optInt(
                            "maintenanceBatchSize",
                            defaults.maintenanceBatchSize,
                        ),
                        updateIntervalMs = block.optInt("updateIntervalMs", defaults.updateIntervalMs),
                        teleportMinMinutes = block.optInt(
                            "teleportMinMinutes",
                            defaults.teleportMinMinutes,
                        ),
                        teleportMaxMinutes = block.optInt(
                            "teleportMaxMinutes",
                            defaults.teleportMaxMinutes,
                        ),
                        iterationsPerTick = block.optInt("iterationsPerTick", defaults.iterationsPerTick),
                        admissionWorldP99Ms = block.optInt(
                            "admissionWorldP99Ms",
                            defaults.admissionWorldP99Ms,
                        ),
                        syncLevelWithPlayers = block.optBoolean(
                            "syncLevelWithPlayers",
                            defaults.syncLevelWithPlayers,
                        ),
                        limitCombatActivity = block.optBoolean(
                            "limitCombatActivity",
                            defaults.limitCombatActivity,
                        ),
                        activeBotPercent = block.optInt("activeBotPercent", defaults.activeBotPercent),
                        autoDoQuests = block.optBoolean("autoDoQuests", defaults.autoDoQuests),
                        allowBotChat = block.optBoolean("allowBotChat", defaults.allowBotChat),
                        allowPlayerInvites = block.optBoolean(
                            "allowPlayerInvites",
                            defaults.allowPlayerInvites,
                        ),
                        groupNearby = block.optBoolean("groupNearby", defaults.groupNearby),
                        wanderWhenIdle = block.optBoolean("wanderWhenIdle", defaults.wanderWhenIdle),
                        enableOffSpecStrategies = block.optBoolean(
                            "enableOffSpecStrategies",
                            defaults.enableOffSpecStrategies,
                        ),
                    )
                }.getOrElse { defaults }
            }
        }

        @Suppress("LongMethod") // one reviewed put-chain literal
        fun toJson(): String = org.json.JSONObject()
            .put("runtimeMode", runtimeMode.name)
            .put("allowLanPlayers", allowLanPlayers)
            .put("lanAddress", lanAddress)
            .put("tunnelAdvertisedAddress", tunnelAdvertisedAddress)
            .put("setupComplete", setupComplete)
            .put("botProfileId", botProfileId)
            .put("botPopulationTarget", botPopulationTarget)
            .put("botSavedPresetId", botSavedPresetId)
            // DESK-124: the launcher-level advanced override block.
            .put("botAdvancedEnabled", botAdvancedEnabled)
            .put(
                "botAdvanced",
                JSONObject()
                    .put("nearbyBotLimit", botAdvanced.nearbyBotLimit)
                    .put("nearbyRadius", botAdvanced.nearbyRadius)
                    .put("loginBatchSize", botAdvanced.loginBatchSize)
                    .put("maintenanceBatchSize", botAdvanced.maintenanceBatchSize)
                    .put("updateIntervalMs", botAdvanced.updateIntervalMs)
                    .put("teleportMinMinutes", botAdvanced.teleportMinMinutes)
                    .put("teleportMaxMinutes", botAdvanced.teleportMaxMinutes)
                    .put("iterationsPerTick", botAdvanced.iterationsPerTick)
                    .put("admissionWorldP99Ms", botAdvanced.admissionWorldP99Ms)
                    .put("syncLevelWithPlayers", botAdvanced.syncLevelWithPlayers)
                    .put("limitCombatActivity", botAdvanced.limitCombatActivity)
                    .put("activeBotPercent", botAdvanced.activeBotPercent)
                    .put("autoDoQuests", botAdvanced.autoDoQuests)
                    .put("allowBotChat", botAdvanced.allowBotChat)
                    .put("allowPlayerInvites", botAdvanced.allowPlayerInvites)
                    .put("groupNearby", botAdvanced.groupNearby)
                    .put("wanderWhenIdle", botAdvanced.wanderWhenIdle)
                    .put("enableOffSpecStrategies", botAdvanced.enableOffSpecStrategies),
            )
            .put("autoLoginOnLaunch", autoLoginOnLaunch)
            .put("autoLoginTimings", org.json.JSONObject(autoLoginTimings.toControlJson()))
            .put("lastActiveGeneration", lastActiveGeneration)
            .put("worldDebugLogs", worldDebugLogs)
            .put("clientDir", clientDir)
            .put("llmEnabled", llmEnabled)
            .put("llmExternalUrl", llmExternalUrl)
            .put("llmExternalModel", llmExternalModel)
            .put("llmExternalApiKey", llmExternalApiKey)
            .put("llmCloudChatter", llmCloudChatter)
            .put("llmBanter", llmBanter)
            .put("llmAmbience", llmAmbience)
            .put("llmMaxNewTokens", llmMaxNewTokens)
            .put("llmGenerationTimeout", llmGenerationTimeout)
            .put("llmExternalMode", llmExternalMode)
            .put("llmModelId", llmModelId)
            // DESK-132: normalized on write exactly like the Android :1077 leg.
            .put("tweaks", org.json.JSONObject(tweaks.toJson()))
            // DESK-13: every write keeps stamping the schema constant (the
            // Android update() contract) or each write would regress the
            // stamp and re-run the defaults migration.
            .put("tweaksSchema", TWEAKS_SCHEMA_VERSION)
            .put("audioMode", audioMode.name)
            .put("databaseProvider", databaseProvider.name)
            // R1-1: the flavor-switcher pick outlives the process (the
            // Android storage twin's key).
            .put("selectedFlavorId", selectedFlavorId)
            .put(
                "nearbyInteractTriggerGuardMs",
                NearbyInteractPolicy.normalizeTriggerGuardMs(nearbyInteractTriggerGuardMs),
            )
            .put("llmPromptPackJson", llmPromptPackJson)
            .put("llmThreads", llmThreads)
            .put("llmOffloadLayers", llmOffloadLayers)
            .put("llmAdvanced", llmAdvanced)
            .put("inputSafeMode", inputSafeMode)
            .put("friendAccessEnabled", friendAccessEnabled)
            // Design-timeframe §4.1: the stage persists through the shared
            // resolution so an impossible value can never be re-persisted.
            .put("timeframeStage", TimeframeStages.resolveStored(timeframeStage))
            // Realm-status browser §6.3: the visibility switch + consent
            // fields persist verbatim (the machine owns the boot semantics).
            .put("realmStatusVisibility", realmStatusVisibility.name)
            .put("realmDescription", realmDescription)
            .put("showCharacterNames", showCharacterNames)
            .put("cloudflaredPath", cloudflaredPath)
            .put("pageRateLimitPerMinute", pageRateLimitPerMinute)
            // W6-N1: the discovery switch + relay set + realm identity.
            .put("nostrDiscoveryEnabled", nostrDiscoveryEnabled)
            .put("nostrRelays", org.json.JSONArray(nostrRelays))
            .put("nostrPrivateKeyHex", nostrPrivateKeyHex)
            .toString()
    }

    /** Mirrors LlmRuntimePolicy's desktop engine defaults (Settings
     *  cannot reach the internal server object); pinned in step by
     *  DesktopLlmEmbeddedConfTest.settingsEngineDefaultsMatchThePolicyDefaults.
     *  Plain object members — a `companion object` is not applicable
     *  inside a standalone object. */
    internal const val DEFAULT_LLM_THREADS = 3
    internal const val DEFAULT_LLM_OFFLOAD_LAYERS = 0

    /** Realm-status browser §6.2: the shipped PUBLIC per-visitor rate limit
     *  (60/min); the settings field's default and its test-hook baseline. */
    const val DEFAULT_PAGE_RATE_LIMIT_PER_MINUTE = 60
}
