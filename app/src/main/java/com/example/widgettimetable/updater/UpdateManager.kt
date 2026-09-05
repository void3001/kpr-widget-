package com.example.widgettimetable.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class RemoteUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val apkUrl: String,
    val releaseDate: String,
    val changelog: List<String>
)

object UpdateManager {
    const val CURRENT_VERSION_NAME = "v1.6"
    const val CURRENT_VERSION_CODE = 7

    const val UPDATE_MANIFEST_URL = "https://raw.githubusercontent.com/void3001/kpr-widget-/main/version.json"

    val FALLBACK_CHANGELOG = listOf(
        "- Widget period time display above each period (e.g. 8:55 AM - 9:50 AM)",
        "- Full subject name in widget with no ellipsis truncation (no '....')",
        "- Removed circles around widget navigation arrows",
        "- Hidden task arrows when no tasks are available",
        "- Fixed course code wrapping and truncated subject names",
        "- Visual differentiation for breaks with warm amber tint and BREAK badge",
        "- Trash can icon button for period deletion",
        "- Fixed top card clipping and auto-scrolled day selector tabs"
    )

    suspend fun checkRemoteUpdate(): Result<RemoteUpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val url = URL(UPDATE_MANIFEST_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "WidgetTimetable-Android")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonString)
                val remoteCode = json.optInt("versionCode", CURRENT_VERSION_CODE)
                val remoteName = json.optString("versionName", CURRENT_VERSION_NAME)
                val minSupported = json.optInt("minSupportedVersionCode", 1)
                val apkUrl = json.optString("apkUrl", "")
                val releaseDate = json.optString("releaseDate", "")
                val changelogJson = json.optJSONArray("changelog")
                val changelogList = mutableListOf<String>()
                if (changelogJson != null) {
                    for (i in 0 until changelogJson.length()) {
                        changelogList.add(changelogJson.getString(i))
                    }
                }

                val updateInfo = RemoteUpdateInfo(
                    versionCode = remoteCode,
                    versionName = remoteName,
                    minSupportedVersionCode = minSupported,
                    apkUrl = apkUrl,
                    releaseDate = releaseDate,
                    changelog = if (changelogList.isNotEmpty()) changelogList else FALLBACK_CHANGELOG
                )

                if (remoteCode > CURRENT_VERSION_CODE) {
                    Result.success(updateInfo)
                } else {
                    Result.success(null) // Up to date
                }
            } else {
                Result.failure(Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val url = URL(apkUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK && connection.responseCode != HttpURLConnection.HTTP_MOVED_PERM && connection.responseCode != HttpURLConnection.HTTP_MOVED_TEMP) {
                withContext(Dispatchers.Main) {
                    onError("Failed to download update: Server returned code ${connection.responseCode}")
                }
                return@withContext
            }

            val fileLength = connection.contentLength
            val destinationDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            val destinationFile = File(destinationDir, "widget_timetable_update.apk")
            if (destinationFile.exists()) destinationFile.delete()

            connection.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val data = ByteArray(4096)
                    var total: Long = 0
                    var count: Int
                    while (input.read(data).also { count = it } != -1) {
                        total += count
                        if (fileLength > 0) {
                            val progress = total.toFloat() / fileLength
                            withContext(Dispatchers.Main) { onProgress(progress) }
                        }
                        output.write(data, 0, count)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                onComplete()
                openApkInstaller(context, destinationFile)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError("Download error: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun openApkInstaller(context: Context, apkFile: File) {
        if (!apkFile.exists()) return

        val intent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        context.startActivity(intent)
    }
}
