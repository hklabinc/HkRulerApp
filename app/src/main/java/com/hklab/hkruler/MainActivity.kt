// MainActivity.kt
package com.hklab.hkruler

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import com.hklab.hkruler.camera.CameraController
import com.hklab.hkruler.data.AppSettings
import com.hklab.hkruler.data.Aspect
import com.hklab.hkruler.data.FocusMode
import com.hklab.hkruler.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.media.MediaScannerConnection
import androidx.core.content.FileProvider
import android.content.ActivityNotFoundException

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import android.app.PendingIntent
import androidx.annotation.RequiresPermission
import android.os.FileObserver

class MainActivity : AppCompatActivity() {

    // --- Auto return 감지/알림용
    private var mediaObserver: ContentObserver? = null
    private var fileObserver: FileObserver? = null
    private var autoReturnArmed = false
    private var cameraLaunchAt: Long = 0L
    private var isInForeground = false


    // ---- Constants
    private companion object {
        private const val STORAGE_FOLDER = "HkRuler"
        private const val EV_BIND_DELAY_MS = 200L
        private const val RETURN_CH_ID = "return_to_hkruler"
        private const val RETURN_NOTI_ID = 2201

        private const val REQ_NOTI = 1001
        private const val REQ_READ_IMAGES = 1002
        private const val REQ_READ_EXT = 1003
    }

    // ---- State
    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraController


    // 시스템 카메라용 보류 URI/파일
    private var pendingSystemPhotoUri: Uri? = null
    private var pendingSystemPhotoFile: File? = null

    // Android 9 이하: 시스템 카메라 사용 전 쓰기 권한 요청용
    private val legacyWritePermissionForSystemLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchSystemCameraInternal()
            else showToast("저장 권한이 없어 시스템 카메라를 사용할 수 없습니다.", long = true)
        }

    // 시스템 카메라 결과 처리
    private val systemCameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = pendingSystemPhotoUri
            if (result.resultCode == Activity.RESULT_OK && uri != null) {
                // API 28 이하에서 FileProvider 파일이면 미디어 스캔(갤러리 노출)
                pendingSystemPhotoFile?.let { f ->
                    if (f.exists()) {
                        MediaScannerConnection.scanFile(
                            this, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null
                        )
                    }
                }
                showToast("시스템 카메라로 저장 완료")
            } else {
                // 취소/실패 시 예약 리소스 정리
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        uri != null && "content" == uri.scheme) {
                        contentResolver.delete(uri, null, null)
                    }
                } catch (_: Exception) {}
                try {
                    pendingSystemPhotoFile?.let { f ->
                        if (f.exists() && f.length() == 0L) f.delete()
                    }
                } catch (_: Exception) {}
                showToast("취소되었습니다")
            }
            pendingSystemPhotoUri = null
            pendingSystemPhotoFile = null
        }


    private var settings = AppSettings(
        aspect = Aspect.R16_9,
        // 필요 시 초기값 조정
    )

    // 파일명 포맷터(지연 초기화)
    private val filenameFormat by lazy { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }

    // ---- Permissions
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else finish()
        }

    private val legacyWritePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) capturePhotoInternal()
            else showToast("저장 권한이 없어 사진을 저장할 수 없습니다.")
        }

    // ---- Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initBinding()
        initCameraController()
        initUi()
        createReturnChannel()
        checkAndStartCamera()

        // 알림 권한(안드13+)
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTI)
        }
        // ✅ 이미지 읽기 권한
        if (Build.VERSION.SDK_INT >= 33) {
            if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), REQ_READ_IMAGES)
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_READ_EXT)
            }
        }
    }

    private fun createReturnChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                RETURN_CH_ID, "Return to HkRuler", NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }


    /** Align Assist 상태에 따라 오버레이 가시성 동기화 */
    private fun updateOverlayVisibility() {
        binding.lineOverlay.visibility =
            if (settings.alignAssist) View.VISIBLE else View.GONE
        // 만약 LineOverlayView에 clear/reset API가 있다면 잔상까지 비우고 싶을 때 아래를 사용
        // if (!settings.alignAssist) binding.lineOverlay.clear() // 또는 setLines(emptyList())
    }
    
    // ---- Init
    private fun initBinding() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    private fun initCameraController() {
        camera = CameraController(
            context = this,
            previewView = binding.previewView,
            overlay = binding.lineOverlay
        )
    }

    private fun initUi() = with(binding) {
        // Settings 패널 토글
        btnSettings.setOnClickListener { toggleSettingsPanel() }

        // Settings UI 구성
        setupSettingsUi()

        // Capture
        btnCapture.setOnClickListener { capturePhoto() }

        // ✅ 추가
//        btnIntentCapture.setOnClickListener { launchSystemCamera() }
        btnIntentCapture.setOnClickListener { openSamsungCameraFullUi() }
    }

    // ---- Permissions / Camera start
    private fun checkAndStartCamera() {
        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        camera.bind(this, settings)
        updateOverlayVisibility() // 바인딩 직후에도 오버레이 가시성 보정
        // EV 슬라이더는 카메라 바인딩 직후 약간의 지연 후 동기화
        binding.root.postDelayed({ bindEvUiFromCamera() }, EV_BIND_DELAY_MS)
    }

    // ---- Capture flow
    private fun capturePhoto() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        capturePhotoInternal()
    }

    private fun capturePhotoInternal() {
        val (outputOptions, displayName) = buildOutputOptions()
        binding.btnCapture.isEnabled = false

        camera.takePicture(outputOptions) { ok, err ->
            binding.btnCapture.isEnabled = true
            if (ok) {
                showToast("사진이 저장되었습니다: Pictures/$STORAGE_FOLDER/$displayName")
            } else {
                showToast("저장 실패: ${err ?: "알 수 없는 오류"}", long = true)
            }
        }
    }

    private fun buildOutputOptions(): Pair<ImageCapture.OutputFileOptions, String> {
        val displayName = "IMG_${filenameFormat.format(Date())}.jpg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 스코프 저장소: Pictures/HkRuler
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/$STORAGE_FOLDER"
                )
            }
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ImageCapture.OutputFileOptions.Builder(contentResolver, uri, values).build() to displayName
        } else {
            // API 28 이하: 퍼블릭 Pictures/HkRuler 디렉터리 직접 저장
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                STORAGE_FOLDER
            ).apply { if (!exists()) mkdirs() }
            val file = File(dir, displayName)
            ImageCapture.OutputFileOptions.Builder(file).build() to displayName
        }
    }

    // ---- EV UI binding
    /** EV 슬라이더를 현재 카메라 상태로 동기화 */
    private fun bindEvUiFromCamera() {
        val state = camera.exposureState
        val sp = binding.settingsPanel

        if (state == null) {
            sp.rowEv.visibility = View.GONE
            return
        }

        val range = state.exposureCompensationRange // Range<Int>
        val cur = state.exposureCompensationIndex
        val max = range.upper - range.lower

        sp.rowEv.visibility = View.VISIBLE
        sp.seekEv.max = max
        sp.seekEv.progress = (cur - range.lower)
        sp.txtEvValue.text = evIndexToLabel(cur)

        sp.seekEv.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val idx = range.lower + progress
                sp.txtEvValue.text = evIndexToLabel(idx)
                camera.applyEv(idx)
                settings = settings.copy(evIndex = idx)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
    }

    private fun evIndexToLabel(idx: Int): String = if (idx > 0) "+$idx" else idx.toString()

    // ---- Settings panel
    /** Settings 패널 UI/로직 */
    private fun setupSettingsUi() {
        val sp = binding.settingsPanel
        setupFocusModeRadio(sp)
        setupAspectAndAssist(sp)
    }

    /** 포커스 모드 RadioGroup (Auto-Multi / Auto-Center) 설정 */
    private fun setupFocusModeRadio(sp: com.hklab.hkruler.databinding.PanelSettingsBinding) {
        sp.radioGroupFocus.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                sp.radioFocusCenter.id -> FocusMode.AUTO_CENTER
                else -> FocusMode.AUTO_MULTI
            }
            settings = settings.copy(focusMode = newMode)
            camera.applyFocusMode(settings)
        }
    }

    private fun setupAspectAndAssist(sp: com.hklab.hkruler.databinding.PanelSettingsBinding) {
        sp.switchAlignAssist.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(alignAssist = isChecked)
            updateOverlayVisibility()   // 스위치 조작 즉시 화면에서 선 숨기기/보이기
            rebindCameraAndRefreshEv()  // Analyzer 유무 반영을 위해 재바인딩
        }
        sp.radioAspect16x9.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                settings = settings.copy(aspect = Aspect.R16_9)
                rebindCameraAndRefreshEv()
            }
        }
        sp.radioAspect4x3.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                settings = settings.copy(aspect = Aspect.R4_3)
                rebindCameraAndRefreshEv()
            }
        }
    }

    private fun rebindCameraAndRefreshEv() {
        camera.bind(this@MainActivity, settings) // Preview/Capture/Analyzer 재구성
        updateOverlayVisibility()                // 재바인딩 후에도 가시성 유지
        binding.root.post { bindEvUiFromCamera() } // 일부 기기에서 상태 재동기화 필요
    }


    // 삼성 카메라 전체 UI(프로 모드 포함)만 실행하는 함수로 교체
    private fun openSamsungCameraFullUi() {
        val samsungPkg = "com.sec.android.app.camera"

        // 1) 감지 무장
        cameraLaunchAt = System.currentTimeMillis()
        autoReturnArmed = true

        // 2) 권한이 있으면 즉시 등록 (없으면 권한 응답에서 등록됨)
        if (canWatchMediaStore()) {
            registerMediaObserverForReturn()
            startFileObserverForReturn()
        }

        // 3) 전체 UI 실행 (런처 우선, 폴백: 기본 전체 카메라)
        val launchSamsung = packageManager.getLaunchIntentForPackage(samsungPkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val genericStillUi = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        try {
            startActivity(launchSamsung ?: genericStillUi)
        } catch (e: ActivityNotFoundException) {
            showToast("카메라 앱을 열 수 없습니다: ${e.localizedMessage}", long = true)
        }

        // (선택) 알림 권한 요청 트리거
        if (Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTI)
        }
    }

    private fun startFileObserverForReturn() {
        if (fileObserver != null) return
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val cameraDir = File(dcim, "Camera")
        fileObserver = object : FileObserver(cameraDir.absolutePath, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (!autoReturnArmed || path.isNullOrEmpty()) return
                val lower = path.lowercase(Locale.US)
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".heic") || lower.endsWith(".dng"))
                {
                    runOnUiThread { onPhotoDetected() }
                }
            }
        }.apply { startWatching() }
    }

    private fun stopFileObserver() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    private fun onPhotoDetected() {
        autoReturnArmed = false
        unregisterMediaObserver()
        stopFileObserver()
        tryBringToFrontOrNotify()
    }

    private fun tryBringToFrontOrNotify() {
        bringTaskToFront()
        // ❶ 전면 복귀 시도 후 600ms 지연 관찰: 여전히 백그라운드면 알림
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isInForeground) showReturnHeadsUp()
        }, 600)
    }


    private fun canWatchMediaStore(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= 29) {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if ((requestCode == REQ_READ_IMAGES || requestCode == REQ_READ_EXT)
            && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (autoReturnArmed) {
                registerMediaObserverForReturn()
                startFileObserverForReturn()
            }
        }
    }


    private fun registerMediaObserverForReturn() {
        if (mediaObserver != null) return
        mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (!autoReturnArmed) return
                if (checkNewPhotoFromSamsung()) onPhotoDetected()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver!!
        )
    }



    private fun unregisterMediaObserver() {
        mediaObserver?.let { contentResolver.unregisterContentObserver(it) }
        mediaObserver = null
    }

    /** 촬영 후 '새 이미지'를 삼성 카메라에서 기록했는지 확인 */
    private fun checkNewPhotoFromSamsung(): Boolean {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,       // API 29+
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, // legacy
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.OWNER_PACKAGE_NAME   // API 29+
        )
        val thresholdSec = (cameraLaunchAt / 1000L) - 5  // 5초 여유
        val selection = buildString {
            append("${MediaStore.Images.Media.DATE_ADDED} >= ?")
            append(" AND ${MediaStore.Images.Media.MIME_TYPE} LIKE 'image/%'")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                append(" AND (${MediaStore.Images.Media.OWNER_PACKAGE_NAME} IS NULL")
                append(" OR ${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ?)")
            } else {
                append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?")
            }
        }
        val args = mutableListOf(thresholdSec.toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            args += "%DCIM/Camera%"
            args += "com.sec.android.app.camera"
        } else {
            args += "Camera"
        }

        return try {
            contentResolver.query(
                uri, projection, selection, args.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c -> c.moveToFirst() } ?: false
        } catch (_: SecurityException) {
            false
        }
    }

    /** 전면 복귀 시도 (성공/실패 불문하고 예외 없이 시도) */
    private fun bringTaskToFront() {
        try {
            val am = getSystemService(ActivityManager::class.java)
            am?.moveTaskToFront(taskId, 0)
        } catch (_: Throwable) {}
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP   or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (_: Throwable) {}
    }

    /** 2차: 헤드업 알림 → 탭 시 즉시 복귀 */
    private fun showReturnHeadsUp() {
        // 사용자 알림 차단 시 토스트
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            showToast("알림이 차단되어 자동 복귀 알림을 표시할 수 없습니다.", long = true)
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        val pi = TaskStackBuilder.create(this)
            .addNextIntentWithParentStack(Intent(this, MainActivity::class.java))
            .getPendingIntent(RETURN_NOTI_ID, flags)

        val noti = NotificationCompat.Builder(this, RETURN_CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("촬영 완료")
            .setContentText("탭 하면 HkRuler로 돌아갑니다")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)    // ✅ 사운드/진동(Heads-up 가중치)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, false)                 // 화면 꺼져있을 때 즉시 보이도록 힌트
            .build()

        NotificationManagerCompat.from(this).notify(RETURN_NOTI_ID, noti)
    }



    private fun launchSystemCamera() {
        // Android 9 이하: 퍼블릭 Pictures에 미리 파일 생성 시 권한 필요
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            legacyWritePermissionForSystemLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        launchSystemCameraInternal()
    }

    private fun launchSystemCameraInternal() {
        val (outUri, outFile) = createOutputUriForSystemCamera()
        pendingSystemPhotoUri = outUri
        pendingSystemPhotoFile = outFile

        val base = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, outUri)
            // 타 앱(카메라)이 우리가 제공한 Uri에 쓸 수 있도록 권한 부여
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 빠른 캡처(지원 기기 한정)
            putExtra("android.intent.extra.quickCapture", true)
        }

        val targetPkg = "com.sec.android.app.camera"
        val samsung = Intent(base).setPackage(targetPkg)

        try {
            // 삼성 카메라가 처리 가능하면 우선 사용, 아니면 기본 핸들러
            val toLaunch = if (samsung.resolveActivity(packageManager) != null) samsung else base
            // 명시 패키지일 때 Uri 권한 명시적 부여(일부 OEM 호환성)
            toLaunch.`package`?.let { pkg ->
                grantUriPermission(pkg, outUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            systemCameraLauncher.launch(toLaunch)
        } catch (e: Exception) {
            showToast("카메라 앱 실행에 실패했습니다: ${e.localizedMessage}", long = true)
            // 예약된 MediaStore 행/빈 파일 정리
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "content" == outUri.scheme) {
                try { contentResolver.delete(outUri, null, null) } catch (_: Exception) {}
            } else {
                outFile?.let { f -> if (f.exists() && f.length() == 0L) f.delete() }
            }
            pendingSystemPhotoUri = null
            pendingSystemPhotoFile = null
        }
    }

    private fun createOutputUriForSystemCamera(): Pair<Uri, File?> {
        val displayName = "IMG_${filenameFormat.format(Date())}.jpg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore에 선삽입 → Pictures/HkRuler 경로 지정
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/$STORAGE_FOLDER")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert 실패")
            uri to null
        } else {
            // API 28 이하: 퍼블릭 Pictures/HkRuler에 파일 직접 생성 + FileProvider URI 반환
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                STORAGE_FOLDER
            ).apply { if (!exists()) mkdirs() }
            val file = File(dir, displayName)
            val uri = FileProvider.getUriForFile(
                this, "${applicationContext.packageName}.fileprovider", file
            )
            uri to file
        }
    }




    // ---- Helpers
    private fun toggleSettingsPanel() {
        val root = binding.settingsPanel.root
        root.visibility = if (root.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showToast(msg: String, long: Boolean = false) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }



    override fun onStop() {
        super.onStop()
        // 백그라운드 전환 중 불필요한 관찰 방지(원하면 유지 가능)
        // unregisterMediaObserver()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterMediaObserver()
        stopFileObserver()
    }
    override fun onResume() {
        super.onResume()
        isInForeground = true
    }
    override fun onPause() {
        super.onPause()
        isInForeground = false
    }

}
