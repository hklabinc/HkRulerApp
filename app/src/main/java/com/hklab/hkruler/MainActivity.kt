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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraController
    private var settings = AppSettings(
        aspect = Aspect.R16_9,
        // 필요 시 초기값 조정
    )

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
