package com.hklab.hkruler.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.*

object FileUtils {
    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun newPhotoUri(context: Context): Uri {
        val fileName = "IMG_${fmt.format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HkRuler")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return requireNotNull(uri)
    }

    fun markCompleted(context: Context, uri: Uri) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
    }
}
