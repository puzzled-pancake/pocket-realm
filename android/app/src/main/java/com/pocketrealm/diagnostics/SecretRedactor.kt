package com.pocketrealm.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/** Structured-first redaction with regex defense in depth for support export. */
class SecretRedactor(private val explicitSecrets: Collection<String> = emptyList()) {
    fun redact(value: String): String {
        val structured = runCatching {
            when {
                value.trimStart().startsWith("{") -> scrub(JSONObject(value)).toString()
                value.trimStart().startsWith("[") -> scrub(JSONArray(value)).toString()
                else -> value
            }
        }.getOrDefault(value)
        return regexRedact(structured)
    }

    private fun scrub(source: JSONObject): JSONObject = JSONObject().also { output ->
        for (key in source.keys()) {
            val value = source.opt(key)
            output.put(key, if (SENSITIVE_KEY.containsMatchIn(key)) "<redacted>" else scrubValue(value))
        }
    }

    private fun scrub(source: JSONArray): JSONArray = JSONArray().also { output ->
        for (i in 0 until source.length()) output.put(scrubValue(source.opt(i)))
    }

    private fun scrubValue(value: Any?): Any? = when (value) {
        is JSONObject -> scrub(value)
        is JSONArray -> scrub(value)
        is String -> regexRedact(value)
        else -> value
    }

    private fun regexRedact(input: String): String {
        var value = input
        explicitSecrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { secret ->
            value = value.replace(Regex(Regex.escape(secret), RegexOption.IGNORE_CASE), "<redacted>")
        }
        value = value.replace(CONTENT_URI, "<document-uri>")
            .replace(WINDOWS_PATH, "<private-path>")
            .replace(ANDROID_PATH, "<private-path>")
            .replace(EMAIL, "<account>")
        value = IPV4.replace(value) { match ->
            if (match.value.startsWith("127.")) match.value else "<ip>"
        }
        return value
    }

    companion object {
        private val SENSITIVE_KEY = Regex(
            "password|passwd|secret|credential|instance.?token|owner.?token|document.?uri|source.?uri|absolute.?path|android.?account",
            RegexOption.IGNORE_CASE,
        )
        private val CONTENT_URI = Regex("content://[^\\s\\\"']+", RegexOption.IGNORE_CASE)
        private val WINDOWS_PATH = Regex("[A-Za-z]:\\\\[^\\r\\n\\\"']+")
        private val ANDROID_PATH = Regex("/(?:data|storage|sdcard|mnt)/[^\\s\\\"']+")
        private val EMAIL = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        private val IPV4 = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    }
}
