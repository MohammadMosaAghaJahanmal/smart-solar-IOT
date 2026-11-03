package com.virtualcamerapro.setup

import org.json.JSONArray
import org.json.JSONObject

/** Redacts both structured values and references embedded in camera error messages. */
internal class DiagnosticRedactor(private val privateFilesPath: String) {
    fun sanitize(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().apply {
            value.keys().forEach { key ->
                if (!PRIVATE_KEY.matches(key)) put(key, sanitize(value.opt(key)))
            }
        }
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(sanitize(value.opt(index)))
        }
        is String -> value.replace(MEDIA_REFERENCE, "<media-reference>")
            .replace(privateFilesPath, "<private-files>")
            .replace(SAVED_MEDIA_NAME, "<selected-media>").take(8192)
        else -> value
    }

    companion object {
        private val PRIVATE_KEY = Regex("(?i).*(sourcePath|sourceUri|sourceFile|sourceSha256|sourceHash|sourceDigest|mediaPath|mediaUri|mediaName|runtimeDirectory|selectedMedia|serialNumber|androidId|accessToken|refreshToken|password|apiKey).*")
        private val MEDIA_REFERENCE = Regex("(?i)(?:content|file)://[^\\s\"'<>]+")
        private val SAVED_MEDIA_NAME = Regex("source-[A-Za-z0-9._-]+")
    }
}
