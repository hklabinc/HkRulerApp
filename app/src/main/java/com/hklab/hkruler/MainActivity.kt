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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.Activity
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.core.content.FileProvider
import android.app.NotificationChannel
import android.app.NotificationManager
import com.hklab.hkruler.autoreturn.ReturnAccessibilityService

import android.app.ActivityOptions
import android.view.Display
import android.app.UiModeManager
import android.content.res.Configuration
import com.hklab.hkruler.autoreturn.ReturnWatcherService


class MainActivity : AppCompatActivity() {

    // ---- Constants
    private companion object {
        private const val STORAGE_FOLDER = "HkRuler"
        private const val EV_BIND_DELAY_MS = 200L
        private const val RETURN_CH_ID = "return_to_hkruler"
    }

    // ---- State
    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraController
    private var settings = AppSettings(aspect = Aspect.R16_9)

    // 시스템 카메라용 보류 URI/파일
    private var pendingSystemPhotoUri: Uri? = null
    private var pendingSystemPhotoFile: File? = null

    // 파일명 포맷터
    private val filenameFormat by lazy { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }

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

    // 여러 권한 동시 요청 런처들
    private val allPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it == true }
            if (allGranted) {
                startCamera()
            } else {
                // 일부 거부됨: 안내 후 종료 또는 설정 화면 유도
                showToast("필수 권한이 거부되어 앱을 종료합니다. 설정에서 권한을 허용해주세요.", long = true)
                finish()
            }
        }

    private fun buildInitialPermissionList(): Array<String> {
        val needed = mutableListOf(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ : 알림, 미디어 이미지
            needed += Manifest.permission.POST_NOTIFICATIONS
            needed += Manifest.permission.READ_MEDIA_IMAGES
        } else if (Build.VERSION.SDK_INT >= 29) {
            // Android 10~12 : 외부 저장 읽기
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        } else {
            // Android 9 이하 : 외부 저장 쓰기
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        // 이미 허용된 것은 제외
        return needed.filterNot { hasPermission(it) }.toTypedArray()
    }

    private fun ensureAllInitialPermissions(onAllGranted: () -> Unit) {
        val missing = buildInitialPermissionList()
        if (missing.isEmpty()) {
            onAllGranted()
        } else {
            allPermissionsLauncher.launch(missing)
        }
    }


    // ---- Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ 외부 디스플레이/DeX에서 뜬 경우 → 폰 화면에 프록시를 띄워 재앵커 후 종료
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

        // ✅ 첫 실행 권한을 한 번에 요청 → 모두 허용되면 카메라 시작
        ensureAllInitialPermissions {
            startCamera()
            // EV UI 바인딩 지연은 기존 그대로 유지
            binding.root.postDelayed({ bindEvUiFromCamera() }, EV_BIND_DELAY_MS)
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

        // 내장 촬영
        btnCapture.setOnClickListener { capturePhoto() }

        // 삼성 카메라 전체 UI(프로 모드 포함) 실행
        btnIntentCapture.setOnClickListener { openSamsungCameraFullUi() }
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

    // ---- Capture (in-app)
    private fun capturePhoto() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
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
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/$STORAGE_FOLDER")
            }
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ImageCapture.OutputFileOptions.Builder(contentResolver, uri, values).build() to displayName
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                STORAGE_FOLDER
            ).apply { if (!exists()) mkdirs() }
            val file = File(dir, displayName)
            ImageCapture.OutputFileOptions.Builder(file).build() to displayName
        }
    }

    // ---- EV / Settings
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

    // ---- 삼성 카메라(전체 UI, 프로 모드 유지)
    private fun openSamsungCameraFullUi() {
        // (선택) 접근성 ON 유도: 항상 자동 복귀를 원한다면 켜두는 것을 권장
        if (!ReturnAccessibilityService.isEnabled(this)) {
            showToast("항상 자동 복귀를 원하면 '설정 > 접근성 > HkRuler'를 켜주세요.", long = true)
        }

        // 1) Foreground Service 시작(감지/복귀 전담)
        val launchAt = System.currentTimeMillis()
        val svc = Intent(this, ReturnWatcherService::class.java)
            .putExtra(ReturnWatcherService.EXTRA_LAUNCH_AT, launchAt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)

        // 2) 삼성 카메라를 ‘항상 폰 화면’에서 실행: 프록시로 위임
        val proxy = Intent(this, PhoneDisplayProxyActivity::class.java).apply {
            action = PhoneDisplayProxyActivity.ACTION_OPEN_CAMERA
        }
        startOnPhoneDisplay(proxy)

//        // 2) 삼성 카메라 전체 UI 실행 (런처 인텐트 우선)
//        val samsungPkg = "com.sec.android.app.camera"
//        val launchSamsung = packageManager.getLaunchIntentForPackage(samsungPkg)?.apply {
//            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
//        }
//        val genericStillUi = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
//
//        try {
//            startActivity(launchSamsung ?: genericStillUi)
//        } catch (e: ActivityNotFoundException) {
//            showToast("카메라 앱을 열 수 없습니다: ${e.localizedMessage}", long = true)
//        }
    }

    // ---- 시스템 카메라 (필요 시 사용)
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



    /** 현재 컨텍스트가 DeX/외부 디스플레이로 보이는지 대략 판정 */
    private fun isDexLikeOrExternal(): Boolean {
        val displayId = if (Build.VERSION.SDK_INT >= 30) this.display?.displayId else null
        val um = getSystemService(UiModeManager::class.java)
        val isDesk = resources.configuration.uiMode and
                Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_DESK
        // displayId가 null이면 보수적으로 false 취급
        return (displayId != null && displayId != Display.DEFAULT_DISPLAY) || isDesk
    }

    /** 주어진 Intent를 ‘휴대폰 내장 디스플레이(기본 디스플레이)’에서 실행(가능하면) */
    private fun startOnPhoneDisplay(intent: Intent) {
        // API 28(P)+ 에서만 디스플레이 지정 가능
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val opts = ActivityOptions.makeBasic().apply {
                    // ✨ '폰 화면'인 기본 디스플레이로 강제
                    setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                }
                startActivity(intent, opts.toBundle())
                return
            } catch (_: Throwable) {
                // 일부 기기 정책/버전에서 무시될 수 있으므로 폴백
            }
        }
        // 폴백: 일반 실행
        startActivity(intent)
    }

}
