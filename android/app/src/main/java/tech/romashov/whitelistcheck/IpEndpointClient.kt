package tech.romashov.whitelistcheck

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

sealed class FetchNextIpResult {
    data class Ok(val ip: String) : FetchNextIpResult()
    data object NoPending : FetchNextIpResult()
    data class Failure(val message: String) : FetchNextIpResult()
}

class IpEndpointClient(
    connectTimeoutSec: Int,
    readTimeoutSec: Int,
    private val ingestToken: String = "",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec.toLong(), TimeUnit.SECONDS)
        .writeTimeout(connectTimeoutSec.toLong(), TimeUnit.SECONDS)
        .build()

    fun fetchNextIp(url: String): FetchNextIpResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
            .header("User-Agent", "WhiteListCheck/1.0 (Android)")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return FetchNextIpResult.Failure("HTTP ${response.code}")
                }
                when (val parsed = parseStructuredOrLegacy(body.trim())) {
                    is Parsed.NoPending -> FetchNextIpResult.NoPending
                    is Parsed.Ip -> FetchNextIpResult.Ok(parsed.value)
                    is Parsed.LegacyPlain -> {
                        val ip = extractFirstIpv4(parsed.text)
                        if (ip != null && isPlausibleIpv4(ip)) {
                            FetchNextIpResult.Ok(ip)
                        } else {
                            FetchNextIpResult.Failure(
                                "В ответе нет IPv4. Для API ожидается JSON вида {\"ip\":\"1.2.3.4\"} или {\"ip\":null}.",
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            FetchNextIpResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    fun putReachability(url: String, reachable: Boolean): Result<Unit> {
        val json = JSONObject().put("reachable", reachable).toString()
        val body = json.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(url)
            .put(body)
            .apply { addIngestAuthIfSet() }
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .header("User-Agent", "WhiteListCheck/1.0 (Android)")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
            }
        }
    }

    private fun Request.Builder.addIngestAuthIfSet(): Request.Builder {
        val t = ingestToken.trim()
        if (t.isNotEmpty()) header("Authorization", "Bearer $t")
        return this
    }

    private sealed class Parsed {
        data object NoPending : Parsed()
        data class Ip(val value: String) : Parsed()
        data class LegacyPlain(val text: String) : Parsed()
    }

    private fun parseStructuredOrLegacy(body: String): Parsed {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            runCatching {
                val o = JSONObject(trimmed)
                if (o.has("ip")) {
                    if (o.isNull("ip")) return Parsed.NoPending
                    val raw = o.optString("ip", "")
                    extractFirstIpv4(raw)?.takeIf { isPlausibleIpv4(it) }?.let { return Parsed.Ip(it) }
                    if (raw.isNotBlank()) {
                        return Parsed.LegacyPlain(trimmed)
                    }
                    return Parsed.NoPending
                }
                listOf("address", "target", "host").forEach { key ->
                    val v = o.optString(key, "")
                    extractFirstIpv4(v)?.takeIf { isPlausibleIpv4(it) }?.let { return Parsed.Ip(it) }
                }
            }
        }
        return Parsed.LegacyPlain(trimmed)
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
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val IPV4 = Pattern.compile(
            "(?<![0-9])(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(?![0-9])",
        )

        /**
         * Для URL вида …/api/v1/next строит …/api/v1/ips/{ip} для PUT результата проверки.
         * Для произвольных legacy-URL возвращает null (отчёт на сервер не шлём).
         */
        fun reportUrlForNextEndpoint(nextEndpointUrl: String, ip: String): String? {
            val url = nextEndpointUrl.trim().toHttpUrlOrNull() ?: return null
            val segs = url.pathSegments
            if (segs.isEmpty() || segs.last() != "next") return null
            val builder = url.newBuilder()
            builder.removePathSegment(segs.size - 1)
            builder.addPathSegment("ips")
            builder.addPathSegment(ip)
            return builder.build().toString()
        }
    }
}
