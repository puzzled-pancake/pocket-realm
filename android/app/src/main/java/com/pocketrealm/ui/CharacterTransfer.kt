package com.pocketrealm.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import com.pocketrealm.supervisor.UserAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The portable character file: the world's `pdump write` SQL wrapped in a
 * small envelope naming the flavor it came from and the SHA-256 of the
 * dump. Import refuses another flavor's file (the dump's table layout is
 * per-expansion) and any file whose digest no longer matches.
 */
internal object CharacterTransferFile {
    const val KIND = "pocket-realm-character"
    const val SCHEMA = 1

    /** Read cap for a picked file: the dump is JSON-escaped inside. */
    const val MAX_FILE_BYTES = 1024 * 1024

    data class Parsed(
        val flavorId: String,
        val characterName: String,
        val createdAtMs: Long,
        val dump: String,
    )

    fun encode(flavorId: String, characterName: String, dump: String, createdAtMs: Long): String =
        JSONObject().put("schema", SCHEMA).put("kind", KIND).put("flavor", flavorId)
            .put("character", characterName).put("createdAtMs", createdAtMs)
            .put("sha256", sha256(dump)).put("dump", dump).toString(2)

    fun decode(text: String): Parsed {
        require(text.length <= MAX_FILE_BYTES) { "the file is too large to be a character file" }
        val json = JSONObject(text)
        require(json.optString("kind") == KIND && json.optInt("schema") == SCHEMA) {
            "not a Pocket Realm character file"
        }
        val dump = json.getString("dump")
        require(dump.isNotEmpty() && sha256(dump) == json.getString("sha256")) {
            "the character file is damaged (checksum mismatch)"
        }
        return Parsed(
            flavorId = json.getString("flavor"),
            characterName = json.getString("character"),
            createdAtMs = json.optLong("createdAtMs"),
            dump = dump,
        )
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

private const val READ_CHUNK = 16 * 1024

/** Human copy for a transfer verdict's detail field. */
internal fun characterTransferCopy(detail: String): String = when {
    detail == "runtime-outdated" ->
        "This build's server runtime predates character transfer — update Pocket Realm to use it."
    detail == "world-not-ready" -> "Start the realm first — transfers run inside the live world."
    detail.isBlank() -> "The realm did not answer."
    else -> detail.trim()
}

/**
 * Move one character between realms: export writes a character file from
 * this realm (inventory, spells, skills, quest log, reputation); import
 * loads one into an account here under fresh identifiers, renaming on a
 * name conflict. Both need the realm running (the world owns the data).
 */
@Composable
internal fun CharacterTransferControls(realmRunning: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val supervisor = remember(context) { RuntimeSupervisorClient(context) }
    var characterName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<CharacterTransferFile.Parsed?>(null) }
    val nameValid = characterName.length in 2..12 && characterName.all { it.isLetter() && it.code < 128 }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val encoded = pendingExport
        pendingExport = null
        if (uri == null || encoded == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)) { "could not open the file" }
                        .use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
                }
                "Character file saved."
            }.getOrElse { "Saving failed: ${it.message ?: it.javaClass.simpleName}" }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = runCatching {
                val text = withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openInputStream(uri)) { "could not open the file" }
                        .use { input ->
                            // Bounded read (InputStream.readNBytes needs API 33).
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(READ_CHUNK)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                require(out.size() <= CharacterTransferFile.MAX_FILE_BYTES) {
                                    "the file is too large to be a character file"
                                }
                            }
                            out.toString(Charsets.UTF_8.name())
                        }
                }
                CharacterTransferFile.decode(text)
            }
            parsed.onSuccess { file ->
                val activeFlavor = ActiveFlavor.displayedId
                if (activeFlavor != null && file.flavorId != activeFlavor) {
                    status = "That character comes from a ${file.flavorId} realm and this realm runs " +
                        "$activeFlavor. Switch game versions on Home to import it."
                } else {
                    pendingImport = file
                }
            }.onFailure { status = "Could not read the character file: ${it.message}" }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Export one of your characters — inventory, spells, skills, quest log and " +
                "reputation — into a file, then import it on a friend's realm (or another of " +
                "your own). The receiving realm gives it fresh identifiers and renames it on a " +
                "name conflict, so nothing there is overwritten. Both realms must run the same " +
                "game version.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!realmRunning) {
            Text(
                "Start the realm (Home or Host) to export or import — the world owns character data.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        OutlinedTextField(
            value = characterName,
            onValueChange = { characterName = it.trim().take(12) },
            label = { Text("Character to export") },
            isError = characterName.isNotEmpty() && !nameValid,
            supportingText = { Text("2-12 letters, exactly as in game") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("character-transfer-name"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    busy = true
                    status = "Exporting $characterName…"
                    scope.launch {
                        val result = runCatching { supervisor.exportCharacter(characterName) }.getOrNull()
                        val dump = result?.optString("dump").orEmpty()
                        if (result?.optBoolean("ok") == true && dump.isNotEmpty()) {
                            pendingExport = CharacterTransferFile.encode(
                                ActiveFlavor.displayedId.orEmpty(), characterName, dump,
                                System.currentTimeMillis(),
                            )
                            status = "Choose where to save the character file."
                            exportPicker.launch("$characterName.prchar.json")
                        } else {
                            status = characterTransferCopy(result?.optString("detail").orEmpty())
                        }
                        busy = false
                    }
                },
                enabled = realmRunning && nameValid && !busy,
                modifier = Modifier.weight(1f).testTag("character-transfer-export"),
            ) { Text("Export character") }
            OutlinedButton(
                onClick = { importPicker.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                enabled = realmRunning && !busy,
                modifier = Modifier.weight(1f).testTag("character-transfer-import"),
            ) { Text("Import character") }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("character-transfer-status"))
        }
    }

    pendingImport?.let { file ->
        var account by remember(file) {
            mutableStateOf(runCatching { UserAccountStore(context).loadOrQuarantine()?.username }.getOrNull().orEmpty())
        }
        var newName by remember(file) { mutableStateOf("") }
        val accountValid = account.length in 1..16 && account.all { it.isLetterOrDigit() && it.code < 128 }
        val newNameValid = newName.isEmpty() || (newName.length in 2..12 && newName.all { it.isLetter() && it.code < 128 })
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import ${file.characterName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The character joins the account below on this realm. Leave the new name " +
                            "empty to keep \"${file.characterName}\"; a taken name is renamed at login.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it.trim().take(16) },
                        label = { Text("Account on this realm") },
                        isError = account.isNotEmpty() && !accountValid,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.trim().take(12) },
                        label = { Text("New name (optional)") },
                        isError = !newNameValid,
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = accountValid && newNameValid,
                    onClick = {
                        pendingImport = null
                        busy = true
                        status = "Importing ${file.characterName}…"
                        scope.launch {
                            val result = runCatching {
                                supervisor.importCharacter(file.dump, account, newName)
                            }.getOrNull()
                            status = if (result?.optBoolean("ok") == true) {
                                "Imported into $account. " + characterTransferCopy(result.optString("detail"))
                            } else {
                                characterTransferCopy(result?.optString("detail").orEmpty())
                            }
                            busy = false
                        }
                    },
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }
}
