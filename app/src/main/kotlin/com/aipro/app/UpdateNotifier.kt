package com.aipro.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aipro.common.AiproLog

/**
 * Posts a one-shot "업데이트되었습니다" notification the first time a new build runs.
 *
 * The seen version is stored in the same preferences bundle the camera settings use, so the
 * notification appears exactly once per installed version and never on plain restarts.
 */
object UpdateNotifier {

    private const val TAG = "UpdateNotifier"
    private const val CHANNEL_ID = "aipro_updates"
    private const val CHANNEL_NAME = "앱 업데이트"
    private const val PREFS = "aipro_settings"
    private const val KEY_SEEN_VERSION = "last_notified_version"
    private const val NOTIFICATION_ID = 1001

    /** Shown in the notification body; keep it short and factual per release. */
    private const val CHANGELOG =
        "사진·동영상 촬영 오류 수정 · 프리뷰 비율/회전 수정 · 프로 모드 메뉴 정리"

    fun notifyIfUpdated(context: Context) {
        runCatching {
            val current = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_SEEN_VERSION, null) == current) return

            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "AI Pro Camera 업데이트 알림" }
                manager.createNotificationChannel(channel)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                // Not marked as seen: the notification is posted on the launch *after* the
                // permission is granted (MainActivity re-runs this from the permission callback).
                AiproLog.i(TAG, "알림 권한 없음 → 권한 허용 후 다시 시도 ($current)")
                return
            }

            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            val notification = builder
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("AI Pro Camera ${BuildConfig.VERSION_NAME} 업데이트됨")
                .setContentText(CHANGELOG)
                .setStyle(Notification.BigTextStyle().bigText(CHANGELOG))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
            prefs.edit().putString(KEY_SEEN_VERSION, current).apply()
            AiproLog.i(TAG, "업데이트 알림 표시: $current")
        }.onFailure { AiproLog.w(TAG, "업데이트 알림 실패", it) }
    }
}
