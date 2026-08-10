package com.pocketrealm.client

/** Closed, APK-installed renderer packages. No path or URL is user supplied. */
data class RendererPackage(
    val id: String,
    val translator: ArmTranslationBackend,
    val label: String,
    val dxvkVersion: String,
    val turnipVersion: String,
    val buildId: String,
    val qualification: String,
    val system32Asset: String? = null,
    val system32Sha256: String? = null,
    val syswow64Asset: String? = null,
    val syswow64Sha256: String? = null,
) {
    init {
        require(ID.matches(id)) { "invalid renderer package id" }
        val paths = listOfNotNull(system32Asset, syswow64Asset)
        require(paths.all { it.startsWith("arm-translated/renderer-packages/") && ".." !in it })
        require(listOfNotNull(system32Sha256, syswow64Sha256).all(SHA256::matches))
        require((system32Asset == null) == (system32Sha256 == null))
        require((syswow64Asset == null) == (syswow64Sha256 == null))
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9.-]{2,63}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

object RendererPackageCatalog {
    const val BOX64_DEFAULT = "box64-dxvk-2.4.1"
    const val BOX64_LEGACY = "box64-dxvk-1.10.3"
    const val FEX_DEFAULT = "fex-dxvk-2.3.1-arm64ec"

    private val packages = listOf(
        RendererPackage(
            id = BOX64_DEFAULT,
            translator = ArmTranslationBackend.BOX64,
            label = "DXVK 2.4.1 · Turnip 26.1",
            dxvkVersion = "2.4.1",
            turnipVersion = "26.1.0",
            buildId = "turnip-26.1.0-dxvk-2.4.1-d3d9",
            qualification = "Current RP6 test package",
            system32Asset = "arm-translated/renderer-packages/$BOX64_DEFAULT/system32/d3d9.dll",
            system32Sha256 = "216058f9320d0667d551f4cea840ee539396449ef8c8e89fe481e4f0ddb628ae",
            syswow64Asset = "arm-translated/renderer-packages/$BOX64_DEFAULT/syswow64/d3d9.dll",
            syswow64Sha256 = "cc556331fc3388989749620bceead4c2da95c3932ed38cf5cc24f3f0a878866e",
        ),
        RendererPackage(
            id = BOX64_LEGACY,
            translator = ArmTranslationBackend.BOX64,
            label = "DXVK 1.10.3 · Turnip 26.1",
            dxvkVersion = "1.10.3",
            turnipVersion = "26.1.0",
            buildId = "turnip-26.1.0-dxvk-1.10.3-d3d9",
            qualification = "Legacy comparison package",
            system32Asset = "arm-translated/renderer-packages/$BOX64_LEGACY/system32/d3d9.dll",
            system32Sha256 = "7129d7e67b9abb06608fe1c30bec4c7a7c7f0649198e39425cd7ef322569c383",
            syswow64Asset = "arm-translated/renderer-packages/$BOX64_LEGACY/syswow64/d3d9.dll",
            syswow64Sha256 = "b6cfa2cd62af73b80d461085d126004b0e22dd3944c9246c58e3a68e747b56b6",
        ),
        RendererPackage(
            id = FEX_DEFAULT,
            translator = ArmTranslationBackend.FEX,
            label = "DXVK 2.3.1 ARM64EC · Turnip 26.2",
            dxvkVersion = "2.3.1-arm64ec-gplasync",
            turnipVersion = "26.2.0",
            buildId = "turnip-26.2.0-dxvk-2.3.1-arm64ec",
            qualification = "Built; live qualification pending",
        ),
    )
    private val byId = packages.associateBy(RendererPackage::id)

    fun compatible(translator: ArmTranslationBackend): List<RendererPackage> =
        packages.filter { it.translator == translator }

    fun default(translator: ArmTranslationBackend): RendererPackage = requireNotNull(
        byId[if (translator == ArmTranslationBackend.FEX) FEX_DEFAULT else BOX64_DEFAULT],
    )

    fun find(id: String?): RendererPackage? = id?.let(byId::get)

    /** Resolve a persisted preference, falling back to the compatible default. */
    fun resolve(translator: ArmTranslationBackend, renderer: String, requestedId: String?): RendererPackage? {
        if (renderer == "opengl") return null
        require(renderer == "dxvk") { "unsupported ARM renderer: $renderer" }
        return find(requestedId).takeIf { it?.translator == translator } ?: default(translator)
    }

    /** Resolve a control-protocol request without silently changing its identity. */
    fun requireForRequest(
        translator: ArmTranslationBackend,
        renderer: String,
        requestedId: String?,
    ): RendererPackage? {
        if (renderer == "opengl") {
            require(requestedId == null) { "OpenGL does not accept a DXVK package" }
            return null
        }
        require(renderer == "dxvk") { "unsupported ARM renderer: $renderer" }
        requireNotNull(requestedId) { "DXVK requires an explicit renderer package" }
        val selected = requireNotNull(find(requestedId)) {
            "unknown renderer package: $requestedId"
        }
        require(selected.translator == translator) {
            "renderer package $requestedId is not compatible with ${translator.id}"
        }
        return selected
    }

    fun normalize(translator: ArmTranslationBackend, requestedId: String?): String =
        resolve(translator, "dxvk", requestedId)!!.id

    fun runtimeGeneration(
        translator: ArmTranslationBackend,
        renderer: String,
        requestedId: String?,
    ): String = requireForRequest(translator, renderer, requestedId)?.id ?: "opengl"
}
