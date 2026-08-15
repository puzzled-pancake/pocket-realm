package com.pocketrealm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketrealm.log.AppLog
import com.pocketrealm.pkg.CapabilityReport
import com.pocketrealm.pkg.ExperimentResult
import com.pocketrealm.pkg.PackagingExperimentRunner
import com.pocketrealm.pkg.PkgRunIds
import kotlinx.coroutines.launch

/**
 * X0 capability report + PKG experiment console (report §8.4 / §20.1).
 *
 * Shows the live device/process capability snapshot and lets the user run the
 * PKG-01/02 experiments interactively. The genuine PKG-06 30-minute runs are
 * driven by the host driver, not here; this screen offers a short bounded
 * smoke for quick verification.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapabilityScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember(context) { PackagingExperimentRunner(context) }

    var report by remember { mutableStateOf<CapabilityReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ExperimentResult>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (report == null) {
            report = CapabilityReport.probe(context, PkgRunIds.current(context))
        }
    }

    fun launch(block: suspend () -> ExperimentResult) {
        scope.launch {
            busy = true
            val r = runCatching { block() }.getOrElse {
                ExperimentResult.fail("?", PkgRunIds.current(context), "EXCEPTION",
                    listOf("${it.javaClass.simpleName}: ${it.message}"), emptyMap(), 0)
            }
            results = listOf(r) + results
            AppLog.i("CapabilityScreen", r.toLogString())
            busy = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Capability (X0)", style = MaterialTheme.typography.headlineSmall)
        }
        report?.let { rep ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Device / process capability", fontWeight = FontWeight.Bold)
                        kv("testRunId", rep.testRunId)
                        kv("sdkInt", rep.sdkInt.toString())
                        kv("buildId", rep.buildId.orEmpty())
                        kv("abilist", rep.abilist.joinToString(","))
                        kv("abilist32", rep.abilist32.joinToString(",").ifEmpty { "(none)" })
                        kv("abilist64", rep.abilist64.joinToString(","))
                        kv("pageSizeBytes", rep.pageSizeBytes.toString())
                        kv("totalRamBytes", rep.totalRamBytes.toString())
                        kv("allocatableBytes (StorageManager)", rep.allocatableBytes.toString())
                        kv("glVendor (app)", rep.glVendor ?: "(no GL context)")
                        kv("glRenderer (app)", rep.glRenderer ?: "(no GL context)")
                        kv("glVersion (app)", rep.glVersion ?: "(no GL context)")
                        kv("vulkanFeature", rep.vulkanFeature.toString())
                        kv("nativeLibraryDir", rep.nativeLibraryDirObserved ?: "(null)")
                    }
                }
            }
        } ?: item {
            Text("Probing capability…")
        }

        item {
            Text("System checks", style = MaterialTheme.typography.titleMedium)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launch { runner.runPkg01() } }, enabled = !busy) { Text("Native runtime") }
                Button(onClick = { launch { runner.runPkg02() } }, enabled = !busy) { Text("Services") }
                Button(onClick = { launch { runner.runPkg06(durationSeconds = 10) } }, enabled = !busy) { Text("Client session (10s)") }
            }
        }
        if (busy) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        items(results) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${r.experiment}  ${if (r.ok) "OK" else "FAIL"}  ${r.code}  (${r.durationMs}ms)",
                        fontWeight = FontWeight.Bold,
                        color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    r.detail.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    r.evidence.forEach { (k, v) ->
                        Text("$k: $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$k: ", fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
        Text(v, fontFamily = FontFamily.Monospace)
    }
}
