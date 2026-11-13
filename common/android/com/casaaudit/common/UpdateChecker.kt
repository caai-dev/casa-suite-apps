package com.casaaudit.common

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "New update available" card for the CASA apps, like the Play Store's.
 *
 * Every build publishes versions.json next to the APKs (GitHub release
 * "latest"). The app compares its own versionCode with the one listed for it
 * and, when a newer one exists, asks: Update now / Remind me later.
 * "Update now" downloads the new APK; installing it keeps the user's data.
 * "Remind me later" waits a day before asking again.
 *
 * Only the app shell needs this: the screens inside come from the live web
 * app, so a Publish on Apper reaches everyone without any update.
 */
class UpdateChecker(
    private val activity: Activity,
    private val appKey: String,      // "casa-suite" or "casana" in versions.json
    private val appName: String,
    private val currentCode: Int
) {
    private var lastCheck = 0L

    /** Safe to call on every onResume: checks at most every 6 hours. */
    fun check() {
        val now = System.currentTimeMillis()
        if (now - lastCheck < SIX_HOURS) return
        lastCheck = now
        val prefs = activity.getSharedPreferences("casa_updates", Context.MODE_PRIVATE)
        if (now < prefs.getLong("snoozeUntil", 0L)) return

        Thread {
            try {
                val conn = URL(VERSIONS_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.instanceFollowRedirects = true
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val info = JSONObject(text).getJSONObject(appKey).getJSONObject("android")
                val latest = info.getInt("versionCode")
                val name = info.optString("versionName", "")
                val url = info.getString("url")
                if (latest > currentCode && url.startsWith("https://")) {
                    activity.runOnUiThread { show(name, url) }
                }
            } catch (_: Exception) {
                // Offline or file missing: try again next time.
            }
        }.start()
    }

    private fun show(versionName: String, url: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences("casa_updates", Context.MODE_PRIVATE)
        val version = if (versionName.isNotBlank()) " ($versionName)" else ""
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(
                "A new version of $appName$version is ready.\n\n" +
                    "Tap Update now. When the download finishes, open it and tap Install. " +
                    "Your login and data stay as they are."
            )
            .setPositiveButton("Update now") { _, _ ->
                try {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("Remind me later") { _, _ ->
                prefs.edit().putLong("snoozeUntil", System.currentTimeMillis() + ONE_DAY).apply()
            }
            .setCancelable(true)
            .show()
    }

    companion object {
        const val VERSIONS_URL =
            "https://github.com/caai-dev/casa-suite-apps/releases/latest/download/versions.json"
        private const val SIX_HOURS = 6L * 60 * 60 * 1000
        private const val ONE_DAY = 24L * 60 * 60 * 1000
    }
}
