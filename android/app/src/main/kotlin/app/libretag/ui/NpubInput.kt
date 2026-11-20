package app.libretag.ui

import android.text.InputType
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import app.libretag.R
import app.libretag.nostr.Bech32
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * An npub text field with a "scan QR" icon. Create it while the activity is created
 * (it registers an activity result), then [field] builds as many inputs as needed.
 */
class NpubInput(private val activity: AppCompatActivity) {
    private var target: TextInputEditText? = null
    private val launcher: ActivityResultLauncher<ScanOptions> =
        activity.registerForActivityResult(ScanContract()) { result ->
            val raw = result.contents ?: return@registerForActivityResult
            val npub = Bech32.normalizeNpub(raw)
            if (npub == null) {
                Toast.makeText(activity, activity.getString(R.string.npub_qr_none), Toast.LENGTH_LONG).show()
            } else {
                target?.setText(npub)
            }
        }

    fun field(hint: String, edit: TextInputEditText): TextInputLayout {
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        return OutlinedField(activity).apply {
            this.hint = hint
            addView(edit)
            endIconMode = TextInputLayout.END_ICON_CUSTOM
            setEndIconDrawable(R.drawable.ic_qr_scan)
            endIconContentDescription = activity.getString(R.string.a11y_scan_qr)
            setEndIconOnClickListener { scan(edit) }
        }
    }

    fun scan(edit: TextInputEditText) {
        target = edit
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(activity.getString(R.string.npub_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    companion object {
        /** null if blank, the canonical npub if valid, "" if invalid. */
        fun parse(text: CharSequence?): String? {
            val t = text?.toString()?.trim().orEmpty()
            if (t.isEmpty()) return null
            return Bech32.normalizeNpub(t) ?: ""
        }
    }
}
