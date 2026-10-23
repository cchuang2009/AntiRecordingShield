package com.antirecording.shield

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast

/**
 * Free / Pro gating. While ENFORCE is false every feature is unlocked (use this for testing).
 * Before a store release: set ENFORCE = true, add Play Billing (see PlayBilling.kt.txt) and call Pro.setPro() from it.
 */
object Pro {
    const val ENFORCE = false
    enum class Feature { REPORT_EXPORT, LAN_SCAN, VENDOR_DB }
    /** Features that need Pro once ENFORCE is true. Edit this set to change the free/paid split. */
    private val PAID = setOf(Feature.REPORT_EXPORT, Feature.LAN_SCAN, Feature.VENDOR_DB)

    /** Set by the billing layer: starts the purchase flow / restores purchases. */
    var buyHook: ((Activity) -> Unit)? = null
    var restoreHook: ((Activity) -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hush_pro", Context.MODE_PRIVATE)
    fun isPro(ctx: Context): Boolean = !ENFORCE || prefs(ctx).getBoolean("pro", false)
    fun allowed(ctx: Context, f: Feature): Boolean = !ENFORCE || f !in PAID || isPro(ctx)
    fun setPro(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("pro", v).apply() }

    fun statusText(ctx: Context): String = when {
        !ENFORCE -> L.t("Testing build: every feature is unlocked.", "測試版本：所有功能皆已解鎖。")
        isPro(ctx) -> L.t("Hush Shield Pro: active. Thank you!", "Hush Shield Pro：已啟用，感謝支持！")
        else -> L.t("Free version. Pro unlocks the room-check PDF report, LAN camera scan and the vendor database.", "免費版。Pro 可解鎖房間檢查 PDF 報告、區網攝影機掃描與廠商資料庫。")
    }

    fun paywall(act: Activity) {
        val msg = L.t(
            "Hush Shield Pro unlocks:\n• Room-check PDF report\n• LAN camera scan\n• Camera-vendor database updates\n\nEverything is processed on your phone; nothing is uploaded.",
            "Hush Shield Pro 可解鎖：\n• 房間檢查 PDF 報告\n• 區網攝影機掃描\n• 攝影機廠商資料庫更新\n\n所有資料都只在您的手機上處理，不會上傳。")
        AlertDialog.Builder(act).setTitle("Hush Shield Pro").setMessage(msg)
            .setPositiveButton(L.t("Upgrade", "升級")) { _, _ ->
                val h = buyHook
                if (h != null) h(act) else Toast.makeText(act, L.t("Billing is not set up in this build.", "此版本尚未設定付款。"), Toast.LENGTH_LONG).show()
            }
            .setNeutralButton(L.t("Restore", "還原購買")) { _, _ -> restoreHook?.invoke(act) }
            .setNegativeButton(L.t("Not now", "暫時不要"), null).show()
    }
}
