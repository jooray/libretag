package app.libretag.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.widget.GridLayout
import androidx.lifecycle.lifecycleScope
import app.libretag.R
import app.libretag.backup.Bip39
import app.libretag.backup.Derived
import app.libretag.backup.KeyBackup
import app.libretag.backup.RestoreResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The recovery phrase (SPEC.md 12.2 A). Create: a fresh 12-word BIP-39 phrase, shown once (the
 * window is FLAG_SECURE: no screenshots, blank in Recents), then words 3, 7 and 11 typed back.
 * Restore: any valid 12 or 24-word mnemonic, a BIP-85 child phrase included; anything else is
 * refused. The phrase lives only in this screen's memory and is never written anywhere.
 */
class PhraseActivity : ScreenActivity() {
    private var phrase: String? = null
    private var step = STEP_SHOW
    private var busy: String? = null
    private val restoreMode get() = intent.getStringExtra(EXTRA_MODE) == MODE_RESTORE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (!restoreMode) phrase = Bip39.generate()
        setTitles(getString(if (restoreMode) R.string.ph_restore_title else R.string.ph_create_title))
        render()
    }

    override fun onDestroy() {
        phrase = null
        super.onDestroy()
    }

    private fun render() {
        content.removeAllViews()
        if (restoreMode) renderRestore() else if (step == STEP_SHOW) renderShow() else renderCheck()
        busy?.let {
            para(it)
            content.addView(LinearProgressIndicator(this).apply { isIndeterminate = true }, lp().margins(l = dp(16), r = dp(16)))
        }
    }

    // ------------------------------------------------------------------ create

    private fun renderShow() {
        val words = phrase!!.split(' ')
        para(getString(R.string.ph_create_text))
        val grid = GridLayout(this).apply {
            columnCount = 2
            words.forEachIndexed { i, w ->
                // column-major: 1-6 left, 7-12 right, as people copy it onto paper
                val col = i / 6
                val row = i % 6
                addView(text(R.style.TextAppearance_LibreTag_BodyLarge, "${i + 1}. $w").apply {
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    setTextIsSelectable(false)
                }, GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(col, 1f)).apply { width = 0 })
            }
        }
        content.addView(vbox {
            background = rounded(col(R.color.surf2), 12f, col(R.color.line))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(grid, lp())
        }, lp().margins(l = dp(16), r = dp(16), t = dp(8), b = dp(8)))
        pad(hbox { addView(button(getString(R.string.ph_written), Btn.FILLED) { step = STEP_CHECK; render() }) })
        para(getString(R.string.ph_bip85))
    }

    private fun renderCheck() {
        h3(getString(R.string.ph_check_title))
        para(getString(R.string.ph_check_text))
        val asks = listOf(3, 7, 11)
        val fields = asks.map { n ->
            val e = TextInputEditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            pad(OutlinedField(this).apply { hint = getString(R.string.ph_word_n, n); addView(e) })
            n to e
        }
        pad(FlowRow(this).apply {
            addView(button(getString(R.string.ph_check), Btn.FILLED) {
                val words = phrase!!.split(' ')
                val ok = fields.all { (n, e) -> Bip39.normalize(e.text?.toString().orEmpty()) == words[n - 1] }
                if (ok) save() else content.snack(getString(R.string.ph_check_wrong))
            }.apply { isEnabled = busy == null })
            addView(button(getString(R.string.ph_back_to_words), Btn.TEXT) { step = STEP_SHOW; render() }.apply { isEnabled = busy == null })
        })
    }

    private fun save() {
        val p = phrase ?: return
        busy = getString(R.string.ph_deriving); render()
        lifecycleScope.launch {
            val d = withContext(Dispatchers.Default) { KeyBackup.deriveFromPhrase(p) } ?: return@launch
            busy = getString(R.string.ph_saving); render()
            app.backup.usePhrase(d)
            busy = null
            phrase = null
            val st = app.backup.state.value.phrase
            if (st.savedAt > 0 && st.problem == null) {
                setResult(RESULT_OK)
                finishWith(getString(R.string.ph_saved))
            } else {
                // keys are kept; the Backup screen shows the problem and "Save now"
                finishWith(getString(R.string.backup_failed))
            }
        }
    }

    private fun finishWith(msg: String) {
        startActivity(Intent(this, BackupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(BackupActivity.EXTRA_MESSAGE, msg))
        finish()
    }

    // ------------------------------------------------------------------ restore

    private var typed: String = ""

    private fun renderRestore() {
        content.addView(card(getString(R.string.ph_warning), R.drawable.ic_alert) {}, lp().margins(l = dp(16), r = dp(16), t = dp(8), b = dp(8)))
        val helper = text(R.style.TextAppearance_LibreTag_Status, "", R.color.ink2)
        val restoreBtn = button(getString(R.string.ph_restore), Btn.FILLED) { restore(typed) }
        val e = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(typed)
        }
        fun update() {
            val c = Bip39.check(typed)
            val n = Bip39.split(typed).size
            helper.text = when (c) {
                is Bip39.Check.Ok -> getString(R.string.ph_ok)
                is Bip39.Check.Bad -> when (c.problem) {
                    Bip39.Problem.EMPTY -> ""
                    Bip39.Problem.UNKNOWN_WORD -> getString(R.string.ph_unknown_word, c.word)
                    Bip39.Problem.WRONG_LENGTH -> resources.getQuantityString(R.plurals.ph_words, n, n) + ". " + getString(R.string.ph_wrong_length)
                    Bip39.Problem.BAD_CHECKSUM -> getString(R.string.ph_bad_checksum)
                }
            }
            restoreBtn.isEnabled = c is Bip39.Check.Ok && busy == null
        }
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { typed = s?.toString().orEmpty(); update() }
        })
        pad(OutlinedField(this).apply { hint = getString(R.string.ph_field); addView(e) })
        content.addView(helper, lp().margins(l = dp(16), r = dp(16), t = dp(4), b = dp(8)))
        pad(hbox { addView(restoreBtn) })
        update()
    }

    private fun restore(input: String) {
        val ok = Bip39.check(input) as? Bip39.Check.Ok ?: return
        busy = getString(R.string.ph_deriving); render()
        lifecycleScope.launch {
            val d: Derived = withContext(Dispatchers.Default) { KeyBackup.derive(ok.phrase) }
            busy = getString(R.string.ph_searching); render()
            val (r, added) = app.backup.restoreWithPhrase(d)
            busy = null
            when {
                added < 0 -> { render(); content.snack(getString(R.string.ph_offline)) }
                r is RestoreResult.Found -> {
                    typed = ""
                    finishWith(if (added > 0) resources.getQuantityString(R.plurals.backup_restored, added, added) else getString(R.string.ph_all_here))
                }
                r == RestoreResult.Deleted -> { render(); content.snack(getString(R.string.backup_restore_deleted)) }
                r == RestoreResult.Unreadable -> { render(); content.snack(getString(R.string.backup_restore_unreadable)) }
                else -> {
                    render()
                    MaterialAlertDialogBuilder(this@PhraseActivity).setTitle(R.string.ph_none_title).setMessage(R.string.ph_none_text)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.ph_use_new) { _, _ ->
                            busy = getString(R.string.ph_saving); render()
                            lifecycleScope.launch {
                                app.backup.usePhrase(d)
                                busy = null
                                typed = ""
                                finishWith(getString(R.string.ph_saved))
                            }
                        }.show()
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val MODE_RESTORE = "restore"
        private const val STEP_SHOW = 0
        private const val STEP_CHECK = 1
        fun create(c: Context) = Intent(c, PhraseActivity::class.java)
        fun restore(c: Context) = Intent(c, PhraseActivity::class.java).putExtra(EXTRA_MODE, MODE_RESTORE)
    }
}
