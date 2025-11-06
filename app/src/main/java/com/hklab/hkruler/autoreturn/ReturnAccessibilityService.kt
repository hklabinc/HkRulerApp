// com/hklab/hkruler/access/AutoReturnAccessService.kt
package com.hklab.hkruler.autoreturn

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

class ReturnAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var sInstance: ReturnAccessibilityService? = null

        fun instance(): ReturnAccessibilityService? = sInstance

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val list = am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            )
            return list.any {
                val si = it.resolveInfo?.serviceInfo
                si?.packageName == context.packageName &&
                        si.name?.endsWith("AutoReturnAccessService") == true
            }
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); sInstance = this }
    override fun onUnbind(intent: Intent?): Boolean { sInstance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** 항상 복귀: HOME → (짧게 여러 번) HkRuler 실행 */
    fun returnToHkRuler() {
        // 1) 홈으로 (카메라가 백그라운드로 내려감: '프로' 모드 그대로 유지)
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2) 전환 안정화를 기다리면서 여러 번 짧게 시도
        val main = Handler(Looper.getMainLooper())
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        } ?: return

        // 첫 시도는 "빠르게", 이후는 "안정성" 보강
        val attempts = longArrayOf(150L, 300L, 600L) // ← 필요시 120/300/600으로 조정 가능

        attempts.forEach { delayMs ->
            main.postDelayed({ startActivity(intent) }, delayMs)
        }
    }
}
