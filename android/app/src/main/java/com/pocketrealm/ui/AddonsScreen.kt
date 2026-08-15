package com.pocketrealm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrealm.addons.AddonCatalog
import com.pocketrealm.addons.AddonCatalogState
import com.pocketrealm.addons.AddonCompatibility
import com.pocketrealm.addons.AddonInstallSource
import com.pocketrealm.addons.AddonRepository
import com.pocketrealm.addons.CatalogAddon
import com.pocketrealm.addons.InstalledAddon

/** Progressive, landscape-first add-on hub. No add-on is installed automatically. */
@Composable
fun AddonsHubScreen(
    onRecommended: () -> Unit,
    onInstalled: () -> Unit,
    onBrowse: () -> Unit,
    onCustom: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { AddonRepository.get(context) }
    val catalog = remember(context) { AddonCatalog.load(context) }
    val state by repository.state.collectAsState()

    AddonPage(repository, state) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("addon-hub-list"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item {
                Text(
                    "Choose one place to start. Add-ons remain optional and apply on the next game launch.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                AddonHubRow(
                    title = "Recommended for Pocket Realm",
                    summary = "${catalog.recommended.size} handheld-friendly choices that stay clear of Android Port's controller bars.",
                    status = "Nothing is installed automatically",
                    onClick = onRecommended,
                    tag = "addon-hub-recommended",
                )
            }
            item {
                AddonHubRow(
                    title = "My add-ons",
                    summary = if (state.installed.isEmpty()) "No add-ons installed yet."
                        else "${state.installed.size} installed for the next game launch.",
                    status = when {
                        state.availableUpdates.isNotEmpty() -> "${state.availableUpdates.size} update(s) available"
                        state.installed.isNotEmpty() -> "Manage, update or remove"
                        else -> "View installed add-ons"
                    },
                    onClick = onInstalled,
                    tag = "addon-hub-installed",
                )
            }
            item {
                AddonHubRow(
                    title = "Browse all ${catalog.addons.size}",
                    summary = "Search the researched Vanilla 1.12 catalog by name, role or feature.",
                    status = "Verified ${catalog.researchedAt}",
                    onClick = onBrowse,
                    tag = "addon-hub-browse",
                )
            }
            item {
                AddonHubRow(
                    title = "Install from GitHub",
                    summary = "Advanced: validate and install a public Vanilla-compatible repository not yet listed.",
                    status = "GitHub only · archive and Interface 11200 checks",
                    onClick = onCustom,
                    tag = "addon-hub-custom",
                )
            }
        }
    }
}

@Composable
fun RecommendedAddonsScreen(onOpenAddon: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { AddonRepository.get(context) }
    val catalog = remember(context) { AddonCatalog.load(context) }
    val state by repository.state.collectAsState()

    AddonPage(repository, state) {
        Text(
            "These are individual suggestions, not a required pack. Open one to review it before installing.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("addon-recommended-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(catalog.recommended, key = CatalogAddon::matrixId) { addon ->
                val installed = state.installed.any { it.id == addon.installId }
                AddonListRow(
                    addon = addon,
                    supporting = checkNotNull(catalog.recommendationReason(addon.matrixId)),
                    status = when {
                        installed -> "Installed"
                        addon.installSource == AddonInstallSource.BUILTIN -> "Optional · built into Pocket Realm"
                        addon.installSource == AddonInstallSource.GITHUB -> "Optional · installs from GitHub"
                        else -> "Reference only"
                    },
                    onClick = { onOpenAddon(addon.matrixId) },
                    tag = "addon-recommended-${addon.matrixId}",
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstalledAddonsScreen(
    onOpenCatalogAddon: (String) -> Unit,
    onOpenCustomAddon: (String) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { AddonRepository.get(context) }
    val catalog = remember(context) { AddonCatalog.load(context) }
    val state by repository.state.collectAsState()
    val busy = state.operation != null

    AddonPage(repository, state) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (state.installed.isEmpty()) "No add-ons installed."
                else "${state.installed.size} installed · changes apply next launch.",
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = repository::checkForUpdates,
                enabled = !busy && state.installed.any { it.repository.startsWith("https://github.com/") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("addon-check-updates"),
            ) { Text("Check updates") }
            if (repository.canRollback() && !busy) {
                OutlinedButton(onClick = repository::rollback, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Undo last change")
                }
            }
        }
        if (state.installed.isEmpty()) {
            EmptyAddonMessage("Browse or Recommended will help you choose an optional add-on.")
        } else {
            val presentations = remember(state.installed, catalog) {
                installedAddonPresentations(catalog, state.installed)
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).testTag("addon-installed-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presentations, key = { it.installed.id }) { presentation ->
                    val installed = presentation.installed
                    val addon = presentation.catalogAddon
                    InstalledAddonRow(
                        installed = installed,
                        addon = addon,
                        updateAvailable = installed.id in state.availableUpdates,
                        onClick = {
                            if (addon != null) onOpenCatalogAddon(addon.matrixId)
                            else onOpenCustomAddon(installed.id)
                        },
                    )
                }
            }
        }
    }
}

internal data class InstalledAddonPresentation(
    val installed: InstalledAddon,
    val catalogAddon: CatalogAddon?,
)

/** Catalog matching never filters the registry; custom installs remain manageable. */
internal fun installedAddonPresentations(
    catalog: AddonCatalog,
    installed: List<InstalledAddon>,
): List<InstalledAddonPresentation> = installed.map { value ->
    InstalledAddonPresentation(value, catalog.addonForInstalled(value))
}

@Composable
fun BrowseAddonsScreen(onOpenAddon: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { AddonRepository.get(context) }
    val catalog = remember(context) { AddonCatalog.load(context) }
    val state by repository.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(query, catalog) { catalog.filter(query, null) }

    AddonPage(repository, state) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search name, role, or feature") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("addon-catalog-search"),
        )
        Text(
            "${visible.size} result${if (visible.size == 1) "" else "s"} · ${state.installed.size} installed",
            style = MaterialTheme.typography.labelLarge,
        )
        if (visible.isEmpty()) {
            EmptyAddonMessage("No researched add-on matches that search.")
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).testTag("addon-catalog-list"),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(visible, key = CatalogAddon::matrixId) { addon ->
                    val installed = state.installed.any { it.id == addon.installId }
                    AddonListRow(
                        addon = addon,
                        supporting = addon.description,
                        status = when {
                            installed -> "Installed"
                            !addon.installable -> "Research reference only"
                            else -> "${addon.handheldScore}/10 · ${addon.handheldVerdict}"
                        },
                        onClick = { onOpenAddon(addon.matrixId) },
                        tag = "addon-catalog-${addon.matrixId}",
                    )
                }
            }
        }
    }
}

@Composable
fun CatalogAddonDetailScreen(matrixId: String) {
    val context = LocalContext.current
    val repository = remember(context) { AddonRepository.get(context) }
    val catalog = remember(context) { AddonCatalog.load(context) }
    val state by repository.state.collectAsState()
    val addon = catalog.addon(matrixId)
    val installed = addon?.installId?.let { id -> state.installed.firstOrNull { it.id == id } }

    AddonPage(repository, state) {
        if (addon == null) {
            EmptyAddonMessage("This catalog entry is no longer available.")
            return@AddonPage
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("addon-catalog-detail"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                AddonDetail(
                    addon = addon,
                    installed = installed,
                    updateAvailable = installed?.id in state.availableUpdates,
                    conflicts = catalog.compatibilityFor(addon.matrixId, state.installed),
                    recommendationReason = catalog.recommendationReason(addon.matrixId),
                )
            }
        }
        HorizontalDivider()
        AddonDetailActions(
            addon = addon,
            installed = installed,
            updateAvailable = installed?.let { it.id in state.availableUpdates } == true,
            busy = state.operation != null,
            onInstall = {
                when (addon.installSource) {
                    AddonInstallSource.BUILTIN -> repository.installBuiltIn(addon)
                    AddonInstallSource.GITHUB -> repository.install(requireNotNull(addon.githubUrl))
                    AddonInstallSource.REFERENCE -> Unit
                }
            },
            onRemove = { installed?.let { repository.remove(it.id) } },
        )
    }
}

@Composable
fun InstalledAddonDetailScreen(installId: String) {
    val context = LocalContext.current
    val repository = remember(context) { AddonRepository.get(context) }
    val state by repository.state.collectAsState()
    val installed = state.installed.firstOrNull { it.id == installId }

    AddonPage(repository, state) {
        if (installed == null) {
            EmptyAddonMessage("This installed add-on is no longer present.")
            return@AddonPage
        }
        Text(installed.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Custom GitHub installation", style = MaterialTheme.typography.titleMedium)
        Text(
            "This add-on was installed from a repository outside the researched catalog. It remains visible here so it can always be removed.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(installed.repository, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Folders: ${installed.folders.joinToString()}")
        Text("Commit: ${installed.commitSha.take(12)}")
        Text(
            "Add-on Lua runs inside WoW. Pocket Realm validates archive structure and Vanilla interface metadata, not the add-on's behaviour.",
            color = MaterialTheme.colorScheme.tertiary,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = { repository.remove(installed.id) },
                enabled = state.operation == null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("addon-custom-remove"),
            ) { Text("Remove") }
        }
    }
}

@Composable
fun CustomAddonInstallScreen() {
    val context = LocalContext.current
    val repository = remember(context) { AddonRepository.get(context) }
    val state by repository.state.collectAsState()
    var customUrl by rememberSaveable { mutableStateOf("") }
    val busy = state.operation != null

    AddonPage(repository, state) {
        Text("Install from GitHub", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Advanced option for a public Vanilla-compatible project not yet in the catalog. Use the repository's main URL, not a download link.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it },
            label = { Text("https://github.com/owner/repository") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("addon-repository-url"),
        )
        Text(
            "Pocket Realm downloads from GitHub, records the exact commit and checksum, rejects oversized or unsafe archives, and requires Vanilla Interface 11200 metadata.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { repository.install(customUrl) },
                enabled = !busy && customUrl.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("addon-install"),
            ) { Text("Validate and install") }
        }
    }
}

@Composable
private fun AddonPage(
    repository: AddonRepository,
    state: AddonCatalogState,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxSize().widthIn(max = 1040.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AddonOperationBanner(
                operationLabel = state.operation?.stage?.label,
                repositoryLabel = state.operation?.repository,
                progress = state.operation?.let { operation ->
                    operation.bytesTotal?.takeIf { it > 0 }?.let { operation.bytesDone.toFloat() / it }
                },
                cancellable = state.operation?.cancellable == true,
                onCancel = repository::cancelCurrent,
                notice = state.notice,
                errorTitle = state.errorTitle,
                error = state.error,
            )
            content()
        }
    }
}

@Composable
private fun AddonHubRow(
    title: String,
    summary: String,
    status: String,
    onClick: () -> Unit,
    tag: String,
) {
    Card(
        Modifier.fillMaxWidth().heightIn(min = 92.dp).clickable(onClick = onClick).testTag(tag),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                status,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun AddonListRow(
    addon: CatalogAddon,
    supporting: String,
    status: String,
    onClick: () -> Unit,
    tag: String,
) {
    Card(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).testTag(tag)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(addon.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(addon.category, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun InstalledAddonRow(
    installed: InstalledAddon,
    addon: CatalogAddon?,
    updateAvailable: Boolean,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick)
            .testTag("addon-installed-${installed.id}"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(addon?.name ?: installed.displayName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    addon?.description ?: "Custom GitHub installation outside the researched catalog.",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (updateAvailable) "Update available" else "Installed",
                color = if (updateAvailable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun AddonDetail(
    addon: CatalogAddon,
    installed: InstalledAddon?,
    updateAvailable: Boolean,
    conflicts: List<Pair<CatalogAddon, AddonCompatibility>>,
    recommendationReason: String?,
) {
    var moreDetails by rememberSaveable(addon.matrixId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(addon.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${addon.category} · ${addon.handheldScore}/10 ${addon.handheldVerdict}")
            }
            Text(
                when {
                    updateAvailable -> "Update available"
                    installed != null -> "Installed"
                    else -> "Optional"
                },
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        recommendationReason?.let {
            Card(Modifier.fillMaxWidth()) { Text("Why it may suit you: $it", Modifier.padding(12.dp)) }
        }
        Text(addon.description, style = MaterialTheme.typography.bodyLarge)
        if (conflicts.isNotEmpty()) {
            Text("With your installed add-ons", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            conflicts.forEach { (other, relation) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${relation.status} ${relation.meaning}: ${other.name}", fontWeight = FontWeight.SemiBold)
                        Text(relation.reason, style = MaterialTheme.typography.bodySmall)
                        Text(relation.action, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        Text(
            "Add-on Lua runs inside WoW. Install only code you trust; compatibility research is not a code audit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        OutlinedButton(
            onClick = { moreDetails = !moreDetails },
            modifier = Modifier.heightIn(min = 48.dp).testTag("addon-more-details"),
        ) { Text(if (moreDetails) "Hide technical details" else "More details") }
        if (moreDetails) {
            Text("Client: ${addon.clientTarget}")
            Text("Version: ${addon.version}")
            Text("Last researched update: ${addon.verifiedUpdate} (${addon.dateConfidence})")
            Text("Maintenance: ${addon.maintenance}")
            if (addon.compatibilityNotes.isNotBlank()) Text("Compatibility: ${addon.compatibilityNotes}")
            Text(addon.communitySignal, color = MaterialTheme.colorScheme.onSurfaceVariant)
            addon.githubUrl?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text("Research: ${addon.researchSources.size} cited source(s).")
        }
        installed?.let {
            Text(
                "Installed ${it.folders.joinToString()} · ${it.commitSha.take(12)} · applies next launch",
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AddonDetailActions(
    addon: CatalogAddon,
    installed: InstalledAddon?,
    updateAvailable: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (installed != null) {
            OutlinedButton(onClick = onRemove, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Remove")
            }
        }
        Button(
            onClick = onInstall,
            enabled = !busy && addon.installable,
            modifier = Modifier.padding(start = 9.dp).heightIn(min = 48.dp).testTag("addon-detail-install"),
        ) {
            Text(when {
                updateAvailable -> "Install update"
                installed != null -> "Reinstall latest"
                addon.installSource == AddonInstallSource.BUILTIN -> "Install built-in"
                addon.installable -> "Install from GitHub"
                else -> "Reference only"
            })
        }
    }
}

@Composable
private fun EmptyAddonMessage(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddonOperationBanner(
    operationLabel: String?,
    repositoryLabel: String?,
    progress: Float?,
    cancellable: Boolean,
    onCancel: () -> Unit,
    notice: String?,
    errorTitle: String?,
    error: String?,
) {
    if (operationLabel == null && notice == null && error == null) return
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    operationLabel != null -> {
                        Text(operationLabel, fontWeight = FontWeight.SemiBold)
                        repositoryLabel?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    error != null -> {
                        Text(errorTitle ?: "Could not complete add-on change",
                            color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        Text(error)
                    }
                    notice != null -> Text(notice, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (operationLabel != null && cancellable) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
    }
}
