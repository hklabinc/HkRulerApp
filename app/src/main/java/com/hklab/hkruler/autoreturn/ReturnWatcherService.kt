package com.hklab.hkruler.autoreturn

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.hklab.hkruler.MainActivity
import com.hklab.hkruler.R
import java.io.File
import java.util.Locale

class ReturnWatcherService : Service() {

    companion object {
        const val CH_ID = "return_watcher"
        const val NOTI_ID = 3301
        const val EXTRA_LAUNCH_AT = "launchAt"
        private const val FS_CH_ID = "return_fullscreen"
        private const val FS_NOTI_ID = 3302
    }

    private var launchAtMs: Long = 0L
    private var mediaObserver: ContentObserver? = null
    private var fileObserver: FileObserver? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CH_ID, "Return watcher", NotificationManager.IMPORTANCE_LOW)
            )
            nm?.createNotificationChannel(
                NotificationChannel(
                    FS_CH_ID,
                    "Return (full-screen)",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val noti = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("HkRuler")
            .setContentText("촬영 감지 중…")
            .setOngoing(true)
            .build()

        // ✅ Android 14+: 런타임 타입 전달(Manifest 타입과 함께)
        ServiceCompat.startForeground(
            this, NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        launchAtMs = intent?.getLongExtra(EXTRA_LAUNCH_AT, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        registerMediaObserver()
        startFileObserver()
        return START_NOT_STICKY
    }

    // ---- MediaStore 감지
    private fun registerMediaObserver() {
        if (mediaObserver != null) return
        mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // 단건 URI로 빠른 판정
                if (uri != null && isNewPhotoUri(uri)) { onDetected(); return }
                // 보조: 테이블 재조회
                if (checkAnyNewPhoto()) onDetected()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver!!
        )
    }

    private fun isNewPhotoUri(itemUri: Uri): Boolean {
        if (itemUri.scheme != "content") return false
        val proj = arrayOf(
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE
        )
        return try {
            contentResolver.query(itemUri, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return false
                val dateAdded = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                val threshold = (launchAtMs / 1000L) - 5
                if (dateAdded < threshold) return false
                val relIdx = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                val buckIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val rel = if (relIdx >= 0) c.getString(relIdx) else null
                val bucket = if (buckIdx >= 0) c.getString(buckIdx) else null
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                val inDcimCamera = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    (rel?.contains("DCIM/Camera") == true) else (bucket == "Camera")
                inDcimCamera && (mime?.startsWith("image/") == true)
            } ?: false
        } catch (_: SecurityException) { false }
    }

    private fun checkAnyNewPhoto(): Boolean {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE
        )
        val sel = StringBuilder().apply {
            append("${MediaStore.Images.Media.DATE_ADDED} >= ?")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE '%DCIM/Camera%'")
            else
                append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}='Camera'")
            append(" AND ${MediaStore.Images.Media.MIME_TYPE} LIKE 'image/%'")
        }.toString()
        val args = arrayOf(((launchAtMs / 1000L) - 5).toString())
        return try {
            contentResolver.query(uri, proj, sel, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c -> c.moveToFirst() } == true
        } catch (_: SecurityException) { false }
    }

    // ---- FileObserver 감지 (DCIM/Camera)
    private fun startFileObserver() {
        if (fileObserver != null) return
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val cameraDir = File(dcim, "Camera")
        fileObserver = object : FileObserver(cameraDir.absolutePath, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path.isNullOrEmpty()) return
                val p = path.lowercase(Locale.US)
                if (p.endsWith(".jpg") || p.endsWith(".jpeg")
                    || p.endsWith(".heic") || p.endsWith(".dng")) {
                    main.post { onDetected() }
                }
            }
        }.apply { startWatching() }
    }

    // ---- 공통: 감지되면 복귀 시도
    private var handled = false
    private fun onDetected() {
        if (handled) return
        handled = true

        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        mediaObserver = null
        try { fileObserver?.stopWatching() } catch (_: Exception) {}
        fileObserver = null

        // 접근성 ON → HOME 후 우리 앱 실행(프로 모드 보존)
        if (ReturnAccessibilityService.isEnabled(this)) {
            ReturnAccessibilityService.instance()?.returnToHkRuler()
            // 보강: 짧은 재시도 + 종료
            scheduleBringToFront()
            main.postDelayed({ cleanupAndStop() }, 1300L)
            return
        }

        // 일반 경로: 여러 번 전면 이동 시도 + 풀스크린 알림 폴백
        scheduleBringToFront()
        main.postDelayed({ showFullScreenReturn() }, 150L)
        main.postDelayed({ cleanupAndStop() }, 1700L)
    }

    private fun scheduleBringToFront() {
        val tries = longArrayOf(0L, 200L, 500L, 900L, 1300L)
        tries.forEach { delay ->
            main.postDelayed({ tryBringToFront() }, delay)
        }
    }

    private fun tryBringToFront() {
        try {
            val am = getSystemService(ActivityManager::class.java)
            am?.appTasks?.forEach { t -> try { t.moveToFront() } catch (_: Exception) {} }
        } catch (_: Throwable) {}
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun showFullScreenReturn() {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }, flags
        )

        val noti = NotificationCompat.Builder(this, FS_CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("HkRuler로 복귀")
            .setContentText("촬영이 완료되어 HkRuler로 전환합니다.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(pi, true) // 화면 켜져 있어도 전면 표시 유도
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(FS_NOTI_ID, noti)
    }

    private fun cleanupAndStop() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { stopSelf() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        mediaObserver = null
        try { fileObserver?.stopWatching() } catch (_: Exception) {}
        fileObserver = null
    }
}