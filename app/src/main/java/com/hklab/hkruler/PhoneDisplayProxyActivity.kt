package com.hklab.hkruler

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast

/**
 * 폰 디스플레이에 잠시 올라가서 대상(메인/카메라)을 실행하고 곧바로 종료하는 프록시.
 * - ACTION_OPEN_MAIN  : HkRuler MainActivity 재실행(폰 화면 고정)
 * - ACTION_OPEN_CAMERA: 삼성 카메라(전체 UI) 실행(폰 화면 고정)
 */
class PhoneDisplayProxyActivity : Activity() {

    companion object {
        const val ACTION_OPEN_MAIN   = "com.hklab.hkruler.action.OPEN_MAIN"
        const val ACTION_OPEN_CAMERA = "com.hklab.hkruler.action.OPEN_CAMERA"
        const val EXTRA_REANCHORED   = "reanchored"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            when (intent?.action) {
                ACTION_OPEN_MAIN -> {
                    // HkRuler 메인 실행 (이미 실행 중이면 앞으로)
                    val i = Intent(this, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        putExtra(EXTRA_REANCHORED, true)
                    }
                    startActivity(i)
                }
                ACTION_OPEN_CAMERA -> {
                    // 삼성 카메라 전체 UI (런처 인텐트 우선, 폴백은 시스템 전체 카메라 UI)
                    val samsungPkg = "com.sec.android.app.camera"
                    val launchSamsung = packageManager.getLaunchIntentForPackage(samsungPkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    val genericStill = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    startActivity(launchSamsung ?: genericStill)
                }
                else -> {
                    // 방어적으로 메인을 띄움
                    val i = Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "폰 화면 실행 실패: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        } finally {
            // 사용자에게 보일 필요가 없으므로 즉시 종료
            finish()
        }
    }
}
