package app.libretag.ui

import android.os.Bundle
import android.view.Gravity
import app.libretag.R

/** My Nostr key & backup: the owner's npub (QR, copy) and the way to Backup (SPEC.md 12.2). */
class NostrKeyActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitles(getString(R.string.menu_nostr_key))
        render()
    }

    private fun render() {
        content.removeAllViews()
        val npub = app.store.ownerNpub
        if (npub != null) {
            content.addView(qrImage("nostr:$npub"))
            content.addView(text(R.style.TextAppearance_LibreTag_Status, npub, R.color.ink2).apply {
                gravity = Gravity.CENTER; setTextIsSelectable(true)
            }, lp().margins(l = dp(24), r = dp(24)))
            content.addView(hbox {
                gravity = Gravity.CENTER
                addView(button(getString(R.string.a11y_copy_npub), Btn.OUTLINED, R.drawable.ic_copy) { copyText("npub", npub); content.snack(getString(R.string.key_copied)) })
            }, lp().margins(t = dp(8), b = dp(8)))
        } else {
            para(getString(R.string.key_no_owner))
            pad(hbox { addView(button(getString(R.string.menu_settings), Btn.OUTLINED) { startActivity(android.content.Intent(this@NostrKeyActivity, SettingsActivity::class.java)) }) })
        }
        hr()
        pad(listItem(R.drawable.ic_cloud, getString(R.string.menu_backup), getString(R.string.bk_intro), icon(R.drawable.ic_chev_r, R.color.ink2)) {
            startActivity(android.content.Intent(this@NostrKeyActivity, BackupActivity::class.java))
        })
    }
}
