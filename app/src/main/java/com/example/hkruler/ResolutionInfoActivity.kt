package com.example.hkruler

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity

class ResolutionInfoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_resolution_info)

        val txtInfo = findViewById<TextView>(R.id.txtResolutionInfo)
        txtInfo.text = getSupportedResolutions()
    }

    private fun getSupportedResolutions(): String {
        val builder = StringBuilder()
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as? StreamConfigurationMap

                builder.append("📷 Camera ID: $cameraId (facing=$facing)\n")

                val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
                jpegSizes?.forEach {
                    builder.append(" - JPEG: ${it.width} x ${it.height}\n")
                }

                val highResJpegSizes = map?.getHighResolutionOutputSizes(ImageFormat.JPEG)
                highResJpegSizes?.forEach {
                    builder.append(" - HIGH-RES: ${it.width} x ${it.height}\n")
                }

                builder.append("\n")
            }
        } catch (e: Exception) {
            builder.append("❌ 해상도 정보 읽기 실패: ${e.message}")
        }

        return builder.toString()
    }
}
