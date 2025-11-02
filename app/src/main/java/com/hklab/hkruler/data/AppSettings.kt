package com.hklab.hkruler.data

import android.content.Context
import android.util.Size

enum class Aspect { R16_9, R4_3 }
enum class FocusMode { AUTO_CENTER, AUTO_MULTI }
enum class CaptureSource { MY_APP, SYSTEM_APP }

data class AppSettings(
    val aspect: Aspect = Aspect.R16_9,
    val previewSize: Size? = null,
    val photoSize: Size? = null,
    val focusMode: FocusMode = FocusMode.AUTO_MULTI,
    val evIndex: Int = 0,
    val alignAssist: Boolean = false,
    val captureSource: CaptureSource = CaptureSource.MY_APP
)

object SettingsStore {
    private const val P = "hk_settings"

    fun load(ctx: Context): AppSettings {
        val sp = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val aspect = Aspect.valueOf(sp.getString("aspect", Aspect.R16_9.name)!!)
        val prwW = sp.getInt("prwW", 0)
        val prwH = sp.getInt("prwH", 0)
        val phoW = sp.getInt("phoW", 0)
        val phoH = sp.getInt("phoH", 0)
        val focus = FocusMode.valueOf(sp.getString("focus", FocusMode.AUTO_MULTI.name)!!)
        val ev = sp.getInt("ev", 0)
        val align = sp.getBoolean("align", false)
        val cap = CaptureSource.valueOf(sp.getString("cap", CaptureSource.MY_APP.name)!!)
        return AppSettings(
            aspect,
            if (prwW > 0) Size(prwW, prwH) else null,
            if (phoW > 0) Size(phoW, phoH) else null,
            focus, ev, align, cap
        )
    }

    fun save(ctx: Context, s: AppSettings) {
        val sp = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
        sp.putString("aspect", s.aspect.name)
        sp.putInt("prwW", s.previewSize?.width ?: 0)
        sp.putInt("prwH", s.previewSize?.height ?: 0)
        sp.putInt("phoW", s.photoSize?.width ?: 0)
        sp.putInt("phoH", s.photoSize?.height ?: 0)
        sp.putString("focus", s.focusMode.name)
        sp.putInt("ev", s.evIndex)
        sp.putBoolean("align", s.alignAssist)
        sp.putString("cap", s.captureSource.name)
        sp.apply()
    }
}
