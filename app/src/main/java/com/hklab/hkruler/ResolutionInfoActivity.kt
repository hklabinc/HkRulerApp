package com.hklab.hkruler

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import com.hklab.hkruler.databinding.ActivityResolutionInfoBinding

class ResolutionInfoActivity : AppCompatActivity() {
    private lateinit var b: ActivityResolutionInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityResolutionInfoBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "Resolution Info"

        val currentPreview = intent.getStringExtra("currentPreview") ?: "unknown"
        b.txtInfo.text = buildString {
            appendLine("현재 Preview 해상도: $currentPreview")
            appendLine()
            val cm = getSystemService(CameraManager::class.java)
            cm.cameraIdList.forEach { id ->
                val ch = cm.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val yuv = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toList().orEmpty()
                    val jpg = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList().orEmpty()
                    val maxJpg = jpg.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    appendLine("Back Camera ID=$id")
                    appendLine(" - JPEG 최고 해상도: ${sizeStr(maxJpg)}")
                    appendLine(" - Preview(YUV) 지원 목록(상위 20):")
                    yuv.sortedByDescending { it.width.toLong() * it.height.toLong() }
                        .take(20).forEach { appendLine("   · ${sizeStr(it)}") }
                    appendLine(" - Photo(JPEG) 지원 목록(상위 20):")
                    jpg.sortedByDescending { it.width.toLong() * it.height.toLong() }
                        .take(20).forEach { appendLine("   · ${sizeStr(it)}") }
                    appendLine()
                }
            }
        }
    }

    private fun sizeStr(s: Size?): String = s?.let { "${it.width}x${it.height}" } ?: "N/A"
}
