package com.hklab.hkruler

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object CameraCapture {
    fun capture(context: Context, imageCapture: ImageCapture?, onSaved: (File) -> Unit, onError: (String) -> Unit) {
        val ic = imageCapture ?: return onError("ImageCapture is null")
        val photoFile = File(context.getExternalFilesDir(null),
            "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        ic.takePicture(opts, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) = onError(exc.message ?: "error")
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved(photoFile)
            })
    }
}
