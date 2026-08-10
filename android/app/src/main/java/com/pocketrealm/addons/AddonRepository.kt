package com.pocketrealm.addons

import android.content.Context
import android.system.Os
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
    }
    private val mutableState = MutableStateFlow(AddonCatalogState(installed = loadRegistry(registry)))
    val state: StateFlow<AddonCatalogState> = mutableState.asStateFlow()
    private val operationGuard = Any()
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeToken: AddonOperationToken? = null
    @Volatile private var activeCancelledNotice: String? = null

    fun install(repositoryUrl: String) {
        launchOperation(
            errorTitle = "Could not install add-on",
            cancelledNotice = "Installation cancelled. Installed add-ons were not changed.",
        ) { token ->
            installLocked(GitHubRepoRef.parse(repositoryUrl), token)
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
            mutableState.value = AddonCatalogState(
                installed = loadRegistryStrict(registry),
                notice = "${target.displayName} will be removed the next time the game starts.",
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
            mutableState.value = AddonCatalogState(
                installed = loadRegistryStrict(registry),
                notice = "Previous add-on set restored for the next game launch.",
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
        val repository = getJson("https://api.github.com/repos/${ref.owner}/${ref.repo}", token)
        require(!repository.optBoolean("archived")) { "This GitHub repository is archived" }
        val canonical = GitHubRepoRef.parse(repository.getString("html_url"))
        val defaultBranch = repository.getString("default_branch")
        val releaseRef = getOptionalJson(
            "https://api.github.com/repos/${canonical.owner}/${canonical.repo}/releases/latest",
            token,
        )?.optString("tag_name")?.takeIf { it.isNotBlank() } ?: defaultBranch
        val commit = getJson(
            "https://api.github.com/repos/${canonical.owner}/${canonical.repo}/commits/$releaseRef",
            token,
        ).getString("sha")
        require(Regex("^[0-9a-fA-F]{40}$").matches(commit)) { "GitHub returned an invalid commit identity" }

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
            val validated = validator.validate(archive, canonical.repo, token::checkpoint)
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
                displayName = canonical.repo,
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
            mutableState.value = AddonCatalogState(
                installed = loadRegistryStrict(registry),
                notice = "${installed.displayName} is ready for the next game launch.",
            )
        } finally {
            staging?.takeIf { it.exists() }?.deleteRecursively()
            archive.delete()
        }
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

    private fun download(url: String, destination: File, slug: String, token: AddonOperationToken) {
        execute(url, token) { response ->
            checkResponse(response, url)
            val declared = response.body.contentLength().takeIf { it >= 0 }
            require(declared == null || declared <= MAX_DOWNLOAD_BYTES) { "Addon download is larger than 128 MiB" }
            var copied = 0L
            response.body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        token.checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_DOWNLOAD_BYTES) { "Addon download exceeded 128 MiB" }
                        output.write(buffer, 0, count)
                        mutableState.value = mutableState.value.copy(
                            operation = AddonOperation(AddonStage.DOWNLOADING, slug, copied, declared),
                        )
                    }
                    token.checkpoint()
                    output.fd.sync()
                }
            }
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
        val json = JSONObject().put("schema", 1).put("installed", JSONArray().apply {
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
        })
        registryPublisher.publish(json.toString(2))
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
            InstalledAddon(
                id = item.getString("id"),
                repository = item.getString("repository"),
                displayName = item.getString("displayName"),
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

    companion object {
        private val ALLOWED_HOSTS = setOf(
            "api.github.com", "github.com", "codeload.github.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com",
        )
        private const val MAX_REDIRECTS = 3
        private const val MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024
        @Volatile private var instance: AddonRepository? = null

        fun get(context: Context): AddonRepository = instance ?: synchronized(this) {
            instance ?: AddonRepository(context).also { instance = it }
        }
    }
}
