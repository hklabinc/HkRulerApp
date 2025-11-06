// com/hklab/hkruler/access/AutoReturnAccessService.kt
package com.hklab.hkruler.access

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

class AutoReturnAccessService : AccessibilityService() {

    companion object {
        @Volatile private var sInstance: AutoReturnAccessService? = null

        fun instance(): AutoReturnAccessService? = sInstance

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

    /** HOME → 잠깐 대기 → HkRuler 실행(NEW_TASK) */
    fun returnToHkRuler() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            if (launch != null) startActivity(launch)
        }, 250)
    }
}
