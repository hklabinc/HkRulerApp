package com.hklab.hkruler

import android.content.Context
import java.io.File

object FileUtils {
    fun appDir(context: Context, name: String): File =
        File(context.getExternalFilesDir(null), name).apply { mkdirs() }
}
