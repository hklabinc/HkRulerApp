//package com.example.hkruler
//
//import android.Manifest
//import android.annotation.SuppressLint
//import android.content.ContentValues
//import android.content.pm.PackageManager
//import android.os.Build
//import android.os.Bundle
//import android.provider.MediaStore
//import android.view.Surface
//import android.widget.ImageButton
//import android.widget.Toast
//import androidx.activity.ComponentActivity
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.camera.core.*
//import androidx.camera.lifecycle.ProcessCameraProvider
//import androidx.camera.view.PreviewView
//import androidx.camera.core.ImageCapture
//import androidx.core.content.ContextCompat
//import androidx.camera.core.resolutionselector.ResolutionSelector
//import androidx.camera.core.resolutionselector.ResolutionStrategy
//import java.text.SimpleDateFormat
//import java.util.Locale
//
//import android.hardware.camera2.CameraCharacteristics
//import android.hardware.camera2.CameraManager
//import android.hardware.camera2.params.StreamConfigurationMap
//import android.util.Log
//import android.graphics.ImageFormat
//
//import android.util.Size
//class `MainActivity_v1_자체카메라앱(해상도낮음)` : ComponentActivity() {
//
//    private lateinit var previewView: PreviewView
//    private lateinit var btnCapture: ImageButton
//    private var imageCapture: ImageCapture? = null
//
//    // 카메라 권한 런처
//    private val requestCameraPermission = registerForActivityResult(
//        ActivityResultContracts.RequestPermission()
//    ) { granted ->
//        if (granted) startCamera() else {
//            Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        previewView = findViewById(R.id.previewView)
//        btnCapture = findViewById(R.id.btnCapture)
//
//        // 권한 확인 후 시작
//        if (ContextCompat.checkSelfPermission(
//                this, Manifest.permission.CAMERA
//            ) == PackageManager.PERMISSION_GRANTED
//        ) {
//            // 권한 허용된 경우 → 카메라 시작 전에 지원 해상도 로그 출력
//            logSupportedHighResOutputSizes()
//            startCamera()
//        } else {
//            requestCameraPermission.launch(Manifest.permission.CAMERA)
//        }
//
//        btnCapture.setOnClickListener { takePhoto() }
//    }
//
//    @SuppressLint("RestrictedApi")
//    private fun startCamera() {
//        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
//
//        cameraProviderFuture.addListener({
//            val cameraProvider = cameraProviderFuture.get()
//
//            val preview = Preview.Builder()
//                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
//                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
//                .build().also {
//                    it.setSurfaceProvider(previewView.surfaceProvider)
//                }
//
//            imageCapture = ImageCapture.Builder()
//                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
//                .setResolutionSelector(
//                    ResolutionSelector.Builder()
//                        .setResolutionStrategy(
//                            ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
//                        )
//                        .build()
//                )
//                .setZslDisabled(true) // 고해상도 캡처 시 안정성 ↑
//                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
//                .build()
//
//            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
//
//            try {
//                cameraProvider.unbindAll()
//                cameraProvider.bindToLifecycle(
//                    this, cameraSelector, preview, imageCapture
//                )
//            } catch (e: Exception) {
//                Toast.makeText(this, "카메라 바인딩 실패: ${e.message}", Toast.LENGTH_SHORT).show()
//            }
//        }, ContextCompat.getMainExecutor(this))
//    }
//
//    private fun takePhoto() {
//        val imageCapture = imageCapture ?: return
//
//        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
//            .format(System.currentTimeMillis())
//
//        val contentValues = ContentValues().apply {
//            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$name.jpg")
//            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraXDemo")
//            }
//        }
//
//        val outputOptions = ImageCapture.OutputFileOptions.Builder(
//            contentResolver,
//            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
//            contentValues
//        ).build()
//
//        imageCapture.takePicture(
//            outputOptions,
//            ContextCompat.getMainExecutor(this),
//            object : ImageCapture.OnImageSavedCallback {
//                override fun onError(exc: ImageCaptureException) {
//                    Toast.makeText(this@`MainActivity_v1_자체카메라앱(해상도낮음)`, "저장 실패: ${exc.message}", Toast.LENGTH_SHORT).show()
//                }
//                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
//                    Toast.makeText(this@`MainActivity_v1_자체카메라앱(해상도낮음)`, "저장 완료!", Toast.LENGTH_SHORT).show()
//                }
//            }
//        )
//    }
//
//
//    private fun logSupportedHighResOutputSizes() {
//        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
//        try {
//            for (cameraId in manager.cameraIdList) {
//                val characteristics = manager.getCameraCharacteristics(cameraId)
//                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
//                val map = characteristics.get(
//                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
//                ) as? StreamConfigurationMap
//
//                if (map != null) {
//                    // 일반 JPEG 출력 사이즈
//                    val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
//                    jpegSizes?.forEach {
//                        Log.d("CameraInfo", "Camera $cameraId JPEG size: ${it.width}x${it.height}")
//                    }
//
//                    // 고해상도 JPEG 출력 사이즈 (API 23+)
//                    val highResJpegSizes = map.getHighResolutionOutputSizes(ImageFormat.JPEG)
//                    highResJpegSizes?.forEach {
//                        Log.d("CameraInfo", "Camera $cameraId HIGH-RES JPEG size: ${it.width}x${it.height}")
//                    }
//
//                    Log.d("CameraInfo", "Camera $cameraId facing=$facing")
//                }
//            }
//        } catch (e: Exception) {
//            Log.e("CameraInfo", "Error reading camera characteristics: ${e.message}")
//        }
//    }
//
//}
