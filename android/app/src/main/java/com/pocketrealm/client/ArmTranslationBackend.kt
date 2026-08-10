package com.pocketrealm.client

/** Selectable implementation behind the ARM translated-Wine provider. */
enum class ArmTranslationBackend(val id: String) {
    BOX64("box64"),
    FEX("fex");

    companion object {
        fun parse(value: String?): ArmTranslationBackend =
            requireNotNull(entries.firstOrNull { it.id == value }) {
                "unsupported ARM translator: ${value ?: "<missing>"}"
            }
    }
}
