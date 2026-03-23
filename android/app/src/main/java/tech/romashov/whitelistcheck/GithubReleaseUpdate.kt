package tech.romashov.whitelistcheck

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class GithubReleaseInfo(
    val tagName: String,
    val version: SemVer,
    val apkDownloadUrl: String,
    val apkFileName: String,
)

object GithubReleaseUpdate {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun latestReleaseUrl(owner: String, repo: String): String =
        "https://api.github.com/repos/$owner/$repo/releases/latest"

    suspend fun fetchLatestRelease(owner: String, repo: String): Result<GithubReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = latestReleaseUrl(owner, repo)
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "tech.romashov.whitelistcheck")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("GitHub API ${response.code}: ${body.take(200)}")
                    }
                    parseReleaseJson(body)
                }
            }
        }

    private fun parseReleaseJson(json: String): GithubReleaseInfo {
        val root = JSONObject(json)
        val tagName = root.optString("tag_name").ifBlank { error("empty tag_name") }
        val version = SemVer.parse(tagName) ?: error("bad tag: $tagName")
        val assets = root.optJSONArray("assets") ?: error("no assets")
        var bestUrl: String? = null
        var bestName: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val download = a.optString("browser_download_url")
            if (download.isBlank()) continue
            if (name.startsWith("white-list-check-", ignoreCase = true) || bestUrl == null) {
                bestUrl = download
                bestName = name
                if (name.startsWith("white-list-check-", ignoreCase = true)) break
            }
        }
        val url = bestUrl ?: error("no .apk in release")
        val fileName = bestName ?: "update.apk"
        return GithubReleaseInfo(tagName, version, url, fileName)
    }

    suspend fun downloadApk(
        downloadUrl: String,
        targetFile: File,
        onProgress: suspend (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "tech.romashov.whitelistcheck")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("download HTTP ${response.code}")
                    val body = response.body ?: error("empty body")
                    val total = body.contentLength().takeIf { it >= 0L } ?: -1L
                    withContext(Dispatchers.Main) { onProgress(0L, total) }
                    var read = 0L
                    var lastEmit = 0L
                    val emitStep = 64 * 1024L
                    val buffer = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        targetFile.outputStream().use { output ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n == -1) break
                                output.write(buffer, 0, n)
                                read += n.toLong()
                                if (read - lastEmit >= emitStep || (total >= 0L && read >= total)) {
                                    lastEmit = read
                                    withContext(Dispatchers.Main) { onProgress(read, total) }
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onProgress(read, total) }
                }
            }
        }

    fun installIntent(context: Context, apkFile: File): Intent {
        val authority = "${context.packageName}.apkfileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                clipData = android.content.ClipData.newRawUri("apk", uri)
            }
        }
    }
}
