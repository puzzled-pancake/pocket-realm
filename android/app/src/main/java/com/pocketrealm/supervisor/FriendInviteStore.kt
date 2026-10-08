package com.pocketrealm.supervisor

import com.pocketrealm.server.FriendProvisionProtocol
import com.pocketrealm.server.ServerRuntimeContract
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * The host side of remote account creation (design-remote-accounts §5): one
 * row per invite in the host app's own store — a dedicated durable file
 * under realm/friend-access, never the settings blob (§5; the settings file
 * already carries mode fields and gets shared around casually).
 *
 * State machine (§5):
 *
 * ```
 * OPEN → SUBMITTED → APPROVED → PROVISIONED
 *          │            └→ REJECTED
 *          └→ EXPIRED / BURNED (bad token, wrong key, cap hit)
 * ```
 *
 * SRP6-safe storage (§2/§12 — the feature's storage rule):
 *  - the raw token is NEVER persisted: the row carries `token_hash`
 *    (SHA-256) only, so a copied file tree grants nothing;
 *  - the submitted (s, v) pair is NEVER persisted either — a verifier is
 *    password-equivalent for offline guessing (§2's caveat), so it lives
 *    only in this process's memory, keyed by invite id, until the host
 *    decides. An app restart mid-approval loses the verifier; the row can
 *    then only be rejected or left to expire. The persisted JSON therefore
 *    contains no secret material at all;
 *  - audit rows carry invite id, action, username, accountId, source IP,
 *    timestamp, outcome — never the token (hash only), never s/v.
 *
 * Defaults (§5): single-use, a 15-minute decision window opening at the
 * first submission (the retention bound on the held verifier), an optional
 * free-text note, and a hard cap of 8 OPEN invites — generation is refused
 * beyond the cap.
 */
// TooManyFunctions/LongParameterList: the store is the §5 state machine's
// whole surface — host actions, the listener's submit/poll verbs, reads,
// rate rows, and the audit writer — one verb per design rule; the submit
// signature is the protocol payload, one parameter per field.
@Suppress("TooManyFunctions")
class FriendInviteStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private val storeFile = File(directory, STORE_FILE_NAME)
    private val auditFile = File(directory, AUDIT_FILE_NAME)

    /** In-memory only: invite id → the submitted verifier (never persisted). */
    private val pendingVerifiers = HashMap<String, SubmittedVerifier>()

    enum class State { OPEN, SUBMITTED, APPROVED, PROVISIONED, REJECTED, EXPIRED, BURNED }

    /** The submitted identity+verifier, held in memory for the approval leg. */
    data class SubmittedVerifier(
        val username: String,
        val sHex: String,
        val vHex: String,
        val sourceIp: String,
    )

    /** What the host UI renders. No token, no verifier — projections are safe. */
    data class InviteRow(
        val inviteId: String,
        val state: State,
        val note: String,
        val createdAtMs: Long,
        val submittedAtMs: Long?,
        val expiresAtMs: Long?,
        val username: String?,
        val sourceIp: String?,
        val accountId: Long?,
    )

    sealed class CreateResult {
        data class Created(val inviteId: String, val tokenBase64Url: String) : CreateResult()
        data class Refused(val reason: String) : CreateResult()
    }

    /** The submit verdict the LISTENER translates; every refusal is the same
     * shape so the listener can answer the identical blob (§5). */
    sealed class SubmitResult {
        data class Accepted(val inviteId: String, val username: String) : SubmitResult()

        /** Unknown id, bad token, expired window, burned token, second use — one shape. */
        object Refused : SubmitResult()
    }

    // ------------------------------------------------------------------
    // Host actions
    // ------------------------------------------------------------------

    /** Generates a fresh invite. The returned token is the ONLY copy — the
     * host UI shows it once; the store keeps its hash only. */
    fun create(note: String): CreateResult = synchronized(lock) {
        val persisted = readLocked()
        expireDueLocked(persisted)
        if (persisted.invites.count { it.state == State.OPEN } >= MAX_OPEN_INVITES) {
            audit(ACTION_CREATE_REFUSED, inviteId = null, username = null, accountId = null,
                sourceIp = null, outcome = "open-invite-cap")
            writeLocked(persisted)
            return@synchronized CreateResult.Refused("Open invites reached the cap of $MAX_OPEN_INVITES")
        }
        val inviteId = newInviteId()
        val tokenBytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val tokenBase64Url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        persisted.invites += InviteRecord(
            inviteId = inviteId,
            state = State.OPEN,
            tokenHash = sha256Hex(tokenBase64Url),
            note = note.take(MAX_NOTE_CHARS),
            createdAtMs = clock(),
        )
        audit(ACTION_CREATED, inviteId = inviteId, username = null, accountId = null,
            sourceIp = null, outcome = "open")
        writeLocked(persisted)
        CreateResult.Created(inviteId, tokenBase64Url)
    }

    /** Revokes an OPEN invite (the host UI's Revoke): the token dies, the
     * row burns. Submitted rows are host decisions (approve/reject), not
     * revokes. */
    fun revoke(inviteId: String): Boolean = synchronized(lock) {
        val persisted = readLocked()
        expireDueLocked(persisted)
        val index = persisted.invites.indexOfFirst { it.inviteId == inviteId }
        if (index < 0 || persisted.invites[index].state != State.OPEN) return@synchronized false
        val invite = persisted.invites[index]
        persisted.invites[index] = invite.copy(state = State.BURNED)
        audit(ACTION_REVOKED, inviteId = inviteId, username = null, accountId = null,
            sourceIp = null, outcome = "burned")
        writeLocked(persisted)
        true
    }

    // ------------------------------------------------------------------
    // Friend submissions (the listener calls this)
    // ------------------------------------------------------------------

    /**
     * One submission attempt. Constant-time hash-matches the token against
     * OPEN rows only (single-use: any second submit is a refusal), opens the
     * 15-minute decision window on success, and holds the verifier in memory
     * only. The caller answers REFUSED_RESPONSE on [SubmitResult.Refused].
     */
    // ReturnCount: the early refusals are the protocol's reject legs.
    @Suppress("LongParameterList", "ReturnCount") // the request payload is exactly these six fields (§3)
    fun submit(
        inviteId: String,
        token: String,
        username: String,
        sHex: String,
        vHex: String,
        sourceIp: String,
    ): SubmitResult {
        val identity = runCatching {
            ServerRuntimeContract.requireAccountToken("username", username)
        }.isSuccess
        if (!identity) return SubmitResult.Refused
        if (!FriendProvisionProtocol.isSrpHex(sHex) || !FriendProvisionProtocol.isSrpHex(vHex)) {
            return SubmitResult.Refused
        }
        return synchronized(lock) {
            val persisted = readLocked()
            expireDueLocked(persisted)
            val invite = persisted.invites.firstOrNull { it.inviteId == inviteId }
            val tokenMatches = invite != null && MessageDigest.isEqual(
                invite.tokenHash.toByteArray(Charsets.UTF_8),
                sha256Hex(token).toByteArray(Charsets.UTF_8),
            )
            if (invite == null || invite.state != State.OPEN || !tokenMatches) {
                // A valid token against a decided row is the friend's
                // read-only poll (the listener routes it to poll()); only a
                // genuinely bad submission is audit noise.
                if (!tokenMatches) {
                    audit(ACTION_SUBMIT_REFUSED, inviteId = inviteId, username = username,
                        accountId = null, sourceIp = sourceIp, outcome = "refused")
                    writeLocked(persisted)
                }
                return@synchronized SubmitResult.Refused
            }
            persisted.invites[persisted.invites.indexOf(invite)] = invite.copy(
                state = State.SUBMITTED,
                submittedAtMs = clock(),
                expiresAtMs = clock() + SUBMISSION_WINDOW_MS,
                username = username,
                sourceIp = sourceIp.take(MAX_IP_CHARS),
            )
            pendingVerifiers[inviteId] = SubmittedVerifier(
                username, sHex, vHex, sourceIp.take(MAX_IP_CHARS),
            )
            audit(ACTION_SUBMITTED, inviteId = inviteId, username = username,
                accountId = null, sourceIp = sourceIp, outcome = "submitted")
            writeLocked(persisted)
            SubmitResult.Accepted(inviteId, username)
        }
    }

    // ------------------------------------------------------------------
    // Friend polls (the listener calls this)
    // ------------------------------------------------------------------

    /**
     * The read-only re-request that lets the friend learn the decision (§5:
     * "Only the PROVISIONED response tells the friend their accountId").
     * The same token, re-sent: while the row is SUBMITTED the answer is
     * still just "submitted"; once the host approves, the ONE response
     * carrying the accountId goes out and the token finally burns. Any
     * other state (REJECTED/EXPIRED/BURNED/unknown/bad token) is the same
     * indistinct refusal — a poll must not leak the difference.
     */
    sealed class PollResult {
        data class Provisioned(val accountId: Long) : PollResult()
        object Pending : PollResult()
        object Refused : PollResult()
    }

    fun poll(inviteId: String, token: String): PollResult = synchronized(lock) {
        val persisted = readLocked()
        expireDueLocked(persisted)
        val invite = persisted.invites.firstOrNull { it.inviteId == inviteId }
        if (invite == null ||
            !MessageDigest.isEqual(
                invite.tokenHash.toByteArray(Charsets.UTF_8),
                sha256Hex(token).toByteArray(Charsets.UTF_8),
            )
        ) {
            return@synchronized PollResult.Refused
        }
        when (invite.state) {
            State.SUBMITTED -> PollResult.Pending
            State.APPROVED -> PollResult.Pending
            State.PROVISIONED -> {
                // The one reveal, then the token burns (single-use holds).
                val index = persisted.invites.indexOf(invite)
                persisted.invites[index] = invite.copy(tokenHash = "")
                audit(ACTION_TOKEN_BURNED, inviteId = inviteId, username = invite.username,
                    accountId = invite.accountId, sourceIp = invite.sourceIp, outcome = "revealed")
                writeLocked(persisted)
                PollResult.Provisioned(invite.accountId ?: 0L)
            }
            else -> PollResult.Refused
        }
    }

    // ------------------------------------------------------------------
    // Host decisions
    // ------------------------------------------------------------------

    /** Approve leg 1: SUBMITTED → APPROVED and hands back the held verifier
     * for the relay op. Null when the row is not inside its decision window. */
    fun markApproved(inviteId: String): SubmittedVerifier? = synchronized(lock) {
        val persisted = readLocked()
        expireDueLocked(persisted)
        val index = persisted.invites.indexOfFirst { it.inviteId == inviteId }
        if (index < 0) return@synchronized null
        val invite = persisted.invites[index]
        if (invite.state != State.SUBMITTED) return@synchronized null
        val verifier = pendingVerifiers[inviteId] ?: return@synchronized null
        persisted.invites[index] = invite.copy(state = State.APPROVED)
        audit(ACTION_APPROVED, inviteId = inviteId, username = invite.username,
            accountId = null, sourceIp = invite.sourceIp, outcome = "approved")
        writeLocked(persisted)
        verifier
    }

    /** Approve leg 2: the relay op answered — PROVISIONED with the new
     * accountId; the held verifier is dropped. */
    fun markProvisioned(inviteId: String, accountId: Long) = synchronized(lock) {
        val persisted = readLocked()
        val index = persisted.invites.indexOfFirst { it.inviteId == inviteId }
        if (index < 0) return@synchronized
        val invite = persisted.invites[index]
        persisted.invites[index] = invite.copy(state = State.PROVISIONED, accountId = accountId)
        audit(ACTION_PROVISIONED, inviteId = inviteId, username = invite.username,
            accountId = accountId, sourceIp = invite.sourceIp, outcome = "provisioned")
        pendingVerifiers.remove(inviteId)
        writeLocked(persisted)
    }

    /** Reject: SUBMITTED → REJECTED; the held verifier is discarded
     * immediately (§5 — the friend's device drops its s/v on REJECTED, and
     * so does the host). */
    fun reject(inviteId: String): Boolean = synchronized(lock) {
        val persisted = readLocked()
        val index = persisted.invites.indexOfFirst { it.inviteId == inviteId }
        if (index < 0) return@synchronized false
        val invite = persisted.invites[index]
        if (invite.state != State.SUBMITTED) return@synchronized false
        persisted.invites[index] = invite.copy(state = State.REJECTED)
        audit(ACTION_REJECTED, inviteId = inviteId, username = invite.username,
            accountId = null, sourceIp = invite.sourceIp, outcome = "rejected")
        pendingVerifiers.remove(inviteId)
        writeLocked(persisted)
        true
    }

    /** Approve-lane failure: the row returns to SUBMITTED so the host can
     * retry (a host-side relay error must not punish the friend). */
    fun approvalFailed(inviteId: String, outcome: String) = synchronized(lock) {
        val persisted = readLocked()
        val index = persisted.invites.indexOfFirst { it.inviteId == inviteId }
        if (index < 0) return@synchronized
        val invite = persisted.invites[index]
        if (invite.state != State.APPROVED) return@synchronized
        persisted.invites[index] = invite.copy(state = State.SUBMITTED)
        audit(ACTION_APPROVAL_FAILED, inviteId = inviteId, username = invite.username,
            accountId = null, sourceIp = invite.sourceIp, outcome = outcome.take(MAX_OUTCOME_CHARS))
        writeLocked(persisted)
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    fun rows(): List<InviteRow> = synchronized(lock) {
        val persisted = readLocked()
        expireDueLocked(persisted)
        persisted.invites.map { it.toRow() }.sortedByDescending { it.createdAtMs }
    }

    fun pending(): List<InviteRow> = rows().filter { it.state == State.SUBMITTED }

    fun openCount(): Int = rows().count { it.state == State.OPEN }

    /**
     * §12: audit rows for the moderation verbs (ban/unban/gm) — the same
     * row shape as the invite lifecycle (action, username, accountId,
     * timestamp, outcome; no secrets), keyed by username instead of invite
     * id. The controller calls this after every moderation verb so the
     * host audit store answers "who banned whom, when, with what result".
     */
    fun auditModeration(action: String, username: String?, accountId: Long?, outcome: String) = synchronized(lock) {
        audit(
            action,
            inviteId = null,
            username = username,
            accountId = accountId,
            sourceIp = null,
            outcome = outcome.take(MAX_OUTCOME_CHARS),
        )
    }

    /** The audit trail (newest last). Each row: invite id, action, username,
     * accountId, source IP, timestamp, outcome — no secrets (§12). */
    fun auditTail(limit: Int = DEFAULT_AUDIT_TAIL): List<JSONObject> {
        if (!auditFile.isFile) return emptyList()
        val lines = runCatching { auditFile.readLines() }.getOrDefault(emptyList())
        return lines.takeLast(limit).mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
    }

    // ------------------------------------------------------------------
    // Rate-limit rows (§6 layer 1: "in-memory + persisted row") — the
    // listener owns the live counters; the store persists the lockout rows
    // so an app restart does not amnesty a locked source.
    // ------------------------------------------------------------------

    data class RateRow(val ip: String, val failures: Int, val lockedUntilMs: Long) {
        val locked: Boolean get() = lockedUntilMs > 0
    }

    fun rateRows(): List<RateRow> = synchronized(lock) { readLocked().rate.map { it.toRow() } }

    fun recordRateFailure(ip: String, failures: Int, lockedUntilMs: Long) = synchronized(lock) {
        val persisted = readLocked()
        val record = RateRecord(ip.take(MAX_IP_CHARS), failures, lockedUntilMs)
        val index = persisted.rate.indexOfFirst { it.ip == record.ip }
        if (index >= 0) persisted.rate[index] = record else persisted.rate += record
        // §6 layer 1, bounded persistence: a bad-token spray from many
        // unique source IPs must not grow the store (and its whole-file
        // rewrite) without bound — the row count is capped. Over the cap,
        // insertion-OLDEST UNLOCKED rows are evicted first: a live lockout
        // row is the one thing this persistence exists for (no amnesty
        // across an app restart), so locked rows are evicted last, and
        // only when every row is locked past the cap (the oldest rows'
        // lockouts lapse first anyway).
        if (persisted.rate.size > MAX_RATE_ROWS) {
            var excess = persisted.rate.size - MAX_RATE_ROWS
            val kept = ArrayList<RateRecord>(MAX_RATE_ROWS)
            for (record0 in persisted.rate) {
                if (excess > 0 && record0.lockedUntilMs <= 0L) {
                    excess -= 1
                    continue
                }
                kept += record0
            }
            persisted.rate.clear()
            persisted.rate.addAll(if (excess > 0) kept.drop(excess) else kept)
        }
        writeLocked(persisted)
    }

    fun lockedUntilMs(ip: String): Long = synchronized(lock) {
        readLocked().rate.firstOrNull { it.ip == ip }?.lockedUntilMs ?: 0L
    }

    // ------------------------------------------------------------------
    // Persistence — the JSON carries NO secrets: state, hash, metadata.
    // ------------------------------------------------------------------

    private data class InviteRecord(
        val inviteId: String,
        val state: State,
        val tokenHash: String,
        val note: String,
        val createdAtMs: Long,
        val submittedAtMs: Long? = null,
        val expiresAtMs: Long? = null,
        val username: String? = null,
        val sourceIp: String? = null,
        val accountId: Long? = null,
    ) {
        fun toRow() = InviteRow(
            inviteId, state, note, createdAtMs, submittedAtMs, expiresAtMs, username, sourceIp, accountId,
        )
    }

    private data class RateRecord(val ip: String, val failures: Int, val lockedUntilMs: Long) {
        fun toRow() = RateRow(ip, failures, lockedUntilMs)
    }

    private class Persisted {
        val invites = ArrayList<InviteRecord>()
        val rate = ArrayList<RateRecord>()
    }

    /** Lazily flips submitted/approved rows whose decision window lapsed to
     * EXPIRED and drops their held verifiers. */
    private fun expireDueLocked(persisted: Persisted) {
        val now = clock()
        for (index in persisted.invites.indices) {
            val invite = persisted.invites[index]
            val due = invite.expiresAtMs != null && invite.expiresAtMs <= now &&
                (invite.state == State.SUBMITTED || invite.state == State.APPROVED)
            if (due) {
                persisted.invites[index] = invite.copy(state = State.EXPIRED)
                audit(ACTION_EXPIRED, inviteId = invite.inviteId, username = invite.username,
                    accountId = null, sourceIp = invite.sourceIp, outcome = "expired")
                pendingVerifiers.remove(invite.inviteId)
            }
        }
    }

    // ReturnCount/LoopWithTooManyJumpStatements: the lenient per-field
    // decode returns early on every degrade leg (absent file, unparsable
    // JSON, wrong schema) and skips malformed rows — the stores'
    // degrade-posture shape, not control-flow debt.
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun readLocked(): Persisted {
        val persisted = Persisted()
        if (!storeFile.isFile) return persisted
        val value = runCatching { JSONObject(storeFile.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return persisted
        if (value.optInt("schema") != STORE_SCHEMA) return persisted
        value.optJSONArray("invites")?.let { array ->
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val state = runCatching { State.valueOf(row.optString("state")) }.getOrNull() ?: continue
                persisted.invites += InviteRecord(
                    inviteId = row.optString("inviteId"),
                    state = state,
                    tokenHash = row.optString("tokenHash"),
                    note = row.optString("note"),
                    createdAtMs = row.optLong("createdAtMs"),
                    submittedAtMs = row.optLongOrNull("submittedAtMs"),
                    expiresAtMs = row.optLongOrNull("expiresAtMs"),
                    username = row.optStringOrNull("username"),
                    sourceIp = row.optStringOrNull("sourceIp"),
                    accountId = row.optLongOrNull("accountId"),
                )
            }
        }
        value.optJSONArray("rate")?.let { array ->
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                persisted.rate += RateRecord(
                    ip = row.optString("ip"),
                    failures = row.optInt("failures"),
                    lockedUntilMs = row.optLong("lockedUntilMs"),
                )
            }
        }
        return persisted
    }

    private fun writeLocked(persisted: Persisted) {
        directory.mkdirs()
        val invites = JSONArray()
        persisted.invites.forEach { invite ->
            val row = JSONObject()
                .put("inviteId", invite.inviteId)
                .put("state", invite.state.name)
                .put("tokenHash", invite.tokenHash)
                .put("note", invite.note)
                .put("createdAtMs", invite.createdAtMs)
            invite.submittedAtMs?.let { row.put("submittedAtMs", it) }
            invite.expiresAtMs?.let { row.put("expiresAtMs", it) }
            invite.username?.let { row.put("username", it) }
            invite.sourceIp?.let { row.put("sourceIp", it) }
            invite.accountId?.let { row.put("accountId", it) }
            invites.put(row)
        }
        val rate = JSONArray()
        persisted.rate.forEach { row ->
            rate.put(
                JSONObject()
                    .put("ip", row.ip)
                    .put("failures", row.failures)
                    .put("lockedUntilMs", row.lockedUntilMs),
            )
        }
        val encoded = JSONObject()
            .put("schema", STORE_SCHEMA)
            .put("invites", invites)
            .put("rate", rate)
            .toString().toByteArray(Charsets.UTF_8)
        // nanoTime suffix, not the Java-9 process-handle pid lookup: this
        // file is shared with the Android build (minSdk 26), whose runtime
        // lacks that API until API 33 — a pid suffix would crash the first
        // write with NoSuchMethodError on every API<=32 device. Writes are
        // serialized by `lock`, so nanoTime is unique per temp.
        val temp = File(directory, ".$STORE_FILE_NAME.${System.nanoTime()}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(encoded)
            stream.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                temp.toPath(), storeFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(), storeFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    @Suppress("LongParameterList") // one parameter per audit column (§12)
    private fun audit(
        action: String,
        inviteId: String?,
        username: String?,
        accountId: Long?,
        sourceIp: String?,
        outcome: String,
    ) {
        val row = JSONObject()
            .put("at", clock())
            .put("action", action)
            .put("outcome", outcome.take(MAX_OUTCOME_CHARS))
        inviteId?.let { row.put("inviteId", it) }
        username?.let { row.put("username", it) }
        accountId?.let { row.put("accountId", it) }
        sourceIp?.takeIf { it.isNotEmpty() }?.let { row.put("sourceIp", it) }
        runCatching {
            auditFile.parentFile?.mkdirs()
            auditFile.appendText(row.toString() + "\n", Charsets.UTF_8)
        }
    }

    // LoopWithTooManyJumpStatements/ReturnCount: the collision-checked id
    // generator is a single retry loop by design; the file readers are the
    // lenient per-field decode the stores' degrade posture requires.
    @Suppress("LoopWithTooManyJumpStatements", "ReturnCount", "MagicNumber") // base32 field constants
    private fun newInviteId(): String {
        // Short, human-readable id: FRD- + 6 Crockford-base32 chars (no
        // I/L/O/U confusables), collision-checked against the live rows.
        while (true) {
            val bytes = ByteArray(INVITE_ID_RANDOM_BYTES).also(random::nextBytes)
            var value = 0L
            bytes.forEach { value = (value shl BYTE_BITS) or (it.toLong() and BYTE_MASK) }
            var id = ""
            repeat(INVITE_ID_CHARS) {
                id = CROCKFORD[(value and INVITE_ID_CHAR_MASK).toInt()] + id
                value = value ushr INVITE_ID_CHAR_BITS
            }
            val candidate = "FRD-$id"
            if (readLocked().invites.none { it.inviteId == candidate }) return candidate
        }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

    companion object {
        internal const val STORE_SCHEMA = 1
        internal const val STORE_FILE_NAME = "friend-access-invites.json"
        internal const val AUDIT_FILE_NAME = "friend-access-audit.jsonl"
        internal const val MAX_OPEN_INVITES = 8
        internal const val SUBMISSION_WINDOW_MS = 15L * 60L * 1_000L

        /** ≥ the §5 requirement (16+ bytes) with margin: 24 bytes = 192 bits. */
        internal const val TOKEN_BYTES = 24
        internal const val INVITE_ID_CHARS = 6
        internal const val INVITE_ID_RANDOM_BYTES = 5
        internal const val MAX_NOTE_CHARS = 64
        internal const val MAX_IP_CHARS = 45
        internal const val MAX_OUTCOME_CHARS = 128

        /** The rate-row cap (§6 layer 1, bounded persistence): one row per
         * unique source IP, evicted insertion-oldest-unlocked-first once
         * exceeded — a multi-source bad-token spray cannot grow the store
         * without bound. */
        internal const val MAX_RATE_ROWS = 512
        internal const val DEFAULT_AUDIT_TAIL = 100
        internal const val ACTION_CREATED = "invite-created"
        internal const val ACTION_CREATE_REFUSED = "invite-create-refused"
        internal const val ACTION_REVOKED = "invite-revoked"
        internal const val ACTION_SUBMITTED = "invite-submitted"
        internal const val ACTION_SUBMIT_REFUSED = "invite-submit-refused"
        internal const val ACTION_APPROVED = "invite-approved"
        internal const val ACTION_APPROVAL_FAILED = "invite-approval-failed"
        internal const val ACTION_PROVISIONED = "invite-provisioned"
        internal const val ACTION_REJECTED = "invite-rejected"
        internal const val ACTION_EXPIRED = "invite-expired"
        internal const val ACTION_TOKEN_BURNED = "invite-token-burned"

        // §12: the moderation verbs ride the same no-secret audit rows.
        internal const val ACTION_BANNED = "friend-banned"
        internal const val ACTION_UNBANNED = "friend-unbanned"
        internal const val ACTION_GM_CHANGED = "friend-gm-changed"

        private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val INVITE_ID_CHAR_BITS = 5
        private const val INVITE_ID_CHAR_MASK = 31L
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xFFL

        internal fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
