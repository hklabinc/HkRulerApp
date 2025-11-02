package com.hklab.hkruler.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.impl.UseCaseConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.hklab.hkruler.data.*
import com.hklab.hkruler.processing.EdgeAnalyzer
import com.hklab.hkruler.processing.LineOverlayView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val previewView: PreviewView,
    private val overlay: LineOverlayView
) {

    // --- 내부 카메라 핸들
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analyzerUseCase: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var currentSettings: AppSettings = AppSettings()
        private set

    /** 외부에서 EV 설정 범위를 읽을 수 있도록 노출 */
    val exposureState: ExposureState?
        get() = camera?.cameraInfo?.exposureState

    /** 외부에서 현재 포커스 모드/거리도 참고 가능 (UI 바인딩용) */
    val currentFocusMode: FocusMode get() = currentSettings.focusMode

    fun supportedPreviewSizes(aspect: Aspect): List<Size> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = cm.cameraIdList.first {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
        }
        val map = cm.getCameraCharacteristics(backId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        return sizes.filter { keepAspect(it, aspect) }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
    }

    fun supportedPhotoSizes(aspect: Aspect): List<Size> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = cm.cameraIdList.first {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
        }
        val map = cm.getCameraCharacteristics(backId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        return sizes.filter { keepAspect(it, aspect) }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
    }

    fun highestBackJpeg(): Size? =
        supportedPhotoSizes(Aspect.R16_9).plus(supportedPhotoSizes(Aspect.R4_3))
            .maxByOrNull { it.width.toLong() * it.height.toLong() }

    private fun keepAspect(s: Size, aspect: Aspect): Boolean {
        val r = s.width.toFloat() / s.height
        return when (aspect) {
            Aspect.R16_9 -> kotlin.math.abs(r - 16f / 9f) < 0.02f
            Aspect.R4_3 -> kotlin.math.abs(r - 4f / 3f) < 0.02f
        }
    }

    // Preview 전용
    private fun applyAspectOrSize(
        builder: Preview.Builder,
        aspect: Aspect,
        size: Size?
    ) {
        if (size != null) {
            builder.setTargetResolution(size)
        } else {
            builder.setTargetAspectRatio(
                if (aspect == Aspect.R16_9) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
            )
        }
    }

    // ImageCapture 전용
    private fun applyAspectOrSize(
        builder: ImageCapture.Builder,
        aspect: Aspect,
        size: Size?
    ) {
        if (size != null) {
            builder.setTargetResolution(size)
        } else {
            builder.setTargetAspectRatio(
                if (aspect == Aspect.R16_9) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
            )
        }
    }

    private fun pickSupportedFpsRange(ctx: Context): Range<Int> {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
        } ?: return Range(30, 30)

        val ch = cm.getCameraCharacteristics(backId)
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(30, 30)

        val preferred = listOf(15, 24, 30)
        for (p in preferred) {
            ranges.firstOrNull { it.lower <= p && it.upper >= p && it.upper == p }?.let { return it }
            ranges.firstOrNull { it.lower <= p && it.upper >= p }?.let { return it }
        }
        return ranges.minByOrNull { it.upper } ?: Range(30, 30)
    }

    /** 카메라 바인딩/재바인딩 */
    fun bind(lifecycleOwner: LifecycleOwner, newSettings: AppSettings) {
        currentSettings = newSettings

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            provider.unbindAll()

            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            // --- Preview
            val previewBuilder = Preview.Builder()
                .setTargetRotation(Surface.ROTATION_90)
            applyAspectOrSize(previewBuilder, newSettings.aspect, newSettings.previewSize)
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // --- ImageCapture
            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(Surface.ROTATION_90)
            applyAspectOrSize(captureBuilder, newSettings.aspect, newSettings.photoSize)

            // 낮은 FPS(전력절감) 적용 (기기 거절 대비 try-catch)
            try {
                val fps = pickSupportedFpsRange(context)
                Camera2Interop.Extender(captureBuilder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            } catch (_: Throwable) {}

            imageCapture = captureBuilder.build()

            // --- ImageAnalysis (정렬 보조)
            analyzerUseCase = if (newSettings.alignAssist) {
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(Surface.ROTATION_90)
                    .build().also {
                        it.setAnalyzer(cameraExecutor, EdgeAnalyzer(overlay, 500))
                    }
            } else null

            // 바인드
            camera = if (analyzerUseCase != null) {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analyzerUseCase)
            } else {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            }

            // EV, 포커스 등 적용
            applyEv(newSettings.evIndex)
            applyFocusMode(newSettings)

        }, ContextCompat.getMainExecutor(context))
    }

    fun takePicture(outputOptions: ImageCapture.OutputFileOptions, onResult: (Boolean, String?) -> Unit) {
        val ic = imageCapture ?: return onResult(false, "ImageCapture not ready")
        ic.takePicture(outputOptions, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    onResult(false, exc.message)
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onResult(true, null)
                }
            })
    }

    fun applyEv(index: Int) {
        camera?.cameraInfo?.exposureState?.let { state ->
            val clamped = index.coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
            camera?.cameraControl?.setExposureCompensationIndex(clamped)
        }
    }

    /** ⬅️ MainActivity에서 호출 가능하도록 public 으로 */
    fun applyFocusMode(s: AppSettings) {
        val control = camera?.cameraControl ?: return
        when (s.focusMode) {
            FocusMode.AUTO_MULTI -> {
                // Continuous AF (기본)
            }

            FocusMode.AUTO_CENTER -> {
                val point = previewView.meteringPointFactory.createPoint(
                    previewView.width / 2f, previewView.height / 2f
                )
                val act = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
                camera?.cameraControl?.startFocusAndMetering(act)
            }
        }
    }


    private fun <T: UseCaseConfig.Builder<*,*,*>> applyAspectOrSize(builder: T, aspect: Aspect, size: Size?) {
        when (builder) {
            is Preview.Builder -> if (size != null) builder.setTargetResolution(size) else {
                builder.setTargetAspectRatio(
                    if (aspect == Aspect.R16_9) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
                )
            }
            is ImageCapture.Builder -> if (size != null) builder.setTargetResolution(size) else {
                builder.setTargetAspectRatio(
                    if (aspect == Aspect.R16_9) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
                )
            }
        }
    }
}
