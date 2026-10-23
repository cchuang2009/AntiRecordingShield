package com.antirecording.shield

import android.content.Context
import java.util.Locale

/**
 * UI language. mode 0 = follow the phone's language (Chinese phones -> 中文, everything else -> English), 1 = English, 2 = 中文.
 * Use L.t("English text", "中文文字") for every user-visible string; the activity is recreated when the mode changes.
 */
object L {
    private var mode = 0
    fun load(ctx: Context) { mode = ctx.getSharedPreferences("hush_lang", Context.MODE_PRIVATE).getInt("mode", 0) }
    fun setMode(ctx: Context, m: Int) { mode = m; ctx.getSharedPreferences("hush_lang", Context.MODE_PRIVATE).edit().putInt("mode", m).apply() }
    fun mode(): Int = mode
    val zh: Boolean get() = when (mode) { 1 -> false; 2 -> true; else -> Locale.getDefault().language.equals("zh", ignoreCase = true) }
    fun t(en: String, cn: String): String = if (zh) cn else en
}
