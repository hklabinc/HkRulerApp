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

class MainActivity : AppCompatActivity() {

    // ---- Constants
    private companion object {
        private const val STORAGE_FOLDER = "HkRuler"
        private const val EV_BIND_DELAY_MS = 200L
        private val FOCUS_MODE_LABELS = listOf("Auto (Multi)", "Auto (Center)", "Manual")
    }

    // ---- State
    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraController

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
        checkAndStartCamera()
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
        setupFocusModeSpinner(sp)
        setupManualFocusSeekbar(sp)
        setupAspectAndAssist(sp)
    }

    private fun setupFocusModeSpinner(sp: com.hklab.hkruler.databinding.PanelSettingsBinding) {
        sp.spFocusMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            FOCUS_MODE_LABELS
        )

        sp.spFocusMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val newMode = when (position) {
                    1 -> FocusMode.AUTO_CENTER
                    2 -> FocusMode.MANUAL
                    else -> FocusMode.AUTO_MULTI
                }
                settings = settings.copy(focusMode = newMode)

                // 수동 포커스 UI 노출/숨김
                sp.rowManualFocus.visibility = if (newMode == FocusMode.MANUAL) View.VISIBLE else View.GONE

                // 카메라에 즉시 적용
                camera.applyFocusMode(settings)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupManualFocusSeekbar(sp: com.hklab.hkruler.databinding.PanelSettingsBinding) {
        // 일부 레이아웃/기기에서 미존재 가능성 방어
        sp.seekManualFocus?.apply {
            max = 1000
            progress = (settings.manualFocusDistance * 1000).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val dist = progress / 1000f
                    settings = settings.copy(manualFocusDistance = dist)
                    camera.applyFocusMode(settings) // 수동 포커스 갱신
                    sp.txtManualFocusValue?.text = String.format("%.3f", dist)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        } ?: run {
            sp.rowManualFocus.visibility = View.GONE
        }
    }

    private fun setupAspectAndAssist(sp: com.hklab.hkruler.databinding.PanelSettingsBinding) {
        sp.switchAlignAssist.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(alignAssist = isChecked)
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
        camera.bind(this@MainActivity, settings) // Preview/Capture/Analyzer 재구성
        binding.root.post { bindEvUiFromCamera() } // 일부 기기에서 상태 재동기화 필요
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
}
