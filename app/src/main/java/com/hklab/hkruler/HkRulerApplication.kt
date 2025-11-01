package com.hklab.hkruler

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class HkRulerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // OpenCV 로더
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "OpenCV init failed")
        }
    }
}
