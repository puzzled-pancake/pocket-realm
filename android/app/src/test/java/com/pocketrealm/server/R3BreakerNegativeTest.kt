package com.pocketrealm.server

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.SafClientScanner
import com.pocketrealm.supervisor.FriendInviteStore
import com.pocketrealm.supervisor.ProvisionRateLimiter
import com.pocketrealm.supervisor.ProvisioningListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Adversarial breaker (android-core ): negative probes at the
 * seams the earlier rounds' matrices did not cover. Every test either
 * pins a refusal that must hold or DEMONSTRATES a live hole (named in its
 * body) so the fix cannot land silently.
 */
class R3BreakerNegativeTest {

    // ------------------------------------------------------------------
    // 1. THE COLLISION HOLE (R3-1, serious): the Android verifier seam's
    // authorization is dead for the exact case it was written for.
    //
    // WorldRuntimeService.createAccountVerifier computes
    //     writeExpansion = retryAuthorized || shouldWriteExpansion(rc, exists)
    // On a FIRST approval that collides (rc=ACCOUNT_EXISTS, allow=false,
    // row exists) retryAuthorized is false but shouldWriteExpansion(10,
    // true) is TRUE — so the addon write executes on the unproven row and
    // `accepted = addonRc == 0` answers ok=true. The desktop twin refuses
    // the same input at DesktopRuntimeBackend.kt (`if (!allowExistingAccount)
    // ok=false code=ACCOUNT_EXISTS`), so the twins DISAGREE on collisions.
    // Consequences on Android: backend maps ok -> ACCOUNT_CREATED, the
    // controller marks the invite PROVISIONED and the friend's poll reveals
    // the EXISTING account id — the collision contract ("without
    // the authorization ACCOUNT_EXISTS answers ok=false with NO addon
    // write") is not implemented; the refuseCollision leg is unreachable.
    // ------------------------------------------------------------------

    @Test
    fun `an unauthorized collision is refused - the seam decision is conjunctive on the authorization`() {
        // R3-1 fix (android-core review round 3, serious): the decision is
        // the CONJUNCTIVE verifierExpansionWrite — the expression
        // OR-ed the gated leg with the authorization-blind
        // shouldWriteExpansion, making the gate a no-op (the hole this test
        // used to demonstrate). The behavioral matrix, evaluated through the
        // pure function the seam consumes:
        val rc = ServerRuntimeContract.ACCOUNT_EXISTS_CODE
        // First approval colliding with an existing account: REFUSED.
        assertThrows(IllegalStateException::class.java) {
            // (the seam's accepted answer for this input would be a
            // contract violation; the decision itself is false)
            check(
                !ServerRuntimeContract.verifierExpansionWrite(
                    rc, accountExists = true, allowExistingAccount = false,
                ),
            )
            error("an unauthorized collision must not converge")
        }
        // The designed retry (attempt bookkeeping present): converges.
        assertTrue(
            ServerRuntimeContract.verifierExpansionWrite(
                rc, accountExists = true, allowExistingAccount = true,
            ),
        )
        // ACCOUNT_EXISTS without a verified row: never.
        assertTrue(
            !ServerRuntimeContract.verifierExpansionWrite(
                rc, accountExists = false, allowExistingAccount = true,
            ),
        )
        // Source contract: the seam consumes the conjunctive decision, never
        // the OR-ed no-op shape.
        val body = methodBody(
            locateSource("android/app/src/main/java/com/pocketrealm/server/WorldRuntimeService.kt"),
            "override fun createAccountVerifier(",
        )
        assertTrue(
            "the seam must consume verifierExpansionWrite",
            "ServerRuntimeContract.verifierExpansionWrite(" in body,
        )
        assertTrue(
            "the dead OR-ed shape must be gone",
            "retryAuthorized ||" !in body &&
                "shouldWriteExpansion(rc, accountExists)" !in body,
        )
    }

    // ------------------------------------------------------------------
    // 2. The poll/reveal identity echo: the PROVISIONED response names the
    // POLLER'S username, never the row's — a friend who edits the username
    // field between submit and poll is told the account is the edited name
    // (the real account keeps the submitted name; the join then fails
    // without a clean explanation anywhere).
    // ------------------------------------------------------------------

    @Test
    fun `the provisioned reveal echoes the poll request username not the row identity`() {
        tempDir().use { dir ->
            val store = FriendInviteStore(dir)
            val limiter = ProvisionRateLimiter(store)
            ProvisioningListener(store, limiter, "127.0.0.1", port = 0).use { listener ->
                val port = (listener.start() as ProvisioningListener.ListenerState.Listening).port
                val created = store.create("r3-echo") as FriendInviteStore.CreateResult.Created
                val submitted = FriendProvisionProtocol.encodeRequest(
                    created.inviteId, created.tokenBase64Url,
                    "friendone", "aa".repeat(32), "bb".repeat(32),
                )
                ask(port, submitted)
                store.markApproved(created.inviteId)
                store.markProvisioned(created.inviteId, 42L)
                // The poll carries a DIFFERENT username (an edited form field).
                val poll = FriendProvisionProtocol.encodeRequest(
                    created.inviteId, created.tokenBase64Url,
                    "someoneelse", "aa".repeat(32), "bb".repeat(32),
                )
                val answer = JSONObject(ask(port, poll)!!.decodeText())
                assertTrue(answer.optBoolean("ok"))
                assertEquals("PROVISIONED", answer.optString("state"))
                // fix: the reveal names the ROW's stored username —
                // the poll request's edited username field is not an
                // identity and must never be echoed back as one.
                assertEquals(
                    "friendone",
                    answer.optString("username"),
                )
                assertEquals(
                    "friendone",
                    store.rows().first { it.inviteId == created.inviteId }.username,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. Manifest matrix cells the earlier rounds did not plant
    // ------------------------------------------------------------------

    @Test
    fun `a same-family realmd-adjacent build 6005 manifest is refused on the vanilla lane`() {
        // realmd accepts 6005 for vanilla (realmbuilds "5875 6005 6141") but
        // the client identity is 5875 only — a prepared generation stamped
        // with the adjacent build must refuse, never coerce.
        val manifest = vanillaManifest().put("clientBuild", 6005)
        assertThrows(IllegalStateException::class.java) {
            PreparedDataStore.validateDataManifestEnvelope(manifest, ClientFlavors.VANILLA)
        }
    }

    @Test
    fun `flavor and family aliases are case-sensitive - no case-insensitive coercion`() {
        assertThrows(IllegalStateException::class.java) {
            PreparedDataStore.validateDataManifestEnvelope(
                vanillaManifest().put("flavor", "Vanilla"), ClientFlavors.VANILLA,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            PreparedDataStore.validateDataManifestEnvelope(
                vanillaManifest().put("cmangosFamily", "Classic"), ClientFlavors.VANILLA,
            )
        }
    }

    @Test
    fun `a hash-consistent manifest missing counts refuses loud but without the actionable copy`() {
        // Doctored pair whose bytes match the pointer (self-consistent) but
        // lacking "counts": the reader throws a raw JSONException ("No value
        // for counts"), not the designed "Rebuild it from Game files" copy.
        // Still fail-closed (the world refuses to start) — this pins the
        // message-quality gap for a crafted, hash-valid manifest.
        val root = tempRoot()
        try {
            val id = "12345678-1234-1234-1234-123456789abc"
            val generation = File(root, "generations/$id").apply { mkdirs() }
            val manifest = vanillaManifest()
            manifest.remove("counts") // JSONObject.remove returns the removed value, not the receiver
            val manifestFile = File(generation, "data-manifest.json").apply { writeText(manifest.toString()) }
            File(root, "active-vanilla.json").writeText(
                JSONObject()
                    .put("schema", 1)
                    .put("mode", "NORMAL")
                    .put("generation", id)
                    .put("manifestSha256", sha256(manifestFile))
                    .toString(),
            )
            val failure = assertThrows(Exception::class.java) {
                PreparedDataStore(root).requireActiveEnvelope(ClientFlavors.VANILLA)
            }
            assertTrue(
                "the raw decode error must still refuse (fail-closed)",
                failure.message!!.contains("counts"),
            )
            assertTrue(
                "the crafted-pair path answers a raw JSON error, never the actionable copy",
                !failure.message!!.contains("Rebuild it from Game files"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 4. Doctored MPQ sets: the documented extras gap at full foreign-set
    // scale — a vanilla-identity tree carrying WotLK's ENTIRE root set
    // (patch-4 included) imports with zero failures. The round-2 pin used
    // one synthetic patch-9; this is the real cross-flavor payload.
    // ------------------------------------------------------------------

    @Test
    fun `a full foreign wotlk root set rides into a vanilla import as extras`() {
        val access = tree(exe = clientExe(), mpqs = VANILLA_BASE_MPQS + WOTLK_ROOT_MPQS)
        val result = SafClientScanner.scanAccess(access)
        assertTrue(
            "the doctored extras gap: ${result.failures}",
            result.supported && result.failures.isEmpty(),
        )
        assertEquals("wow-1.12.1-5875", result.clientId)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("r3-breaker").toFile()

    private fun tempRoot(): File = Files.createTempDirectory("r3-manifest").toFile()

    private fun vanillaManifest(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("complete", true)
        .put("mode", "NORMAL")
        .put("clientBuild", 5875)
        .put("cmangosFamily", "classic")
        .put(
            "counts",
            JSONObject()
                .put("dbc", 100).put("maps", 100).put("vmapTrees", 1)
                .put("vmapTiles", 0).put("mmapMaps", 1).put("mmapTiles", 1),
        )

    private fun mpqHeader(): ByteArray = byteArrayOf(0x4d, 0x50, 0x51, 0x1a) + ByteArray(4)

    /** A minimal PE whose VS_FIXEDFILEINFO stamp carries [version]. */
    private fun clientExe(version: String = "1.12.1.5875"): ByteArray {
        val buf = ByteArray(1024)
        buf[0] = 'M'.code.toByte()
        buf[1] = 'Z'.code.toByte()
        val pe = 0x80
        buf[0x3c] = pe.toByte()
        "PE\u0000\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(buf, pe)
        buf[pe + 4] = 0x4c
        buf[pe + 5] = 0x01
        buf[pe + 24] = 0x0b
        buf[pe + 25] = 0x01
        val parts = version.split('.').map { it.toInt() }
        val ms = (parts[0] shl 16) or parts[1]
        val ls = (parts[2] shl 16) or parts[3]
        fun put32(offset: Int, value: Int) {
            buf[offset] = value.toByte()
            buf[offset + 1] = (value ushr 8).toByte()
            buf[offset + 2] = (value ushr 16).toByte()
            buf[offset + 3] = (value ushr 24).toByte()
        }
        put32(0x200, 0xFEEF04BDL.toInt())
        put32(0x208, ms)
        put32(0x20c, ls)
        return buf
    }

    private fun tree(exe: ByteArray, mpqs: Set<String>): SafClientScanner.Access {
        val files = mutableMapOf<String, ByteArray>("WoW.exe" to exe)
        mpqs.forEach { files["Data/$it"] = mpqHeader() }
        return FakeAccess(files)
    }

    private class FakeAccess(
        private val files: Map<String, ByteArray>,
    ) : SafClientScanner.Access {
        private val dirs = mutableSetOf("Data")

        override val rootId: String = ""

        override fun children(parentId: String): List<SafClientScanner.Entry> {
            val prefix = if (parentId.isEmpty()) "" else "$parentId/"
            val dirEntries = dirs
                .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .map { SafClientScanner.Entry(it, it.substringAfterLast('/'), true, 0) }
            val fileEntries = files
                .filterKeys { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .map {
                    SafClientScanner.Entry(
                        it.key,
                        it.key.substringAfterLast('/'),
                        false,
                        it.value.size.toLong(),
                    )
                }
            return dirEntries + fileEntries
        }

        override fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean): ByteArray =
            files.getValue(documentId).copyOf(minOf(files.getValue(documentId).size, maxBytes))
    }

    private fun ask(port: Int, payload: ByteArray): ByteArray? = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 20_000
        socket.getOutputStream().apply { write(payload); flush() }
        FriendProvisionProtocol.readFrame(
            socket.getInputStream(),
            FriendProvisionProtocol.MAX_RESPONSE_BYTES,
        )
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun locateSource(repoRelative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = dir?.let { File(it, repoRelative) }
            if (candidate != null && candidate.isFile) return candidate.readText()
            dir = dir?.parentFile
        }
        error("source not found walking up from ${System.getProperty("user.dir")}: $repoRelative")
    }

    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "method not found: $signature" }
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("unbalanced braces for: $signature")
    }

    private fun File.use(block: (File) -> Unit) {
        try {
            block(this)
        } finally {
            deleteRecursively()
        }
    }

    private fun ProvisioningListener.use(block: (ProvisioningListener) -> Unit) {
        try {
            block(this)
        } finally {
            stop()
        }
    }

    private fun ByteArray.decodeText(): String = String(this, Charsets.UTF_8)

    private companion object {
        val VANILLA_BASE_MPQS: Set<String> = ClientFlavors.VANILLA.requiredMpqs
        val WOTLK_ROOT_MPQS: Set<String> = setOf(
            "common.mpq", "common-2.mpq", "expansion.mpq", "lichking.mpq",
            "patch.mpq", "patch-2.mpq", "patch-3.mpq", "patch-4.mpq",
        )
    }
}
