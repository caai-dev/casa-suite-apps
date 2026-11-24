package com.casaaudit.casana

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Notifications with buttons (24 Nov 2026, bhaiya: "in the notification itself, it must give an option to like and reply").
 *
 * From app 1.6 the server (send-push) sends this phone "data" messages, which reach this service even when Casana
 * is closed, and the app builds the notification itself: the same title and text as before, plus
 * "Like" and "Reply" buttons on comment notifications. Each button opens Casana at that comment; the page then
 * likes it or opens the reply box. Phones on 1.5 and older keep getting the old messages, which Android shows by
 * itself (no buttons), so nothing changes for them.
 *
 * Data keys (all text): title, body, url, tag, likeUrl, replyUrl. Only Casana links are opened.
 */
class CasanaMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val title = d["title"]?.takeIf { it.isNotBlank() } ?: return // old-style messages are shown by Android
        try {
            show(this, title, d["body"].orEmpty(), d["url"], d["tag"], d["likeUrl"], d["replyUrl"])
        } catch (_: Exception) {
            // A notification that cannot be built must never crash the app.
        }
    }

    override fun onNewToken(token: String) {
        // The page reads the token through window.CasanaAndroid.pushToken() the next time Casana opens.
    }

    companion object {
        private const val CHANNEL = "casana_activity"
        private const val HOST = "apex-casa-workdesk.apper.so"

        private fun casanaLink(url: String?): Uri? =
            url?.let { Uri.parse(it) }?.takeIf { it.scheme == "https" && it.host == HOST }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Casana activity", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Mentions, comments and tasks given to you"
                }
            )
        }

        /** An Intent that opens [link] inside Casana and closes notification [id]. */
        private fun open(context: Context, link: Uri, id: Int, code: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(link)
                .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun show(context: Context, title: String, body: String, url: String?, tag: String?, likeUrl: String?, replyUrl: String?) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            ensureChannel(context)
            val id = (tag?.takeIf { it.isNotBlank() } ?: "$title|$body").hashCode() and 0x3FFFFFFF
            val main = casanaLink(url) ?: Uri.parse("https://$HOST/inbox")

            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL)
            } else {
                Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
            }
            builder
                .setSmallIcon(R.drawable.ic_stat_casana)
                .setColor(0xFF105C22.toInt())
                .setContentTitle(title.take(120))
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(open(context, main, id, id * 4))

            val icon = Icon.createWithResource(context, R.drawable.ic_stat_casana)
            casanaLink(likeUrl)?.let {
                builder.addAction(Notification.Action.Builder(icon, "Like", open(context, it, id, id * 4 + 1)).build())
            }
            casanaLink(replyUrl)?.let {
                builder.addAction(Notification.Action.Builder(icon, "Reply", open(context, it, id, id * 4 + 2)).build())
            }
            context.getSystemService(NotificationManager::class.java)?.notify(id, builder.build())
        }
    }
}
