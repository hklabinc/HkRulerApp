// MainActivity.kt
package com.hklab.hkruler

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hklab.hkruler.camera.CameraController
import com.hklab.hkruler.data.AppSettings
import com.hklab.hkruler.data.Aspect
import com.hklab.hkruler.data.FocusMode
import com.hklab.hkruler.databinding.ActivityMainBinding
import android.os.Build
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Environment
import android.widget.Toast
import androidx.camera.core.ImageCapture
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraController
    private var settings = AppSettings(
        aspect = Aspect.R16_9,
        // 필요 시 초기값 조정
    )

    private val requestLegacyWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) capturePhotoInternal()
            else Toast.makeText(this, "저장 권한이 없어 사진을 저장할 수 없습니다.", Toast.LENGTH_LONG).show()
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // CameraController 생성
        camera = CameraController(
            context = this,
            previewView = binding.previewView,
            overlay = binding.lineOverlay
        )

        // 버튼: Settings 토글
        binding.btnSettings.setOnClickListener {
            binding.settingsPanel.root.visibility =
                if (binding.settingsPanel.root.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // --- Settings UI 초기화
        setupSettingsUi()

        // 권한 확인 후 시작
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        binding.btnCapture.setOnClickListener { capturePhoto() }

    }

    private fun capturePhoto() {
        // API 28 이하에서는 퍼블릭 저장소에 파일 생성 시 권한 필요
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestLegacyWritePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        capturePhotoInternal()
    }

    private fun capturePhotoInternal() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "IMG_${timestamp}.jpg"

        val outputOptions: ImageCapture.OutputFileOptions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // MediaStore(스코프 저장소) → Pictures/HkRuler
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/HkRuler"
                    )
                }
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                ImageCapture.OutputFileOptions.Builder(contentResolver, uri, values).build()
            } else {
                // API 28 이하 → 퍼블릭 Pictures/HkRuler 디렉터리 직접 생성 후 저장
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "HkRuler"
                ).apply { if (!exists()) mkdirs() }
                val file = File(dir, displayName)
                ImageCapture.OutputFileOptions.Builder(file).build()
            }

        // 연속 탭 방지
        binding.btnCapture.isEnabled = false

        // CameraController의 takePicture 사용 (이미 구현되어 있음)
        camera.takePicture(outputOptions) { ok, err ->
            binding.btnCapture.isEnabled = true
            if (ok) {
                Toast.makeText(this, "사진이 저장되었습니다: Pictures/HkRuler/$displayName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "저장 실패: ${err ?: "알 수 없는 오류"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCamera() {
        camera.bind(this, settings)
        // EV 슬라이더는 카메라 바인딩 이후 상태가 준비되면 업데이트
        binding.root.postDelayed({ bindEvUiFromCamera() }, 200)
    }

    /** EV 슬라이더를 현재 카메라 상태로 동기화 */
    private fun bindEvUiFromCamera() {
        val state = camera.exposureState
        if (state == null) {
            binding.settingsPanel.rowEv.visibility = View.GONE
            return
        }
        val range = state.exposureCompensationRange // Range<Int>
        val cur = state.exposureCompensationIndex
        val max = range.upper - range.lower

        val sp = binding.settingsPanel
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

    private fun evIndexToLabel(idx: Int): String {
        // 간단 라벨 (필요시 노출 보정 스텝 반영)
        return if (idx > 0) "+$idx" else idx.toString()
    }

    /** Settings 패널 UI/로직 */
    private fun setupSettingsUi() {
        val sp = binding.settingsPanel

        // --- 포커스 모드 스피너
        val items = listOf("Auto (Multi)", "Auto (Center)", "Manual")
        sp.spFocusMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

        sp.spFocusMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val newMode = when (position) {
                    1 -> FocusMode.AUTO_CENTER
                    2 -> FocusMode.MANUAL
                    else -> FocusMode.AUTO_MULTI
                }
                settings = settings.copy(focusMode = newMode)

                // 수동 포커스 UI 노출
                if (sp.seekManualFocus != null) {
                    val visible = if (newMode == FocusMode.MANUAL) View.VISIBLE else View.GONE
                    sp.rowManualFocus.visibility = visible
                }

                // 카메라에 즉시 적용
                camera.applyFocusMode(settings)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- 수동 포커스 SeekBar
        // 패널 레이아웃에 존재하지 않는 기기/레이아웃 대비해서 null 체크
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
            // 레이아웃에 없으면 전체 행 숨김
            sp.rowManualFocus.visibility = View.GONE
        }

        // --- 종횡비 라디오/스위치 (필요시)
        sp.switchAlignAssist.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(alignAssist = isChecked)
            camera.bind(this, settings) // Analyzer 유무 바뀌므로 리바인딩
        }

        sp.radioAspect16x9.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                settings = settings.copy(aspect = Aspect.R16_9)
                camera.bind(this, settings) // Preview/Capture 타겟 비율 적용
                // EV 슬라이더 다시 동기화 (일부 기기는 비율/사이즈 변경시 상태 갱신 필요)
                binding.root.post { bindEvUiFromCamera() }
            }
        }
        sp.radioAspect4x3.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                settings = settings.copy(aspect = Aspect.R4_3)
                camera.bind(this, settings)
                binding.root.post { bindEvUiFromCamera() }
            }
        }
    }
}
