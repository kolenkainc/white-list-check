package tech.romashov.whitelistcheck

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class IpEndpointClient(
    connectTimeoutSec: Int,
    readTimeoutSec: Int,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec.toLong(), TimeUnit.SECONDS)
        .writeTimeout(connectTimeoutSec.toLong(), TimeUnit.SECONDS)
        .build()

    fun fetchAndParseIp(url: String): Result<String> {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
            .header("User-Agent", "WhiteListCheck/1.0 (Android)")
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                parseIpFromBody(body.trim())
                    ?: error("В ответе нет IPv4. Задайте на сервере JSON вида {\"ip\":\"1.2.3.4\"} или текст с адресом.")
            }
        }
    }

    private fun parseIpFromBody(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            runCatching {
                val o = JSONObject(trimmed)
                listOf("ip", "address", "target", "host").forEach { key ->
                    o.optString(key).takeIf { it.isNotBlank() }?.let { extractFirstIpv4(it) }?.let { return it }
                }
            }
        }
        return extractFirstIpv4(trimmed)
    }

    private fun extractFirstIpv4(text: String): String? {
        val m = IPV4.matcher(text)
        while (m.find()) {
            val candidate = m.group()
            if (isPlausibleIpv4(candidate)) return candidate
        }
        return null
    }

    private fun isPlausibleIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    companion object {
        private val IPV4 = Pattern.compile(
            "(?<![0-9])(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(?![0-9])",
        )
    }
}
