package com.pocketrealm.addons

import android.content.Context
import android.system.Os
import com.pocketrealm.client.InputProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * App-scoped, single-writer GitHub addon manager. A published registry is
 * immutable for a running game; [AddonRuntimeProjector] applies it next launch.
 */
class AddonRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, "addons").apply { mkdirs() }
    private val packages = File(root, "packages").apply { mkdirs() }
    private val scratch = File(appContext.cacheDir, "addon-downloads").apply { mkdirs() }
    private val registry = File(root, "registry.json")
    private val previousRegistry = File(root, "registry.previous.json")
    private val registryJournal = File(root, "registry.transaction.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val validator = AddonArchiveValidator()
    private val extractor = AddonArchiveExtractor()
    private val registryPublisher = AddonRegistryPublisher(
        registry = registry,
        previousRegistry = previousRegistry,
        journal = registryJournal,
        atomicWrite = ::writeAtomic,
    )
    init {
        registryPublisher.recoverIfNeeded()
        cleanupStaleAddonStaging(packages)
        retireRemovedProducts()
    }
    private val initialInstalled = loadRegistry(registry)
    private val mutableState = MutableStateFlow(AddonCatalogState(installed = initialInstalled))
    val state: StateFlow<AddonCatalogState> = mutableState.asStateFlow()
    private val operationGuard = Any()
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeToken: AddonOperationToken? = null
    @Volatile private var activeCancelledNotice: String? = null

    init {
        reconcileVanillaConsolePort(initialInstalled)?.let { failure ->
            mutableState.value = mutableState.value.copy(
                errorTitle = "Android Port controls need attention",
                error = failure,
            )
        }
        refreshInstalledBuiltInIfNeeded(initialInstalled)
    }

    /**
     * A built-in add-on is part of the APK rather than a permanently pinned
     * download. On app upgrade, republish the new validated asset package while
     * retaining the prior registry/package as the ordinary one-step rollback.
     */
    private fun refreshInstalledBuiltInIfNeeded(installed: List<InstalledAddon>) {
        if (installed.none { it.id == VanillaConsolePortPackage.INSTALL_ID }) return
        launchOperation(errorTitle = "Could not refresh Android Port") { token ->
            val current = loadRegistryStrict(registry)
                .firstOrNull { it.id == VanillaConsolePortPackage.INSTALL_ID }
                ?: return@launchOperation
            val assetDigest = sha256AssetTree(VanillaConsolePortPackage.ASSET_PATH, token)
            if (assetDigest.equals(current.archiveSha256, ignoreCase = true)) return@launchOperation
            val addon = checkNotNull(AddonCatalog.load(appContext).addon("151"))
            installBuiltInLocked(addon, token)
        }
    }

    fun install(repositoryUrl: String) {
        launchOperation(
            errorTitle = "Could not install add-on",
            cancelledNotice = "Installation cancelled. Installed add-ons were not changed.",
        ) { token ->
            installLocked(GitHubRepoRef.parse(repositoryUrl), token)
        }
    }

    fun installBuiltIn(addon: CatalogAddon) {
        require(addon.installSource == AddonInstallSource.BUILTIN)
        require(addon.installId == VanillaConsolePortPackage.INSTALL_ID)
        require(addon.assetPath == VanillaConsolePortPackage.ASSET_PATH)
        launchOperation(
            errorTitle = "Could not install built-in add-on",
            cancelledNotice = "Installation cancelled. Installed add-ons were not changed.",
        ) { token ->
            installBuiltInLocked(addon, token)
        }
    }

    fun checkForUpdates() {
        launchOperation(errorTitle = "Could not check add-on updates") { token ->
            val current = loadRegistryStrict(registry)
            val updates = linkedMapOf<String, String>()
            current.firstOrNull { it.id == VanillaConsolePortPackage.INSTALL_ID }?.let { installed ->
                token.checkpoint()
                val digest = sha256AssetTree(VanillaConsolePortPackage.ASSET_PATH, token)
                if (!digest.equals(installed.archiveSha256, ignoreCase = true)) {
                    updates[installed.id] = digest.take(40)
                }
            }
            current.filter { it.repository.startsWith("https://github.com/", ignoreCase = true) }
                .forEach { installed ->
                    token.checkpoint()
                    mutableState.value = mutableState.value.copy(
                        operation = AddonOperation(AddonStage.RESOLVING, installed.displayName),
                        notice = null,
                        errorTitle = null,
                        error = null,
                    )
                    val latest = resolveGitHub(GitHubRepoRef.parse(installed.repository), token)
                    val latestIdentity = if (latest.ref.id == VoiceOverReleaseResolver.INSTALL_ID) {
                        resolveVoiceOverAssets(latest, token).remoteIdentity
                    } else latest.commit
                    if (!latestIdentity.equals(installed.commitSha, ignoreCase = true)) {
                        updates[installed.id] = latestIdentity
                    }
                }
            mutableState.value = AddonCatalogState(
                installed = current,
                availableUpdates = updates,
                updatesCheckedAtEpochMs = System.currentTimeMillis(),
                notice = if (updates.isEmpty()) "Installed add-ons are up to date."
                    else "${updates.size} add-on update${if (updates.size == 1) "" else "s"} available.",
            )
        }
    }

    fun remove(addonId: String) {
        launchOperation(errorTitle = "Could not remove add-on") { token ->
            val current = loadRegistryStrict(registry)
            val target = current.firstOrNull { it.id == addonId }
                ?: error("The selected add-on is no longer installed")
            mutableState.value = mutableState.value.copy(
                operation = AddonOperation(AddonStage.REMOVING, target.repository, cancellable = false),
                notice = null,
                errorTitle = null,
                error = null,
            )
            token.beginCommit()
            publishRegistry(current.filterNot { it.id == addonId })
            val installed = loadRegistryStrict(registry)
            val profileFailure = reconcileVanillaConsolePort(installed)
            mutableState.value = AddonCatalogState(
                installed = installed,
                notice = if (profileFailure == null) {
                    "${target.displayName} will be removed the next time the game starts."
                } else {
                    "${target.displayName} was removed, but its prior control preset could not be restored."
                },
                errorTitle = profileFailure?.let { "Android Port controls need attention" },
                error = profileFailure,
            )
        }
    }

    fun rollback() {
        launchOperation(errorTitle = "Could not restore add-ons") { token ->
            require(previousRegistry.isFile) { "There is no previous add-on set to restore" }
            val prior = loadRegistryStrict(previousRegistry)
            require(prior.all { File(root, it.packagePath).isDirectory }) {
                "The previous add-on set is no longer available"
            }
            mutableState.value = mutableState.value.copy(
                operation = AddonOperation(AddonStage.INSTALLING, "Previous add-on set", cancellable = false),
                notice = null,
                errorTitle = null,
                error = null,
            )
            token.beginCommit()
            publishRegistry(prior)
            val installed = loadRegistryStrict(registry)
            val profileFailure = reconcileVanillaConsolePort(installed)
            mutableState.value = AddonCatalogState(
                installed = installed,
                notice = if (profileFailure == null) {
                    "Previous add-on set restored for the next game launch."
                } else {
                    "Previous add-ons were restored, but the matching controller preset needs attention."
                },
                errorTitle = profileFailure?.let { "Android Port controls need attention" },
                error = profileFailure,
            )
        }
    }

    fun cancelCurrent() {
        val active = synchronized(operationGuard) {
            Triple(activeToken, activeJob, activeCancelledNotice)
        }
        if (active.first?.cancel() == true) {
            mutableState.value = mutableState.value.copy(
                operation = null,
                notice = active.third ?: "Change cancelled. Installed add-ons were not changed.",
                errorTitle = null,
                error = null,
            )
            active.second?.cancel(CancellationException("Add-on operation cancelled"))
        }
    }
    fun canRollback(): Boolean = previousRegistry.isFile

    private fun launchOperation(
        errorTitle: String,
        cancelledNotice: String = "Change cancelled. Installed add-ons were not changed.",
        action: (AddonOperationToken) -> Unit,
    ) {
        synchronized(operationGuard) {
            if (activeJob?.isActive == true) return
            val token = AddonOperationToken()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                mutex.withLock {
                    try {
                        action(token)
                    } catch (cancelled: CancellationException) {
                        mutableState.value = mutableState.value.copy(
                            operation = null,
                            notice = cancelledNotice,
                            errorTitle = null,
                            error = null,
                        )
                        throw cancelled
                    } catch (failure: Throwable) {
                        mutableState.value = mutableState.value.copy(
                            operation = null,
                            notice = null,
                            errorTitle = errorTitle,
                            error = failure.message ?: errorTitle,
                        )
                    } finally {
                        synchronized(operationGuard) {
                            if (activeToken === token) {
                                activeToken = null
                                activeJob = null
                                activeCancelledNotice = null
                            }
                        }
                    }
                }
            }
            activeToken = token
            activeJob = job
            activeCancelledNotice = cancelledNotice
            job.invokeOnCompletion {
                token.finish()
                synchronized(operationGuard) {
                    if (activeToken === token) {
                        activeToken = null
                        activeJob = null
                        activeCancelledNotice = null
                    }
                }
            }
            job.start()
        }
    }

    private fun installLocked(ref: GitHubRepoRef, token: AddonOperationToken) {
        token.checkpoint()
        mutableState.value = mutableState.value.copy(
            operation = AddonOperation(AddonStage.RESOLVING, ref.slug),
            notice = null,
            errorTitle = null,
            error = null,
        )
        val resolved = resolveGitHub(ref, token)
        val canonical = resolved.ref
        val commit = resolved.commit
        if (canonical.id == VoiceOverReleaseResolver.INSTALL_ID) {
            installVoiceOverLocked(resolved, token)
            return
        }
        val archive = File(scratch, "${canonical.id}-${System.nanoTime()}.zip")
        var staging: File? = null
        try {
            download(
                "https://api.github.com/repos/${canonical.owner}/${canonical.repo}/zipball/$commit",
                archive,
                canonical.slug,
                token,
            )
            token.checkpoint()
            mutableState.value = mutableState.value.copy(
                operation = AddonOperation(AddonStage.VALIDATING, canonical.slug),
            )
            val validated = validator.validate(archive, canonical.repo, checkpoint = token::checkpoint)
            val digest = sha256(archive, token)
            val packageRelative = "packages/${canonical.id}/$commit"
            val finalPackage = File(root, packageRelative)
            if (!finalPackage.isDirectory) {
                staging = File(packages, ".staging-${canonical.id}-${System.nanoTime()}")
                check(staging.mkdirs()) { "Addon staging directory could not be created" }
                extractor.extract(archive, validated, staging, token::checkpoint)
            }
            token.checkpoint()
            mutableState.value = mutableState.value.copy(
                operation = AddonOperation(AddonStage.INSTALLING, canonical.slug),
            )
            val installed = InstalledAddon(
                id = canonical.id,
                repository = "https://github.com/${canonical.slug}",
                displayName = if (canonical.id == VanillaConsolePortPackage.INSTALL_ID) {
                    VanillaConsolePortPackage.DISPLAY_NAME
                } else {
                    canonical.repo
                },
                commitSha = commit.lowercase(Locale.ROOT),
                archiveSha256 = digest,
                installedAtEpochMs = System.currentTimeMillis(),
                packagePath = packageRelative,
                folders = validated.addonFolders,
            )
            val current = loadRegistryStrict(registry).filterNot { it.id == installed.id } + installed
            token.beginCommit()
            mutableState.value = mutableState.value.copy(
                operation = AddonOperation(AddonStage.INSTALLING, canonical.slug, cancellable = false),
            )
            var publishedNewPackage = false
            try {
                staging?.let { prepared ->
                    finalPackage.parentFile!!.mkdirs()
                    check(prepared.renameTo(finalPackage)) { "Validated add-on could not be published" }
                    publishedNewPackage = true
                    staging = null
                }
                publishRegistry(current.sortedBy { it.displayName.lowercase(Locale.ROOT) })
            } catch (failure: Throwable) {
                if (publishedNewPackage) finalPackage.deleteRecursively()
                throw failure
            }
            runCatching { prunePackages(loadRegistry(registry), loadRegistry(previousRegistry)) }
            val profileFailure = reconcileVanillaConsolePort(loadRegistryStrict(registry))
            mutableState.value = AddonCatalogState(
                installed = loadRegistryStrict(registry),
                notice = when {
                    profileFailure != null ->
                        "${installed.displayName} installed, but its matching controller preset could not be selected."
                    installed.id == VanillaConsolePortPackage.INSTALL_ID ->
                        "Android Port and its matching Winlator control preset are ready for the next game launch."
                    else -> "${installed.displayName} is ready for the next game launch."
                },
                errorTitle = profileFailure?.let { "Android Port controls need attention" },
                error = profileFailure,
            )
        } finally {
            staging?.takeIf { it.exists() }?.deleteRecursively()
            archive.delete()
        }
    }

    private fun installBuiltInLocked(addon: CatalogAddon, token: AddonOperationToken) {
        val installId = requireNotNull(addon.installId)
        val assetPath = requireNotNull(addon.assetPath)
        token.checkpoint()
        mutableState.value = mutableState.value.copy(
            operation = AddonOperation(AddonStage.VALIDATING, addon.name),
            notice = null,
            errorTitle = null,
            error = null,
        )

        var staging: File? = File(packages, ".staging-$installId-${System.nanoTime()}")
        try {
            check(staging!!.mkdirs()) { "Built-in add-on staging directory could not be created" }
            copyBuiltInAssetTree(assetPath, staging!!, token)
            val folders = validateBuiltInPackage(staging!!)
            val digest = sha256Tree(staging!!, token)
            val identity = digest.take(40)
            val packageRelative = "packages/$installId/$identity"
            val finalPackage = File(root, packageRelative)
            if (finalPackage.isDirectory) {
                require(folders.all { File(finalPackage, it).isDirectory }) {
                    "The cached built-in add-on package is incomplete"
                }
                staging!!.deleteRecursively()
                staging = null
            }

            val installed = InstalledAddon(
                id = installId,
                repository = "builtin:$assetPath",
                displayName = addon.name,
                commitSha = identity,
                archiveSha256 = digest,
                installedAtEpochMs = System.currentTimeMillis(),
                packagePath = packageRelative,
                folders = folders,
            )
            val current = loadRegistryStrict(registry).filterNot { it.id == installId } + installed
            token.beginCommit()
            mutableState.value = mutableState.value.copy(
                operation = AddonOperation(AddonStage.INSTALLING, addon.name, cancellable = false),
            )
            var publishedNewPackage = false
            try {
                staging?.let { prepared ->
                    finalPackage.parentFile!!.mkdirs()
                    check(prepared.renameTo(finalPackage)) { "Built-in add-on package could not be published" }
                    publishedNewPackage = true
                    staging = null
                }
                publishRegistry(current.sortedBy { it.displayName.lowercase(Locale.ROOT) })
            } catch (failure: Throwable) {
                if (publishedNewPackage) finalPackage.deleteRecursively()
                throw failure
            }
            runCatching { prunePackages(loadRegistry(registry), loadRegistry(previousRegistry)) }
            val profileFailure = reconcileVanillaConsolePort(loadRegistryStrict(registry))
            mutableState.value = AddonCatalogState(
                installed = loadRegistryStrict(registry),
                notice = if (profileFailure == null) {
                    "Android Port and its matching Winlator controls are ready for the next game launch."
                } else {
                    "Android Port installed, but its matching controller preset could not be selected."
                },
                errorTitle = profileFailure?.let { "Android Port controls need attention" },
                error = profileFailure,
            )
        } finally {
            staging?.takeIf { it.exists() }?.deleteRecursively()
        }
    }

    private fun installVoiceOverLocked(resolved: ResolvedGitHub, token: AddonOperationToken) {
        val assets = resolveVoiceOverAssets(resolved, token)
        ensureVoiceOverStorage(assets.player.size + assets.data.size)

        val playerArchive = File(scratch, "voiceover-player-${System.nanoTime()}.zip")
        val dataArchive = File(scratch, "voiceover-data-${System.nanoTime()}.zip")
        var staging: File? = null
        try {
            download(
                assets.player.url,
                playerArchive,
                "${resolved.ref.slug} (1.12 add-on)",
                token,
                maxBytes = VoiceOverReleaseResolver.MAX_PLAYER_BYTES,
                expectedBytes = assets.player.size,
            )
            download(
                assets.data.url,
                dataArchive,
                "${resolved.ref.slug} (Vanilla sounds)",
                token,
                maxBytes = VoiceOverReleaseResolver.MAX_DATA_BYTES,
                expectedBytes = assets.data.size,
            )
            token.checkpoint()
            mutableState.value = mutableState.value.copy(
                operation = AddonOperation(AddonStage.VALIDATING, resolved.ref.slug),
            )
            val player = validator.validate(
                playerArchive,
                VoiceOverReleaseResolver.DISPLAY_NAME,
                policy = AddonArchiveValidator.Policy.VOICEOVER_PLAYER,
                checkpoint = token::checkpoint,
            )
            val data = validator.validate(
                dataArchive,
                "${VoiceOverReleaseResolver.DISPLAY_NAME} Vanilla sounds",
                policy = AddonArchiveValidator.Policy.VOICEOVER_DATA,
                checkpoint = token::checkpoint,
            )
            val folders = (player.addonFolders + data.addonFolders)
                .distinctBy { it.lowercase(Locale.ROOT) }
            require(folders.map { it.lowercase(Locale.ROOT) }.toSet() == setOf(
                VoiceOverReleaseResolver.PLAYER_FOLDER.lowercase(Locale.ROOT),
                VoiceOverReleaseResolver.DATA_FOLDER.lowercase(Locale.ROOT),
            )) { "VoiceOver release did not contain both required add-on folders" }

            val playerDigest = sha256(playerArchive, token)
            val dataDigest = sha256(dataArchive, token)
            val digest = sha256("$playerDigest\n$dataDigest")
            val packageRelative = "packages/${resolved.ref.id}/${assets.remoteIdentity}"
            val finalPackage = File(root, packageRelative)
            if (!finalPackage.isDirectory) {
                staging = File(packages, ".staging-${resolved.ref.id}-${System.nanoTime()}")
                check(staging.mkdirs()) { "VoiceOver staging directory could not be created" }
                extractor.extract(playerArchive, player, staging, token::checkpoint)
                extractor.extract(dataArchive, data, staging, token::checkpoint)
            } else {
                require(folders.all { File(finalPackage, it).isDirectory }) {
                    "The cached VoiceOver package is incomplete"
                }
            }
            token.checkpoint()
            mutableState.value = mutableState.value.copy(
                operation = AddonOperation(AddonStage.INSTALLING, resolved.ref.slug),
            )
            val installed = InstalledAddon(
                id = resolved.ref.id,
                repository = "https://github.com/${resolved.ref.slug}",
                displayName = VoiceOverReleaseResolver.DISPLAY_NAME,
                commitSha = assets.remoteIdentity,
                archiveSha256 = digest,
                installedAtEpochMs = System.currentTimeMillis(),
                packagePath = packageRelative,
                folders = folders,
            )
            val current = loadRegistryStrict(registry).filterNot { it.id == installed.id } + installed
            token.beginCommit()
            mutableState.value = mutableState.value.copy(
                operation = AddonOperation(AddonStage.INSTALLING, resolved.ref.slug, cancellable = false),
            )
            var publishedNewPackage = false
            try {
                staging?.let { prepared ->
                    finalPackage.parentFile!!.mkdirs()
                    check(prepared.renameTo(finalPackage)) { "Validated VoiceOver package could not be published" }
                    publishedNewPackage = true
                    staging = null
                }
                publishRegistry(current.sortedBy { it.displayName.lowercase(Locale.ROOT) })
            } catch (failure: Throwable) {
                if (publishedNewPackage) finalPackage.deleteRecursively()
                throw failure
            }
            runCatching { prunePackages(loadRegistry(registry), loadRegistry(previousRegistry)) }
            mutableState.value = AddonCatalogState(
                installed = loadRegistryStrict(registry),
                notice = "WoW VoiceOver and Vanilla sounds are ready for the next game launch.",
            )
        } finally {
            staging?.takeIf { it.exists() }?.deleteRecursively()
            playerArchive.delete()
            dataArchive.delete()
        }
    }

    private fun resolveVoiceOverAssets(
        resolved: ResolvedGitHub,
        token: AddonOperationToken,
    ): VoiceOverReleaseResolver.Resolved {
        val latestRelease = requireNotNull(resolved.latestRelease) {
            "VoiceOver does not have a current GitHub release"
        }
        return VoiceOverReleaseResolver.resolve(latestRelease) { tag ->
            getJson(
                "https://api.github.com/repos/${resolved.ref.owner}/${resolved.ref.repo}/releases/tags/$tag",
                token,
            )
        }
    }

    private data class ResolvedGitHub(
        val ref: GitHubRepoRef,
        val commit: String,
        val latestRelease: JSONObject?,
    )

    /**
     * Keep the optional Vanilla controller addon and its native key preset in
     * sync. InputProfileStore compare-and-restores the prior profile only when
     * the automatically applied preset was not subsequently customized.
     */
    private fun reconcileVanillaConsolePort(installed: List<InstalledAddon>): String? {
        val failure = runCatching {
            val store = InputProfileStore(appContext)
            if (installed.any { it.id == VanillaConsolePortPackage.INSTALL_ID }) {
                store.enableVanillaConsolePort()
            } else if (store.hasManagedVanillaConsolePort()) {
                store.disableVanillaConsolePort()
            }
        }.exceptionOrNull() ?: return null
        return failure.message ?: "The matching control preset could not be persisted."
    }

    private fun resolveGitHub(ref: GitHubRepoRef, token: AddonOperationToken): ResolvedGitHub {
        val repository = getJson("https://api.github.com/repos/${ref.owner}/${ref.repo}", token)
        val canonical = GitHubRepoRef.parse(repository.getString("html_url"))
        val defaultBranch = repository.getString("default_branch")
        val latestRelease = getOptionalJson(
            "https://api.github.com/repos/${canonical.owner}/${canonical.repo}/releases/latest",
            token,
        )
        val releaseRef = latestRelease?.optString("tag_name")?.takeIf { it.isNotBlank() } ?: defaultBranch
        val commit = getJson(
            "https://api.github.com/repos/${canonical.owner}/${canonical.repo}/commits/$releaseRef",
            token,
        ).getString("sha").lowercase(Locale.ROOT)
        require(Regex("^[0-9a-f]{40}$").matches(commit)) { "GitHub returned an invalid commit identity" }
        return ResolvedGitHub(canonical, commit, latestRelease)
    }

    private fun getJson(url: String, token: AddonOperationToken): JSONObject = execute(url, token) { response ->
        checkResponse(response, url)
        token.checkpoint()
        JSONObject(response.body.string()).also { token.checkpoint() }
    }

    private fun getOptionalJson(url: String, token: AddonOperationToken): JSONObject? = execute(url, token) { response ->
        if (response.code == 404) return@execute null
        checkResponse(response, url)
        token.checkpoint()
        JSONObject(response.body.string()).also { token.checkpoint() }
    }

    private fun <T> execute(
        initialUrl: String,
        token: AddonOperationToken,
        consume: (Response) -> T,
    ): T {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirect ->
            token.checkpoint()
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host in ALLOWED_HOSTS) { "GitHub redirected to an untrusted host" }
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "PocketRealm-AddonManager/1")
                .build()
            val call = client.newCall(request)
            token.attach(call)
            val response = try {
                call.execute()
            } catch (failure: Throwable) {
                token.detach(call)
                token.checkpoint()
                throw failure
            }
            if (response.code !in 300..399) {
                try {
                    token.checkpoint()
                    return response.use(consume)
                } catch (failure: Throwable) {
                    token.checkpoint()
                    throw failure
                } finally {
                    response.close()
                    token.detach(call)
                }
            }
            val location = response.header("Location")
            response.close()
            token.detach(call)
            token.checkpoint()
            require(redirect < MAX_REDIRECTS && !location.isNullOrBlank()) { "GitHub redirect limit exceeded" }
            url = uri.resolve(location).toString()
        }
        error("GitHub redirect limit exceeded")
    }

    private fun download(
        url: String,
        destination: File,
        slug: String,
        token: AddonOperationToken,
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
        expectedBytes: Long? = null,
    ) {
        execute(url, token) { response ->
            checkResponse(response, url)
            val declared = response.body.contentLength().takeIf { it >= 0 }
            require(declared == null || declared <= maxBytes) { "Add-on download is larger than supported" }
            require(expectedBytes == null || declared == null || declared == expectedBytes) {
                "GitHub release asset size changed during download"
            }
            var copied = 0L
            response.body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        token.checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= maxBytes) { "Add-on download exceeded its supported size" }
                        output.write(buffer, 0, count)
                        mutableState.value = mutableState.value.copy(
                            operation = AddonOperation(AddonStage.DOWNLOADING, slug, copied, declared),
                        )
                    }
                    token.checkpoint()
                    output.fd.sync()
                }
            }
            require(expectedBytes == null || copied == expectedBytes) {
                "GitHub release asset size changed during download"
            }
        }
    }

    private fun ensureVoiceOverStorage(assetBytes: Long) {
        val reserve = 512L * 1024 * 1024
        val required = Math.addExact(Math.multiplyExact(assetBytes, 3L), reserve)
        require(root.usableSpace >= required) {
            val gib = 1024L * 1024 * 1024
            val roundedGiB = (required + gib - 1) / gib
            "WoW VoiceOver requires at least $roundedGiB GiB free for safe installation and activation"
        }
    }

    private fun checkResponse(response: Response, url: String) {
        if (response.isSuccessful) return
        val remaining = response.header("X-RateLimit-Remaining")
        val reset = response.header("X-RateLimit-Reset")
        when {
            response.code == 403 || response.code == 429 ->
                error("GitHub rate limit reached${reset?.let { "; reset time $it" }.orEmpty()}")
            response.code == 404 -> error("GitHub repository or release was not found")
            else -> error("GitHub request failed (${response.code}) for ${URI(url).path}; remaining=${remaining ?: "unknown"}")
        }
    }

    private fun publishRegistry(installed: List<InstalledAddon>) {
        registryPublisher.publish(registryJson(installed))
    }

    private fun registryJson(installed: List<InstalledAddon>): String =
        JSONObject().put("schema", 1).put("installed", JSONArray().apply {
            installed.forEach { addon ->
                put(JSONObject()
                    .put("id", addon.id)
                    .put("repository", addon.repository)
                    .put("displayName", addon.displayName)
                    .put("commitSha", addon.commitSha)
                    .put("archiveSha256", addon.archiveSha256)
                    .put("installedAtEpochMs", addon.installedAtEpochMs)
                    .put("packagePath", addon.packagePath)
                    .put("folders", JSONArray(addon.folders)))
            }
        }).toString(2)

    private fun retireRemovedProducts() {
        for (file in listOf(registry, previousRegistry)) {
            if (!file.isFile) continue
            val current = loadRegistryStrict(file)
            val retained = current.filterNot(::isRetiredProduct)
            if (retained.size != current.size) writeAtomic(file, registryJson(retained))
        }
        runCatching { prunePackages(loadRegistry(registry), loadRegistry(previousRegistry)) }
    }

    private fun isRetiredProduct(addon: InstalledAddon): Boolean =
        addon.id in RETIRED_PRODUCT_INSTALL_IDS || addon.folders.any { folder ->
            folder.equals("PocketRealmPad", ignoreCase = true) ||
                folder.equals("PocketRealmPadLauncher", ignoreCase = true)
        }

    private fun loadRegistry(file: File): List<InstalledAddon> =
        runCatching { loadRegistryStrict(file) }.getOrElse { emptyList() }

    private fun loadRegistryStrict(file: File): List<InstalledAddon> {
        if (!file.isFile) return emptyList()
        val rootJson = JSONObject(file.readText())
        require(rootJson.getInt("schema") == 1)
        val array = rootJson.getJSONArray("installed")
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            InstalledAddon(
                id = id,
                repository = item.getString("repository"),
                displayName = if (id == VanillaConsolePortPackage.INSTALL_ID) {
                    VanillaConsolePortPackage.DISPLAY_NAME
                } else {
                    item.getString("displayName")
                },
                commitSha = item.getString("commitSha"),
                archiveSha256 = item.getString("archiveSha256"),
                installedAtEpochMs = item.getLong("installedAtEpochMs"),
                packagePath = item.getString("packagePath"),
                folders = item.getJSONArray("folders").let { folders ->
                    List(folders.length()) { folders.getString(it) }
                },
            ).also { require(File(root, it.packagePath).isDirectory) }
        }
    }

    private fun prunePackages(active: List<InstalledAddon>, previous: List<InstalledAddon>) {
        val keep = (active + previous).map { File(root, it.packagePath).canonicalPath }.toSet()
        packages.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".staging-") }
            .forEach { owner ->
                owner.listFiles().orEmpty().filter { it.isDirectory && it.canonicalPath !in keep }
                    .forEach { it.deleteRecursively() }
                if (owner.listFiles().isNullOrEmpty()) owner.delete()
            }
    }

    private fun copyBuiltInAssetTree(assetRoot: String, destination: File, token: AddonOperationToken) {
        var fileCount = 0
        var totalBytes = 0L
        fun copy(path: String, target: File) {
            token.checkpoint()
            val children = appContext.assets.list(path)?.sorted().orEmpty()
            if (children.isNotEmpty()) {
                check(target.mkdirs() || target.isDirectory) { "Built-in add-on directory could not be created" }
                children.forEach { name ->
                    require(name.matches(Regex("[A-Za-z0-9_. -]+")) && name != "." && name != "..") {
                        "Built-in add-on contains an unsafe asset name"
                    }
                    copy("$path/$name", File(target, name))
                }
                return
            }
            fileCount += 1
            require(fileCount <= MAX_BUILTIN_FILES) { "Built-in add-on contains too many files" }
            target.parentFile!!.mkdirs()
            appContext.assets.open(path).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        token.checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        require(totalBytes <= MAX_BUILTIN_BYTES) { "Built-in add-on is larger than supported" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        }
        copy(assetRoot, destination)
        require(fileCount > 0) { "Built-in add-on assets are empty" }
    }

    private fun validateBuiltInPackage(packageRoot: File): List<String> {
        val folders = packageRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }
        require(folders == listOf(VanillaConsolePortPackage.ADDON_FOLDER)) {
            "Built-in Android Port package has an unexpected folder layout"
        }
        require(packageRoot.listFiles().orEmpty().none { it.isFile }) {
            "Built-in Android Port package contains files outside its add-on folder"
        }
        val addon = File(packageRoot, VanillaConsolePortPackage.ADDON_FOLDER)
        val toc = File(addon, "${VanillaConsolePortPackage.ADDON_FOLDER}.toc")
        require(toc.isFile && Regex("""(?m)^## Interface:\s*11200\s*$""").containsMatchIn(toc.readText())) {
            "Built-in Android Port is not an Interface 11200 add-on"
        }
        addon.walkTopDown().filter { it.isFile }.forEach { file ->
            require(file.extension.lowercase(Locale.ROOT) in BUILTIN_ALLOWED_EXTENSIONS) {
                "Built-in Android Port contains a forbidden file type: ${file.name}"
            }
        }
        return folders
    }

    private fun sha256Tree(root: File, token: AddonOperationToken): String {
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown().filter { it.isFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                token.checkpoint()
                digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                digest.update(byteArrayOf(0))
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256AssetTree(assetRoot: String, token: AddonOperationToken): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = mutableListOf<String>()
        fun collect(path: String) {
            token.checkpoint()
            val children = appContext.assets.list(path)?.sorted().orEmpty()
            if (children.isEmpty()) files += path else children.forEach { collect("$path/$it") }
        }
        collect(assetRoot)
        require(files.isNotEmpty()) { "Built-in Android Port assets are empty" }
        files.sorted().forEach { path ->
            token.checkpoint()
            val relative = path.removePrefix("$assetRoot/")
            digest.update(relative.toByteArray(Charsets.UTF_8))
            digest.update(byteArrayOf(0))
            appContext.assets.open(path).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeAtomic(destination: File, content: String) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Os.rename(temp.absolutePath, destination.absolutePath)
    }

    private fun sha256(file: File, token: AddonOperationToken): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                token.checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        token.checkpoint()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val ALLOWED_HOSTS = setOf(
            "api.github.com", "github.com", "codeload.github.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
        private const val MAX_REDIRECTS = 3
        private const val MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024
        private const val MAX_BUILTIN_BYTES = 8L * 1024 * 1024
        private const val MAX_BUILTIN_FILES = 512
        private val BUILTIN_ALLOWED_EXTENSIONS = setOf("lua", "toc", "xml", "md", "txt", "tga", "blp")
        private val RETIRED_PRODUCT_INSTALL_IDS = setOf(
            "bundled__pocketrealmpad",
            // Short-lived remote prototype replaced by the clean-room built-in.
            "pepordev__consoleexperienceclassic",
        )
        @Volatile private var instance: AddonRepository? = null

        fun get(context: Context): AddonRepository = instance ?: synchronized(this) {
            instance ?: AddonRepository(context).also { instance = it }
        }
    }
}
