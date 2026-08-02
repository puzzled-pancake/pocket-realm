package com.pocketrealm.supervisor

import android.content.Context
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Atomic temp+fsync+rename journal required by report section 18.5. */
class AtomicSupervisorJournal(context: Context) : SupervisorJournal {
    private val lock = Any()
    private val directory = File(context.noBackupFilesDir, "runtime-supervisor").apply { mkdirs() }
    private val journal = File(directory, "journal.json")

    override fun read(): RuntimeSnapshot? = synchronized(lock) {
        if (!journal.isFile) return null
        try {
            decode(JSONObject(journal.readText(Charsets.UTF_8)))
        } catch (error: Throwable) {
            RuntimeSnapshot(
                phase = RuntimePhase.ERROR,
                clean = false,
                lastDurableAction = "journal-decode-failed",
                lastError = "JOURNAL_CORRUPT: ${(error.message ?: error.javaClass.simpleName).take(384)}",
                recoverability = Recoverability.USER_ACTION_REQUIRED,
                updatedAtWallMs = System.currentTimeMillis(),
                updatedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
    }

    override fun write(snapshot: RuntimeSnapshot) = synchronized(lock) {
        require(snapshot.schema == RuntimeSnapshot.JOURNAL_SCHEMA)
        directory.mkdirs()
        val temp = File(directory, ".journal.${android.os.Process.myPid()}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(encode(snapshot).toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Os.chmod(temp.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        // rename(2) replaces the same-directory target atomically; never
        // introduce a delete window in which the durable journal is absent.
        Os.rename(temp.absolutePath, journal.absolutePath)
        // Persist the directory entry as well as the file contents.
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    fun fileForTest(): File = journal

    private fun encode(value: RuntimeSnapshot): JSONObject = JSONObject()
        .put("schema", value.schema)
        .put("sessionId", value.sessionId)
        .put("phase", value.phase.name)
        .put("requestedProfile", value.requestedProfile)
        .put("clean", value.clean)
        .put("components", JSONObject().also { components ->
            RuntimeComponent.entries.forEach { component ->
                val state = value.components.getValue(component)
                components.put(component.name.lowercase(), JSONObject()
                    .put("state", state.state.name)
                    .put("instanceToken", state.instanceToken)
                    .put("startedAtWallMs", state.startedAtWallMs)
                    .put("detail", state.detail.take(512)))
            }
        })
        .put("lastDurableAction", value.lastDurableAction.take(128))
        .put("lastError", value.lastError?.take(512))
        .put("updatedAtWallMs", value.updatedAtWallMs)
        .put("updatedAtElapsedMs", value.updatedAtElapsedMs)
        .put("recoverability", value.recoverability.name)

    private fun decode(value: JSONObject): RuntimeSnapshot {
        require(value.getInt("schema") == RuntimeSnapshot.JOURNAL_SCHEMA) { "unsupported journal schema" }
        fun nullable(name: String): String? = if (value.isNull(name)) null else value.getString(name)
        val session = nullable("sessionId")
        if (session != null) java.util.UUID.fromString(session)
        val componentsValue = value.getJSONObject("components")
        val components = RuntimeComponent.entries.associateWith { component ->
            val encoded = componentsValue.getJSONObject(component.name.lowercase())
            val token = if (encoded.isNull("instanceToken")) null else encoded.getString("instanceToken")
            require(token == null || TOKEN.matches(token)) { "invalid component token" }
            ComponentSnapshot(
                state = ComponentLifecycle.valueOf(encoded.getString("state")),
                instanceToken = token,
                startedAtWallMs = if (encoded.isNull("startedAtWallMs")) null else encoded.getLong("startedAtWallMs"),
                detail = encoded.optString("detail").take(512),
            )
        }
        return RuntimeSnapshot(
            sessionId = session,
            phase = RuntimePhase.valueOf(value.getString("phase")),
            requestedProfile = nullable("requestedProfile"),
            clean = value.getBoolean("clean"),
            components = components,
            lastDurableAction = value.getString("lastDurableAction").take(128),
            lastError = nullable("lastError")?.take(512),
            updatedAtWallMs = value.getLong("updatedAtWallMs"),
            updatedAtElapsedMs = value.getLong("updatedAtElapsedMs"),
            recoverability = Recoverability.valueOf(value.getString("recoverability")),
        )
    }

    companion object { private val TOKEN = Regex("[0-9a-f]{64}") }
}
