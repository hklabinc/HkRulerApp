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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.core.content.FileProvider
import android.app.NotificationChannel
import android.app.NotificationManager
import com.hklab.hkruler.autoreturn.ReturnAccessibilityService
import com.hklab.hkruler.autoreturn.ReturnWatcherService

import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.view.Display
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.BitmapFactory

class MainActivity : AppCompatActivity() {

    // ---- Constants
    private companion object {
        private const val STORAGE_FOLDER = "HkRuler"
        private const val EV_BIND_DELAY_MS = 200L
        private const val REQ_NOTI = 1001
        private const val REQ_READ_IMAGES = 1002
        private const val REQ_READ_EXT = 1003
        private const val RETURN_CH_ID = "return_to_hkruler"
    }

    // ---- State
    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraController
    private var settings = AppSettings(aspect = Aspect.R16_9)

    // 시스템 카메라용 보류 URI/파일
    private var pendingSystemPhotoUri: Uri? = null
    private var pendingSystemPhotoFile: File? = null

    // ✅ 인앱 캡처 미리보기용 임시 파일 상태
    private var pendingPreviewFile: File? = null
    private var pendingDisplayName: String? = null

    // 파일명 포맷터
    private val filenameFormat by lazy { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }

    private val REQUIRED_PERMISSIONS = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private val allPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isEmpty()) {
                startCamera()
            } else {
                showToast("필수 권한이 거부되었습니다: ${denied.joinToString()}", long = true)
                finish()
            }
        }

    // 권한 요청 런처들
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else finish()
        }

    private val legacyWritePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) capturePhotoInternal()
            else showToast("저장 권한이 없어 사진을 저장할 수 없습니다.")
        }

    // Android 9 이하: 시스템 카메라 사용 전 쓰기 권한 요청
    private val legacyWritePermissionForSystemLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchSystemCameraInternal()
            else showToast("저장 권한이 없어 시스템 카메라를 사용할 수 없습니다.", long = true)
        }

    // ✅ Android 9 이하: 미리보기에서 “저장” 시 퍼블릭 저장 권한 요청
    private val legacyWritePermissionForPreviewSaveLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) reallySavePendingPreviewToGallery()
            else showToast("저장 권한이 없어 사진을 저장할 수 없습니다.", long = true)
        }

    // 시스템 카메라 결과 처리
    private val systemCameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = pendingSystemPhotoUri
            if (result.resultCode == Activity.RESULT_OK && uri != null) {
                // API 28 이하 FileProvider 파일이면 미디어 스캔
                pendingSystemPhotoFile?.let { f ->
                    if (f.exists()) {
                        MediaScannerConnection.scanFile(
                            this, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null
                        )
                    }
                }
                showToast("시스템 카메라로 저장 완료")
            } else {
                // 취소/실패 → 예약 리소스 정리
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

    // ---- Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ 외부 디스플레이/DeX 등에서 실행되면 폰 화면으로 재앵커 (기존 동작 보존)
        val alreadyReanchored = intent?.getBooleanExtra(PhoneDisplayProxyActivity.EXTRA_REANCHORED, false) ?: false
        if (!alreadyReanchored && isDexLikeOrExternal()) {
            val proxy = Intent(this, PhoneDisplayProxyActivity::class.java).apply {
                action = PhoneDisplayProxyActivity.ACTION_OPEN_MAIN
            }
            startOnPhoneDisplay(proxy)
            finish()
            return
        }

        initBinding()
        initCameraController()
        initUi()
        createReturnChannel()

        // 한 번에 권한 확인 및 요청
        if (!hasAllPermissions()) {
            allPermissionLauncher.launch(REQUIRED_PERMISSIONS.toTypedArray())
        } else {
            startCamera()
        }
    }

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

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
        btnSettings.setOnClickListener { toggleSettingsPanel() }
        setupSettingsUi()

        // 인앱 촬영(미리보기)
        btnCapture.setOnClickListener { capturePhoto() }

        // 삼성 카메라(전체 UI, 프로 모드 포함) 실행
        btnIntentCapture.setOnClickListener { openSamsungCameraFullUi() }

        // ✅ 미리보기 저장/취소
        btnPreviewSave.setOnClickListener { onClickSavePreview() }
        btnPreviewCancel.setOnClickListener { onClickCancelPreview() }
    }

    private fun createReturnChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                RETURN_CH_ID, "Return to HkRuler", NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    // ---- Camera start
    private fun startCamera() {
        camera.bind(this, settings)
        updateOverlayVisibility()
        binding.root.postDelayed({ bindEvUiFromCamera() }, EV_BIND_DELAY_MS)
    }

    // ---- Capture (show preview first)
    private fun capturePhoto() {
        // ※ 이전에는 API 28 이하에서 퍼블릭 저장 권한을 먼저 요구했지만,
        //    지금은 '임시 파일'에 저장 후 사용자가 저장을 누를 때만 퍼블릭 저장으로 이동합니다.
        capturePhotoInternal()
    }

    /** 임시 파일로 캡처 → 미리보기 오버레이 표시 */
    private fun capturePhotoInternal() {
        // 1) 임시 파일 생성(앱 캐시)
        val temp = File.createTempFile("HkRuler_", ".jpg", cacheDir)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(temp).build()
        val displayName = "IMG_${filenameFormat.format(Date())}.jpg"

        // 2) 촬영
        binding.btnCapture.isEnabled = false
        camera.takePicture(outputOptions) { ok, err ->
            binding.btnCapture.isEnabled = true
            if (!ok) {
                temp.delete()
                showToast("촬영 실패: ${err ?: "알 수 없는 오류"}", long = true)
                return@takePicture
            }
            // 3) 상태 보관 + 미리보기 표시
            pendingPreviewFile = temp
            pendingDisplayName = displayName
            showPreviewOverlay(temp, displayName)
        }
    }

    /** 미리보기 오버레이 표시 + 정보 갱신 */
    private fun showPreviewOverlay(file: File, displayName: String) {
        // 이미지 표시
        binding.imagePreview.setImageURI(Uri.fromFile(file))

        // 해상도/크기 읽기
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        val size = file.length()

        binding.txtShotInfo.text = buildString {
            append(displayName)
            if (w > 0 && h > 0) append(" ${w}×${h}")
            append(" (${readableBytes(size)})")
        }

        binding.previewOverlay.visibility = View.VISIBLE
    }

    /** 미리보기 → 저장 버튼 */
    private fun onClickSavePreview() {
        if (pendingPreviewFile == null || pendingDisplayName == null) {
            hidePreviewOverlay()
            return
        }
        // API 28 이하: 퍼블릭 폴더에 쓰기 권한 필요
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            legacyWritePermissionForPreviewSaveLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        reallySavePendingPreviewToGallery()
    }

    /** 미리보기 → 취소 버튼 */
    private fun onClickCancelPreview() {
        pendingPreviewFile?.let { runCatching { it.delete() } }
        pendingPreviewFile = null
        pendingDisplayName = null
        binding.txtShotInfo.text = ""
        hidePreviewOverlay()
    }

    /** 실제 저장 수행(권한 보장 하에 호출) */
    private fun reallySavePendingPreviewToGallery() {
        val src = pendingPreviewFile ?: return
        val displayName = pendingDisplayName ?: "IMG_${filenameFormat.format(Date())}.jpg"

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 스코프 저장소 → Pictures/HkRuler
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/$STORAGE_FOLDER")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(src).use { it.copyTo(out) }
                    }
                }.isSuccess
            } else false
        } else {
            // API 28 이하 → 퍼블릭 Pictures/HkRuler로 복사
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                STORAGE_FOLDER
            ).apply { if (!exists()) mkdirs() }
            val dst = File(dir, displayName)
            runCatching {
                FileInputStream(src).use { input ->
                    FileOutputStream(dst).use { output -> input.copyTo(output) }
                }
                // 갤러리 스캔
                MediaScannerConnection.scanFile(
                    this, arrayOf(dst.absolutePath), arrayOf("image/jpeg"), null
                )
            }.isSuccess
        }

        if (ok) {
            showToast("사진이 저장되었습니다: Pictures/$STORAGE_FOLDER/$displayName")
            runCatching { src.delete() }
        } else {
            showToast("저장 실패(파일 복사 중 오류)", long = true)
        }

        // 상태/오버레이 정리
        pendingPreviewFile = null
        pendingDisplayName = null
        binding.previewOverlay.visibility = View.GONE
    }

    private fun hidePreviewOverlay() {
        binding.imagePreview.setImageDrawable(null)
        binding.previewOverlay.visibility = View.GONE
    }

    private fun readableBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        return when {
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    // ---- EV / Settings (기존 유지)
    private fun bindEvUiFromCamera() {
        val state = camera.exposureState ?: run {
            binding.settingsPanel.rowEv.visibility = View.GONE
            return
        }
        val range = state.exposureCompensationRange
        val cur = state.exposureCompensationIndex
        val max = range.upper - range.lower

        with(binding.settingsPanel) {
            rowEv.visibility = View.VISIBLE
            seekEv.max = max
            seekEv.progress = (cur - range.lower)
            txtEvValue.text = evIndexToLabel(cur)
            seekEv.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val idx = range.lower + progress
                    txtEvValue.text = evIndexToLabel(idx)
                    camera.applyEv(idx)
                    settings = settings.copy(evIndex = idx)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
    }

    private fun evIndexToLabel(idx: Int) = if (idx > 0) "+$idx" else idx.toString()

    private fun setupSettingsUi() {
        val sp = binding.settingsPanel
        // 포커스 모드 라디오
        sp.radioGroupFocus.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                sp.radioFocusCenter.id -> FocusMode.AUTO_CENTER
                else -> FocusMode.AUTO_MULTI
            }
            settings = settings.copy(focusMode = newMode)
            camera.applyFocusMode(settings)
        }
        // 종횡비 + Align Assist
        sp.switchAlignAssist.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(alignAssist = isChecked)
            updateOverlayVisibility()
            rebindCameraAndRefreshEv()
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
        camera.bind(this@MainActivity, settings)
        updateOverlayVisibility()
        binding.root.post { bindEvUiFromCamera() }
    }

    /** Align Assist on/off 에 따라 오버레이 표시/숨김 */
    private fun updateOverlayVisibility() {
        binding.lineOverlay.visibility = if (settings.alignAssist) View.VISIBLE else View.GONE
    }

    // ---- 삼성 카메라(전체 UI, 프로 모드 유지) — 기존 유지
    private fun openSamsungCameraFullUi() {
        if (!ReturnAccessibilityService.isEnabled(this)) {
            showToast("항상 자동 복귀를 원하면 '설정 > 접근성 > HkRuler'를 켜주세요.", long = true)
        }
        val launchAt = System.currentTimeMillis()
        val svc = Intent(this, ReturnWatcherService::class.java)
            .putExtra(ReturnWatcherService.EXTRA_LAUNCH_AT, launchAt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        val proxy = Intent(this, PhoneDisplayProxyActivity::class.java).apply {
            action = PhoneDisplayProxyActivity.ACTION_OPEN_CAMERA
        }
        startOnPhoneDisplay(proxy)
    }

    // ---- 시스템 카메라(필요 시) — 기존 유지
    private fun launchSystemCamera() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
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
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("android.intent.extra.quickCapture", true)
        }
        val samsung = Intent(base).setPackage("com.sec.android.app.camera")
        try {
            val toLaunch = if (samsung.resolveActivity(packageManager) != null) samsung else base
            toLaunch.`package`?.let { pkg ->
                grantUriPermission(pkg, outUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            systemCameraLauncher.launch(toLaunch)
        } catch (e: Exception) {
            showToast("카메라 앱 실행에 실패했습니다: ${e.localizedMessage}", long = true)
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

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun showToast(msg: String, long: Boolean = false) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /** 현재 컨텍스트가 DeX/외부 디스플레이로 보이는지 대략 판정(기존 코드 유지) */
    private fun isDexLikeOrExternal(): Boolean {
        val displayId = if (Build.VERSION.SDK_INT >= 30) this.display?.displayId else null
        val um = getSystemService(UiModeManager::class.java)
        val isDesk = resources.configuration.uiMode and
                Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_DESK
        return (displayId != null && displayId != Display.DEFAULT_DISPLAY) || isDesk
    }

    /** 주어진 Intent를 ‘휴대폰 내장 디스플레이(기본 디스플레이)’에서 실행(가능하면) */
    private fun startOnPhoneDisplay(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val opts = ActivityOptions.makeBasic().apply {
                    setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                }
                startActivity(intent, opts.toBundle())
                return
            } catch (_: Throwable) { /* fallback below */ }
        }
        startActivity(intent)
    }


    override fun onResume() {
        super.onResume()

        // 이미 미리보기 중이면 무시
        if (binding.previewOverlay.visibility == View.VISIBLE) return

        // 삼성카메라에서 복귀했는지 감지
        val recent = getLatestPhotoFromSamsungCamera()
        recent?.let { file ->
            pendingPreviewFile = file
            pendingDisplayName = file.name
            showPreviewOverlay(file, file.name)
        }
    }

    private fun getLatestPhotoFromSamsungCamera(): File? {
        return try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATA
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val path = cursor.getString(pathCol)
                    val name = cursor.getString(nameCol)
                    val file = File(path)
                    // 최근 10초 내에 찍은 파일만 인정 (앱 복귀 시점 기준)
                    val diff = (System.currentTimeMillis() - file.lastModified())
                    if (file.exists() && diff < 10_000) { // 10초 이내
                        return file
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }


}
