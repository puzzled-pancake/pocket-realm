package com.pocketrealm.supervisor

import com.pocketrealm.discovery.iroh.DeviceIdentity
import com.pocketrealm.discovery.iroh.JoinHello
import com.pocketrealm.discovery.iroh.JoinPasscode
import com.pocketrealm.discovery.iroh.JoinPeer
import com.pocketrealm.discovery.iroh.SignupProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A public host's player records and bans (Android and desktop share it):
 * which device and iroh node each account joined from, device and node
 * bans, the account bans issued from the host screens, and the per-device
 * sign-up history behind the sign-up limits. One JSON file under the realm
 * data root, written atomically; a corrupt file reads as empty.
 *
 * Device tokens are per-host hashes the joining app sends ([JoinPeer]);
 * nothing here can be turned back into a hardware id. A modified app can
 * send a fresh token, so a device ban stops ordinary players only.
 */
@Suppress("TooManyFunctions") // one small verb per moderation action
class PublicHostModerationStore(
    private val file: File,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    data class DeviceBan(val token: String, val reason: String, val atMs: Long, val account: String?)
    data class NodeBan(val nodeHex: String, val reason: String, val atMs: Long, val deviceToken: String?)
    data class AccountBan(val account: String, val reason: String, val atMs: Long)
    data class Sighting(
        val account: String,
        val deviceToken: String,
        val nodeHex: String,
        val firstSeenMs: Long,
        val lastSeenMs: Long,
    )
    data class SignupRecord(val account: String, val deviceToken: String, val nodeHex: String, val atMs: Long)

    /** One row of the host's players list. */
    data class PlayerRow(
        val account: String,
        val signedUpAtMs: Long?,
        val lastSeenMs: Long?,
        val devices: Int,
        val online: Boolean,
        val accountBanned: Boolean,
        val deviceBanned: Boolean,
    )

    private data class Data(
        val deviceBans: List<DeviceBan> = emptyList(),
        val nodeBans: List<NodeBan> = emptyList(),
        val accountBans: List<AccountBan> = emptyList(),
        val sightings: List<Sighting> = emptyList(),
        val signups: List<SignupRecord> = emptyList(),
    )

    private val lock = Any()
    private var cache: Data? = null

    /** Live connections (memory only): peer → the account it logged into. */
    private val live = LinkedHashMap<JoinPeer, String?>()

    // ------------------------------------------------------------ admission

    /**
     * The hello answer, in security order: banned devices and nodes first,
     * then (on a password-protected game) the proof with its per-device and
     * per-node wrong-try lockout, then the player limit. [expectedProof] is
     * the host's recomputed proof (null when the game is unlocked or the
     * host's own node id is not known yet — a locked game without it stays
     * locked). [playerLimit]/[onlinePlayers] gate the hello at the world's
     * limit.
     */
    fun admit(
        peer: JoinPeer,
        request: JoinHello.Request,
        locked: Boolean,
        expectedProof: String?,
        playerLimit: Int?,
        onlinePlayers: Int,
    ): SignupProtocol.Reply = synchronized(lock) {
        if (isBannedLocked(peer)) return JoinHello.BANNED_REPLY
        if (locked) {
            // A schema-1 joiner, or one that presented no proof, is asked
            // for the password (never told whether a guess was close).
            if (!request.speaksSchema2 || request.passwordProof.isEmpty() || expectedProof == null) {
                return JoinHello.PASSWORD_REQUIRED_REPLY
            }
            // The lockout is checked before the proof: the window's tries
            // are spent, so even the right password waits it out.
            if (isPasswordLockedOutLocked(peer)) return JoinHello.TRY_LATER_REPLY
            if (!JoinPasscode.matches(request.passwordProof, expectedProof)) {
                recordPasswordMissLocked(peer)
                return JoinHello.PASSWORD_WRONG_REPLY
            }
        }
        if (playerLimit != null && onlinePlayers >= playerLimit) return JoinHello.FULL_REPLY
        JoinHello.WELCOME_REPLY
    }

    /** Wrong password tries, per device token and per node, inside the
     * lockout window (memory only — a host restart clears them, which
     * only shortens a lockout). */
    private val passwordMisses = HashMap<String, ArrayDeque<Long>>()

    private fun isPasswordLockedOutLocked(peer: JoinPeer): Boolean =
        missesInWindowLocked("d:" + peer.deviceToken) >= PASSWORD_TRIES ||
            missesInWindowLocked("n:" + peer.nodeHex) >= PASSWORD_TRIES

    private fun recordPasswordMissLocked(peer: JoinPeer) {
        val now = clockMs()
        addMissLocked("d:" + peer.deviceToken, now)
        addMissLocked("n:" + peer.nodeHex, now)
    }

    private fun addMissLocked(key: String, now: Long) {
        val deque = passwordMisses.getOrPut(key) { ArrayDeque() }
        deque.addLast(now)
        pruneMissesLocked(key, now)
    }

    private fun missesInWindowLocked(key: String): Int {
        pruneMissesLocked(key, clockMs())
        return passwordMisses[key]?.size ?: 0
    }

    private fun pruneMissesLocked(key: String, now: Long) {
        val deque = passwordMisses[key] ?: return
        while (deque.isNotEmpty() && now - deque.first() >= PASSWORD_LOCKOUT_MS) deque.removeFirst()
        if (deque.isEmpty()) passwordMisses.remove(key)
    }

    fun isBanned(peer: JoinPeer): Boolean = synchronized(lock) { isBannedLocked(peer) }

    private fun isBannedLocked(peer: JoinPeer): Boolean {
        val data = load()
        return data.deviceBans.any { it.token == peer.deviceToken } ||
            data.nodeBans.any { it.nodeHex == peer.nodeHex }
    }

    /**
     * The per-device sign-up limits, checked before the host's own
     * open/cap/name gate: a joiner that never said hello (an older app)
     * cannot sign up, a banned device cannot, one device makes at most
     * [MAX_ACCOUNTS_PER_DEVICE] accounts here and waits
     * [MIN_SIGNUP_INTERVAL_MS] between them, and the whole realm takes at
     * most [MAX_SIGNUPS_PER_HOUR]. Null = allowed.
     */
    fun signupGate(peer: JoinPeer?): SignupProtocol.Reply? = synchronized(lock) {
        if (peer == null) {
            return@synchronized SignupProtocol.Reply(
                false, SignupProtocol.REQUEST_INVALID, JoinHello.HELLO_REQUIRED_DETAIL,
            )
        }
        if (isBannedLocked(peer)) {
            return@synchronized SignupProtocol.Reply(false, JoinHello.BANNED, JoinHello.BANNED_REPLY.detail)
        }
        val now = clockMs()
        val mine = load().signups.filter { it.deviceToken == peer.deviceToken || it.nodeHex == peer.nodeHex }
        when {
            mine.size >= MAX_ACCOUNTS_PER_DEVICE -> SignupProtocol.Reply(
                false, SignupProtocol.RATE_LIMITED,
                "This device has already made $MAX_ACCOUNTS_PER_DEVICE accounts on this realm.",
            )
            mine.any { now - it.atMs in 0 until MIN_SIGNUP_INTERVAL_MS } -> SignupProtocol.Reply(
                false, SignupProtocol.RATE_LIMITED,
                "This device made an account a moment ago. Wait a few minutes and try again.",
            )
            load().signups.count { now - it.atMs in 0 until HOUR_MS } >= MAX_SIGNUPS_PER_HOUR ->
                SignupProtocol.Reply(
                    false, SignupProtocol.RATE_LIMITED,
                    "This realm is taking too many new accounts right now. Try again later.",
                )
            else -> null
        }
    }

    fun recordSignup(account: String, peer: JoinPeer) = synchronized(lock) {
        val data = load()
        val signups = (data.signups + SignupRecord(account, peer.deviceToken, peer.nodeHex, clockMs()))
            .takeLast(MAX_RECORDS)
        save(data.copy(signups = signups))
        recordLoginLocked(account, peer)
    }

    // --------------------------------------------------------------- sightings

    /** A login seen on the realm lane: remembers the account's device. */
    fun recordLogin(account: String, peer: JoinPeer) = synchronized(lock) {
        live[peer] = account.uppercase()
        recordLoginLocked(account, peer)
    }

    private fun recordLoginLocked(account: String, peer: JoinPeer) {
        val data = load()
        val now = clockMs()
        val key = account.uppercase()
        val existing = data.sightings.firstOrNull {
            it.account == key && it.deviceToken == peer.deviceToken && it.nodeHex == peer.nodeHex
        }
        val updated = if (existing == null) {
            Sighting(key, peer.deviceToken, peer.nodeHex, now, now)
        } else {
            existing.copy(lastSeenMs = now)
        }
        val others = data.sightings.filterNot { it === existing }
        val sightings = (others + updated).sortedBy { it.lastSeenMs }.takeLast(MAX_RECORDS)
        save(data.copy(sightings = sightings))
    }

    fun markConnected(peer: JoinPeer) = synchronized(lock) {
        if (!live.containsKey(peer)) live[peer] = null
    }

    fun markLeft(peer: JoinPeer) = synchronized(lock) {
        live.remove(peer)
        Unit
    }

    fun clearLive() = synchronized(lock) { live.clear() }

    fun onlineAccounts(): Set<String> = synchronized(lock) { live.values.filterNotNull().toSet() }

    fun connectedPeers(): Int = synchronized(lock) { live.size }

    // -------------------------------------------------------------------- bans

    fun recordAccountBan(account: String, reason: String) = synchronized(lock) {
        val data = load()
        val key = account.uppercase()
        val bans = data.accountBans.filterNot { it.account == key } + AccountBan(key, reason, clockMs())
        save(data.copy(accountBans = bans.takeLast(MAX_RECORDS)))
    }

    fun clearAccountBan(account: String) = synchronized(lock) {
        val data = load()
        save(data.copy(accountBans = data.accountBans.filterNot { it.account == account.uppercase() }))
    }

    /** Bans every device and node this account joined or signed up from;
     * returns the peers banned (empty = none known yet). */
    fun banDevicesOf(account: String, reason: String): List<JoinPeer> = synchronized(lock) {
        val key = account.uppercase()
        val data = load()
        val peers = (
            data.sightings.filter { it.account == key }.map { JoinPeer(it.deviceToken, it.nodeHex) } +
                data.signups.filter { it.account.equals(key, ignoreCase = true) }
                    .map { JoinPeer(it.deviceToken, it.nodeHex) }
            ).distinct()
        if (peers.isEmpty()) return@synchronized emptyList()
        val now = clockMs()
        val deviceBans = data.deviceBans.filterNot { ban -> peers.any { it.deviceToken == ban.token } } +
            peers.map { it.deviceToken }.distinct().map { DeviceBan(it, reason, now, key) }
        val nodeBans = data.nodeBans.filterNot { ban -> peers.any { it.nodeHex == ban.nodeHex } } +
            peers.map { NodeBan(it.nodeHex, reason, now, it.deviceToken) }
        save(data.copy(deviceBans = deviceBans.takeLast(MAX_RECORDS), nodeBans = nodeBans.takeLast(MAX_RECORDS)))
        peers
    }

    /** Lifts one device ban (and the node bans recorded with it). */
    fun unbanDevice(token: String): Boolean = synchronized(lock) {
        val data = load()
        val deviceBans = data.deviceBans.filterNot { it.token == token }
        if (deviceBans.size == data.deviceBans.size) return@synchronized false
        save(data.copy(deviceBans = deviceBans, nodeBans = data.nodeBans.filterNot { it.deviceToken == token }))
        true
    }

    /** Lifts every device ban recorded for this account. */
    fun unbanDevicesOf(account: String): Int = synchronized(lock) {
        val key = account.uppercase()
        val data = load()
        val lifted = data.deviceBans.filter { it.account == key }.map { it.token }.toSet()
        if (lifted.isEmpty()) return@synchronized 0
        save(
            data.copy(
                deviceBans = data.deviceBans.filterNot { it.token in lifted },
                nodeBans = data.nodeBans.filterNot { it.deviceToken in lifted },
            ),
        )
        lifted.size
    }

    fun deviceBans(): List<DeviceBan> = synchronized(lock) { load().deviceBans }

    fun accountBans(): List<AccountBan> = synchronized(lock) { load().accountBans }

    // ------------------------------------------------------------- players list

    /**
     * Every account the host should see: the signed-up ones plus any
     * account seen logging in over the internet, newest activity first.
     */
    fun players(signedUp: List<SignupAccountStore.Entry>): List<PlayerRow> = synchronized(lock) {
        val data = load()
        val online = live.values.filterNotNull().toSet()
        val names = LinkedHashMap<String, String>()
        signedUp.forEach { names[it.username.uppercase()] = it.username }
        data.sightings.forEach { names.putIfAbsent(it.account, it.account) }
        names.map { (key, display) ->
            val seen = data.sightings.filter { it.account == key }
            val devices = (seen.map { it.deviceToken } +
                data.signups.filter { it.account.uppercase() == key }.map { it.deviceToken }).toSet()
            PlayerRow(
                account = display,
                signedUpAtMs = signedUp.firstOrNull { it.username.uppercase() == key }?.createdAtMs,
                lastSeenMs = seen.maxOfOrNull { it.lastSeenMs },
                devices = devices.size,
                online = key in online,
                accountBanned = data.accountBans.any { it.account == key },
                deviceBanned = devices.isNotEmpty() && devices.all { d -> data.deviceBans.any { it.token == d } },
            )
        }.sortedWith(
            compareByDescending<PlayerRow> { it.online }
                .thenByDescending { maxOf(it.lastSeenMs ?: 0L, it.signedUpAtMs ?: 0L) },
        )
    }

    /** The players list and device bans as one JSON reply (the Android
     * host screen reads it across the process boundary). */
    fun playersJson(signedUp: List<SignupAccountStore.Entry>): String {
        val rows = JSONArray()
        players(signedUp).forEach { row ->
            rows.put(
                JSONObject()
                    .put("account", row.account)
                    .put("signedUpAtMs", row.signedUpAtMs ?: 0L)
                    .put("lastSeenMs", row.lastSeenMs ?: 0L)
                    .put("devices", row.devices)
                    .put("online", row.online)
                    .put("accountBanned", row.accountBanned)
                    .put("deviceBanned", row.deviceBanned),
            )
        }
        val bans = JSONArray()
        deviceBans().forEach { ban ->
            bans.put(
                JSONObject()
                    .put("token", ban.token)
                    .put("label", DeviceIdentity.shortLabel(ban.token))
                    .put("account", ban.account ?: "")
                    .put("reason", ban.reason)
                    .put("atMs", ban.atMs),
            )
        }
        return JSONObject().put("ok", true).put("players", rows).put("deviceBans", bans).toString()
    }

    // ------------------------------------------------------------- persistence

    private fun load(): Data = cache ?: read().also { cache = it }

    private fun read(): Data = runCatching {
        if (!file.isFile) return Data()
        val obj = JSONObject(file.readText(Charsets.UTF_8))
        if (obj.optInt("schema") != SCHEMA) return Data()
        Data(
            deviceBans = obj.rows("deviceBans") { row ->
                row.optString("token").takeIf { DeviceIdentity.isToken(it) }?.let {
                    DeviceBan(it, row.optString("reason"), row.optLong("atMs"),
                        row.optString("account").ifEmpty { null })
                }
            },
            nodeBans = obj.rows("nodeBans") { row ->
                row.optString("node").takeIf { DeviceIdentity.isToken(it) }?.let {
                    NodeBan(it, row.optString("reason"), row.optLong("atMs"),
                        row.optString("device").ifEmpty { null })
                }
            },
            accountBans = obj.rows("accountBans") { row ->
                row.optString("account").ifEmpty { null }?.let {
                    AccountBan(it, row.optString("reason"), row.optLong("atMs"))
                }
            },
            sightings = obj.rows("sightings") { row ->
                val device = row.optString("device")
                val node = row.optString("node")
                val account = row.optString("account")
                if (account.isEmpty() || !DeviceIdentity.isToken(device) || !DeviceIdentity.isToken(node)) {
                    null
                } else {
                    Sighting(account, device, node, row.optLong("firstMs"), row.optLong("lastMs"))
                }
            },
            signups = obj.rows("signups") { row ->
                val device = row.optString("device")
                val node = row.optString("node")
                val account = row.optString("account")
                if (account.isEmpty() || !DeviceIdentity.isToken(device) || !DeviceIdentity.isToken(node)) {
                    null
                } else {
                    SignupRecord(account, device, node, row.optLong("atMs"))
                }
            },
        )
    }.getOrDefault(Data())

    private fun <T> JSONObject.rows(key: String, map: (JSONObject) -> T?): List<T> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(map) }
    }

    private fun save(data: Data) {
        cache = data
        val obj = JSONObject().put("schema", SCHEMA)
            .put("deviceBans", JSONArray().apply {
                data.deviceBans.forEach {
                    put(JSONObject().put("token", it.token).put("reason", it.reason).put("atMs", it.atMs)
                        .put("account", it.account ?: ""))
                }
            })
            .put("nodeBans", JSONArray().apply {
                data.nodeBans.forEach {
                    put(JSONObject().put("node", it.nodeHex).put("reason", it.reason).put("atMs", it.atMs)
                        .put("device", it.deviceToken ?: ""))
                }
            })
            .put("accountBans", JSONArray().apply {
                data.accountBans.forEach {
                    put(JSONObject().put("account", it.account).put("reason", it.reason).put("atMs", it.atMs))
                }
            })
            .put("sightings", JSONArray().apply {
                data.sightings.forEach {
                    put(JSONObject().put("account", it.account).put("device", it.deviceToken)
                        .put("node", it.nodeHex).put("firstMs", it.firstSeenMs).put("lastMs", it.lastSeenMs))
                }
            })
            .put("signups", JSONArray().apply {
                data.signups.forEach {
                    put(JSONObject().put("account", it.account).put("device", it.deviceToken)
                        .put("node", it.nodeHex).put("atMs", it.atMs))
                }
            })
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, ".${file.name}.tmp")
            temp.writeText(obj.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "could not write ${file.name}" }
            }
        }
    }

    companion object {
        const val SCHEMA: Int = 1
        const val FILE_NAME: String = "public-host-moderation.json"

        /** Accounts one device may create on one realm, ever. */
        const val MAX_ACCOUNTS_PER_DEVICE: Int = 3

        /** The wait between two sign-ups from the same device. */
        const val MIN_SIGNUP_INTERVAL_MS: Long = 10L * 60L * 1_000L

        /** Sign-ups the whole realm takes per hour. */
        const val MAX_SIGNUPS_PER_HOUR: Int = 30

        /** Wrong join-password tries before the device (and node) is
         * locked out, and how long the lockout lasts. */
        const val PASSWORD_TRIES: Int = 5
        const val PASSWORD_LOCKOUT_MS: Long = 10L * 60L * 1_000L

        /** Each record list keeps at most this many rows (oldest dropped). */
        const val MAX_RECORDS: Int = 5_000

        private const val HOUR_MS = 60L * 60L * 1_000L
    }
}

/**
 * The host screens' ban verbs over [PublicHostModerationStore] and the
 * realm's own account bans (the world console's `ban account`, which
 * also kicks a player who is online). Device bans also close the
 * device's live tunnel connections, so a banned player drops at once.
 */
class PublicHostModerator(
    val store: PublicHostModerationStore,
    private val banAccount: suspend (username: String, reason: String) -> ModerationResult,
    private val unbanAccount: suspend (username: String) -> ModerationResult,
    private val disconnect: (deviceToken: String?, nodeHex: String?) -> Unit,
) {

    enum class Action { BAN_ACCOUNT, UNBAN_ACCOUNT, BAN_DEVICE, UNBAN_DEVICE, BAN_BOTH }

    @Suppress("CyclomaticComplexMethod") // one branch per host verb
    suspend fun act(action: Action, target: String, reason: String = DEFAULT_REASON): ModerationResult {
        val why = reason.trim().ifEmpty { DEFAULT_REASON }.take(MAX_REASON_CHARS)
        val name = target.trim()
        return when (action) {
            Action.BAN_ACCOUNT -> banAccountOnly(name, why)
            Action.UNBAN_ACCOUNT -> {
                val result = unbanAccount(name)
                if (result.ok) store.clearAccountBan(name)
                if (result.ok) ModerationResult(true, "$name can log in again.") else notRunning(result)
            }
            Action.BAN_DEVICE -> banDevices(name, why)
            Action.UNBAN_DEVICE -> {
                val lifted = if (DeviceIdentity.isToken(name)) {
                    if (store.unbanDevice(name)) 1 else 0
                } else {
                    store.unbanDevicesOf(name)
                }
                if (lifted > 0) {
                    ModerationResult(true, "Device ban lifted.")
                } else {
                    ModerationResult(false, "No device ban to lift.")
                }
            }
            Action.BAN_BOTH -> {
                val devices = banDevices(name, why)
                val account = banAccountOnly(name, why)
                ModerationResult(
                    devices.ok || account.ok,
                    listOf(account.detail, devices.detail).joinToString(" "),
                )
            }
        }
    }

    private suspend fun banAccountOnly(name: String, reason: String): ModerationResult {
        val result = banAccount(name, reason)
        if (result.ok) store.recordAccountBan(name, reason)
        return if (result.ok) ModerationResult(true, "$name is banned (kicked if online).") else notRunning(result)
    }

    private fun banDevices(name: String, reason: String): ModerationResult {
        val peers = store.banDevicesOf(name, reason)
        if (peers.isEmpty()) {
            return ModerationResult(false, "No device is known for $name yet (it has not joined over the internet).")
        }
        peers.forEach { disconnect(it.deviceToken, it.nodeHex) }
        val count = peers.map { it.deviceToken }.distinct().size
        return ModerationResult(true, "Banned $count device(s) used by $name.")
    }

    private fun notRunning(result: ModerationResult): ModerationResult =
        if (result.detail.contains("not-ready") || result.detail.contains("not ready")) {
            ModerationResult(false, "Start the realm first, then try again.")
        } else {
            ModerationResult(false, result.detail.ifBlank { "The realm did not accept the change." })
        }

    companion object {
        const val DEFAULT_REASON: String = "Banned by the host"
        const val MAX_REASON_CHARS: Int = 120

        /** The permanent duration in the world console's `ban account` shape. */
        const val PERMANENT: String = "0"

        /** Said on every host ban screen. */
        const val DEVICE_BAN_LIMITS_COPY: String =
            "Device bans use an id the joining app sends. A modified app could fake it, " +
                "so a determined player can get around a device ban."
    }
}

/** The host screens' plain copy for one player row (both platforms). */
object PublicPlayersCopy {

    /** "Online now · 2 devices · signed up here" / "Last seen 3h ago · 1 device". */
    fun statusLine(row: PublicHostModerationStore.PlayerRow, nowMs: Long): String {
        val presence = when {
            row.online -> "Online now"
            row.lastSeenMs != null -> "Last seen ${ago(nowMs - row.lastSeenMs)}"
            else -> "Not seen online yet"
        }
        val devices = when (row.devices) {
            0 -> null
            1 -> "1 device"
            else -> "${row.devices} devices"
        }
        val signup = if (row.signedUpAtMs != null) "signed up here" else null
        val bans = listOfNotNull(
            if (row.accountBanned) "account banned" else null,
            if (row.deviceBanned) "device banned" else null,
        ).joinToString(", ").ifEmpty { null }
        return listOfNotNull(presence, devices, signup, bans).joinToString(" · ")
    }

    /** "under a minute ago", "12m ago", "3h ago", "2d ago". */
    fun ago(elapsedMs: Long): String {
        val minutes = elapsedMs.coerceAtLeast(0L) / MILLIS_PER_MINUTE
        return when {
            minutes < 1 -> "under a minute ago"
            minutes < MINUTES_PER_HOUR -> "${minutes}m ago"
            minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR}h ago"
            else -> "${minutes / MINUTES_PER_DAY}d ago"
        }
    }

    /** One row of [PublicHostModerationStore.playersJson] back to a row. */
    fun rowFromJson(obj: JSONObject): PublicHostModerationStore.PlayerRow =
        PublicHostModerationStore.PlayerRow(
            account = obj.optString("account"),
            signedUpAtMs = obj.optLong("signedUpAtMs").takeIf { it > 0L },
            lastSeenMs = obj.optLong("lastSeenMs").takeIf { it > 0L },
            devices = obj.optInt("devices"),
            online = obj.optBoolean("online"),
            accountBanned = obj.optBoolean("accountBanned"),
            deviceBanned = obj.optBoolean("deviceBanned"),
        )

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val MINUTES_PER_DAY = 24L * 60L
}
