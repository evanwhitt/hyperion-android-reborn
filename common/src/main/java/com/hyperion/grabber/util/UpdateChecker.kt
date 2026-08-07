package com.hyperion.grabber.common.util

import android.util.Log
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class GithubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val downloadUrl: String,
    val publishedAt: String
)

/** Checks the GitHub releases page for a newer build of the app. */
class UpdateChecker {
    private val TAG = "UpdateChecker"
    private val GITHUB_API_URL = "https://api.github.com/repos/evanwhitt/hyperion-android-reborn/releases"

    /** Returns the newest release that has an APK and is newer than [currentVersion], or null. */
    fun checkForUpdates(currentVersion: String): GithubRelease? {
        return try {
            fetchReleases()?.let { findNewerRelease(it, currentVersion) }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            null
        }
    }

    private fun fetchReleases(): JSONArray? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(GITHUB_API_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "Hyperion-Android-Reborn")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()
            return JSONArray(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching releases", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun findNewerRelease(releases: JSONArray, currentVersion: String): GithubRelease? {
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft", false)) continue

            val tagName = release.getString("tag_name")
            val downloadUrl = findApkUrl(release)
            if (downloadUrl.isEmpty()) continue

            // Stable installs shouldn't be offered prerelease (beta) builds;
            // if you're already on a beta, newer betas and stable both apply.
            val isPrerelease = release.optBoolean("prerelease", false)
            if (isPrerelease && !currentVersion.lowercase().contains("-beta")
                && !currentVersion.lowercase().contains("-rc")) continue

            // "latest" (the CI auto-build) has no real version, so it can never be
            // "newer" than a normal tagged release.
            if (isNewerVersion(tagName, currentVersion)) {
                return GithubRelease(
                    tagName,
                    release.optString("name", tagName),
                    release.optString("body", ""),
                    downloadUrl,
                    release.optString("published_at", "")
                )
            }
        }
        return null
    }

    private fun findApkUrl(release: org.json.JSONObject): String {
        val assets = release.optJSONArray("assets") ?: return ""
        for (j in 0 until assets.length()) {
            val asset = assets.getJSONObject(j)
            if (asset.getString("name").endsWith(".apk")) {
                return asset.getString("browser_download_url")
            }
        }
        return ""
    }

    fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        val newParts = newVersion.removePrefix("v").split(".")
        val currentParts = currentVersion.removePrefix("v").split(".")
        for (i in 0 until maxOf(newParts.size, currentParts.size)) {
            val np = newParts.getOrNull(i)?.toIntOrNull() ?: 0
            val cp = currentParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (np > cp) return true
            if (np < cp) return false
        }
        return false
    }
}
