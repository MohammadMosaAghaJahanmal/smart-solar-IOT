package com.virtualcamerapro.broker

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal class BrokerFailure(val code: String, message: String, val recovery: String) : IllegalStateException(message)

/** Explicit per-emulator connection, provisioned only by the emulator installer. */
internal data class EmulatorConnection(val url: String, val token: String, val cameraId: String) {
    fun request(path: String, headers: Map<String, String> = emptyMap(), file: File? = null, bytes: ByteArray? = null, post: Boolean = false): JSONObject {
        require(file == null || bytes == null)
        val connection = URL(url + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3000
            connection.readTimeout = if (path == "/activate") 180000 else 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (post || file != null || bytes != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setFixedLengthStreamingMode(file?.length() ?: bytes?.size?.toLong() ?: 0L)
                connection.outputStream.use { output ->
                    if (file != null) file.inputStream().use { it.copyTo(output) }
                    if (bytes != null) output.write(bytes)
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { JSONObject(readBounded(it)) } ?: JSONObject()
            if (status !in 200..299) throw BrokerFailure(response.optString("code", "EMULATOR_HTTP_$status"),
                response.optString("message", "Emulator companion returned HTTP $status"),
                "Check the local emulator companion and its matching private connection configuration.")
            return response
        } finally { connection.disconnect() }
    }

    companion object {
        const val BACKEND = "android_emulator_environment"
        fun read(filesDir: File): EmulatorConnection {
            val file = File(filesDir, "emulator-connection.json")
            if (!file.isFile) throw BrokerFailure("EMULATOR_CONNECTION_MISSING", "The explicit Android Emulator connection is not installed",
                "Run the emulator setup script for this isolated Android virtual device.")
            if (file.length() !in 1..16384) throw BrokerFailure("EMULATOR_CONNECTION_INVALID", "Invalid emulator connection configuration", "Reinstall the private emulator connection configuration.")
            val json = JSONObject(file.readText())
            val url = json.getString("url").trimEnd('/')
            val token = json.getString("token")
            val cameraId = json.getString("cameraId")
            if (url != "http://127.0.0.1:8791" || json.getString("backend") != BACKEND ||
                token.length !in 16..512 || token.any { it <= ' ' || it == '\u007f' } || !cameraId.matches(Regex("[0-9]+"))) {
                throw BrokerFailure("EMULATOR_CONNECTION_INVALID", "The connection must explicitly select the authenticated loopback Android Emulator companion",
                    "Reinstall the private emulator connection configuration.")
            }
            return EmulatorConnection(url, token, cameraId)
        }
        private fun readBounded(input: InputStream): String {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 1024 * 1024) { "Oversized emulator companion response" }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}
