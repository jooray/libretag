package app.libretag.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.data.Marks
import app.libretag.data.Tracker
import app.libretag.proto.LibreTag
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * A tag shared by its owner: `libretag://share?…` carries `V_e` (and `C_e` for view and control)
 * from epoch e, never K0, so the past before e stays closed (PROTOCOL.md 1, read-only sharing).
 */
object ShareImport {
    data class Shared(val name: String, val type: String, val color: String, val symbol: String, val epoch: Long, val view: String, val cmd: String?)

    fun link(t: Tracker, epoch: Long, viewE: ByteArray, cmdE: ByteArray?): String {
        val b = Uri.Builder().scheme("libretag").authority("share")
            .appendQueryParameter("v", "1")
            .appendQueryParameter("name", t.name)
            .appendQueryParameter("type", t.type)
            .appendQueryParameter("mark", "${t.markColor}-${t.markSymbol}")
            .appendQueryParameter("e", epoch.toString())
            .appendQueryParameter("ve", LibreTag.bytesToHex(viewE))
        cmdE?.let { b.appendQueryParameter("ce", LibreTag.bytesToHex(it)) }
        return b.build().toString()
    }

    fun parse(s: String): Shared? = runCatching {
        val u = Uri.parse(s.trim())
        if (u.scheme != "libretag" || u.host != "share") return null
        val ve = u.getQueryParameter("ve")?.takeIf { LibreTag.hexToBytes(it)?.size == 32 } ?: return null
        val ce = u.getQueryParameter("ce")?.takeIf { LibreTag.hexToBytes(it)?.size == 32 }
        val mark = u.getQueryParameter("mark")?.split('-')
        Shared(u.getQueryParameter("name") ?: "Tag", u.getQueryParameter("type") ?: "thing",
            mark?.getOrNull(0)?.takeIf { it in Marks.COLORS } ?: "", mark?.getOrNull(1)?.takeIf { it in Marks.SYMBOLS } ?: "",
            u.getQueryParameter("e")?.toLongOrNull() ?: 0, ve.lowercase(), ce?.lowercase())
    }.getOrNull()

    fun scan(c: Context) = c.startActivity(Intent(c, ShareImportActivity::class.java).setAction(ShareImportActivity.ACTION_SCAN))
}

/** Scans a share QR code, or opens a `libretag://share` link, and adds the tag after a confirmation. */
class ShareImportActivity : AppCompatActivity() {
    private val scanner = registerForActivityResult(ScanContract()) { r ->
        val c = r.contents
        if (c == null) finish() else handle(c)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val data = intent?.data
        if (data != null) handle(data.toString())
        else scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.share_scan_prompt)).setBeepEnabled(false).setOrientationLocked(false))
    }

    private fun handle(s: String) {
        val sh = ShareImport.parse(s)
        if (sh == null) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.share_import_bad_title).setMessage(R.string.share_import_bad)
                .setPositiveButton(R.string.ok) { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        val app = LibreTagApp.instance
        val name = TextInputEditText(this).apply { setText(sh.name) }
        val by = TextInputEditText(this)
        val box = vbox {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(text(R.style.TextAppearance_LibreTag_BodyMedium,
                getString(if (sh.cmd != null) R.string.share_import_control else R.string.share_import_view), R.color.ink2))
            addView(OutlinedField(this@ShareImportActivity).apply { hint = getString(R.string.field_name); addView(name) }, lp().margins(t = dp(12)))
            addView(OutlinedField(this@ShareImportActivity).apply { hint = getString(R.string.share_import_by); addView(by) }, lp().margins(t = dp(8)))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_import_title)
            .setView(box)
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.share_import_ok) { _, _ ->
                val exists = app.store.trackers.value.any { it.sharedViewHex == sh.view }
                if (!exists) {
                    val used = app.store.trackers.value.map { it.markColor to it.markSymbol }
                    val mark = if (sh.color.isNotEmpty() && (sh.color to sh.symbol) !in used) sh.color to sh.symbol else Marks.free(used)
                    app.store.add(Tracker(name = name.text?.toString()?.trim().orEmpty().ifBlank { sh.name }, keyHex = "",
                        type = sh.type, markColor = mark.first, markSymbol = mark.second,
                        sharedBy = by.text?.toString()?.trim().orEmpty().ifBlank { getString(R.string.share_import_someone) },
                        sharedControl = sh.cmd != null, sharedEpoch = sh.epoch, sharedViewHex = sh.view, sharedCmdHex = sh.cmd ?: "",
                        lastN = sh.epoch * LibreTag.EPOCH_REPORTS - 1, createdAt = System.currentTimeMillis()))
                }
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object { const val ACTION_SCAN = "app.libretag.SCAN_SHARE" }
}
