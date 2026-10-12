/*
 * ************************************************************************
 *  AutoUpdate.kt
 * *************************************************************************
 * Copyright © 2024 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.util

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.videolan.tools.KEY_LAST_UPDATE_TIME
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.getUpdateUri
import java.io.File
import java.io.IOException
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit


object AutoUpdate {
    private const val TAG = "AutoUpdate"

    private fun releaseVersion(value: String): List<Int>? {
        val match = Regex("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)$").matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    private fun compareVersions(left: List<Int>, right: List<Int>): Int {
        for (index in left.indices) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return 0
    }

    /**
     * Checks for a newer SyncNuke GitHub release with a compatible APK
     *
     * @param context [Application] used to get the settings
     * @param skipChecks If true, bypass the six-hour check interval
     * @param listener Function called when an update is found
     */
    suspend fun checkUpdate(context: Application, skipChecks:Boolean = false, listener: (String, Date) -> Unit) = withContext(Dispatchers.IO) {
        //limit to debug builds (nightlies are included)
        if (!BuildConfig.DEBUG && !skipChecks) return@withContext

        //check if last update is older than 6 hours

        val settings = Settings.getInstance(context)
        if (!skipChecks && settings.getLong(KEY_LAST_UPDATE_TIME, 0L) > System.currentTimeMillis() - 6 * 3600000) {
            Log.i(TAG, "Last update is less than 6 hours")
            return@withContext
        }
        settings.putSingle(KEY_LAST_UPDATE_TIME, System.currentTimeMillis())

        try {
            val arch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS[0]
            } else {
                Build.CPU_ABI
            }
            val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build()
            val request = Request.Builder()
                .url("https://api.github.com/repos/if-shouldrs/syncnuke-android/releases?per_page=100")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Release check failed: ${response.code}")
                val releases = JSONArray(response.body?.string() ?: return@withContext)
                val currentVersion = releaseVersion(BuildConfig.VLC_VERSION_NAME) ?: return@withContext
                var newestVersion = currentVersion
                var updateUrl: String? = null
                var updateDate = Date(0)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                for (index in 0 until releases.length()) {
                    val release = releases.getJSONObject(index)
                    if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                    val version = releaseVersion(release.optString("tag_name")) ?: continue
                    if (compareVersions(version, newestVersion) <= 0) continue
                    val prefix = "SyncNuke-Android-${version.joinToString(".")}" +
                            (if (BuildConfig.DEBUG) "-debug" else "")
                    val assets = release.optJSONArray("assets") ?: continue
                    var matchingUrl: String? = null
                    for (assetIndex in 0 until assets.length()) {
                        val asset = assets.getJSONObject(assetIndex)
                        val name = asset.optString("name")
                        if (name == "$prefix-$arch.apk" ||
                            (matchingUrl == null && name == "$prefix-all.apk")) {
                            val url = asset.optString("browser_download_url")
                            if (url.startsWith("https://github.com/if-shouldrs/syncnuke-android/releases/download/")) {
                                matchingUrl = url
                            }
                        }
                    }
                    if (matchingUrl != null) {
                        newestVersion = version
                        updateUrl = matchingUrl
                        updateDate = dateFormat.parse(release.getString("published_at")) ?: Date(0)
                    }
                }
                updateUrl?.let { url ->
                    withContext(Dispatchers.Main) { listener.invoke(url, updateDate) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Downloads the update and installs it
     *
     * @param context [Application] used for downloading and installing the update
     * @param updateURL URL of the update
     * @param loading Function called when the update is downloading
     */
    suspend fun downloadAndInstall(context: Application, updateURL: String, loading: (Boolean) -> Unit) = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { loading.invoke(true) }
        try {
            download(context, updateURL)
            installAPK(context)
        } catch (e: IOException) {
            Log.e(TAG, "Unable to download SyncNuke update", e)
        } finally {
            withContext(Dispatchers.Main) { loading.invoke(false) }
        }
    }

    /**
     * Downloads the update
     *
     * @param context [Application] used for downloading the update
     * @param url URL of the update
     */
    @Throws(IOException::class)
    private fun download(context: Application, url: String) {
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("APK download failed: ${response.code}")
            val body = response.body ?: throw IOException("Empty APK response")
            File(context.cacheDir, "update.apk").sink().buffer().use { sink ->
                sink.writeAll(body.source())
            }
        }
    }

    /**
     * Installs the update
     *
     * @param context [Application] used for installing the update
     */
    private fun installAPK(context: Application) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(getUpdateUri(), "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, e.message, e)
        }
    }

    suspend fun clean(context: Application) = withContext(Dispatchers.IO) {
        try {
            val downloadedFile = File(context.cacheDir, "update.apk")
            if (downloadedFile.exists()) downloadedFile.delete() else { }
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
        }
    }


}