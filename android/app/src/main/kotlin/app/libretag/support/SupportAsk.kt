package app.libretag.support

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.libretag.R
import app.libretag.ui.BlazeDrawable
import app.libretag.ui.Btn
import app.libretag.ui.FlowRow
import app.libretag.ui.PlateDrawable
import app.libretag.ui.WRAP
import app.libretag.ui.button
import app.libretag.ui.col
import app.libretag.ui.dp
import app.libretag.ui.flp
import app.libretag.ui.isDark
import app.libretag.ui.lp
import app.libretag.ui.margins
import app.libretag.ui.openUrl
import app.libretag.ui.snack
import app.libretag.ui.text
import app.libretag.ui.vbox
import java.time.LocalDate
import java.util.Locale

/**
 * LibreTag's adapter for the shared support ask (the support widget spec, ask.md): 7 days of use
 * and 10 actions (opening a tag's detail, a command, a pairing), shown only at a moment of value
 * (a command the tag confirmed, a finished pairing) and never next to a tag in Lost, Alarm or
 * Silent. Decides locally, stores `support.v1`, opens the checkout in the browser; no network.
 */
object SupportAsk {
    private const val PREFS = "support_prompt"
    private const val KEY = "support.v1"
    /** the tag (or "paired") the strip is currently up for: it stays until answered */
    private var showingFor: String? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun load(c: Context): SupportEngine.State = SupportState.decode(prefs(c).getString(KEY, null))

    @Synchronized private fun update(c: Context, f: (SupportEngine.State) -> SupportEngine.State) {
        prefs(c).edit().putString(KEY, SupportState.encode(f(load(c)))).apply()
    }

    fun onLaunch(c: Context) = update(c) { SupportEngine.onLaunch(it, LocalDate.now().toString()) }
    fun onAction(c: Context) = update(c) { SupportEngine.onAction(it) }

    /**
     * At a moment of value: true if the strip should be up for [key]. Counts as shown the first
     * time; [busy] (a tag in Lost, Alarm or Silent) never asks.
     */
    @Synchronized
    fun consider(c: Context, key: String, busy: Boolean): Boolean {
        if (busy) return false
        if (showingFor == key) return true
        if (showingFor != null) return false
        val ctx = SupportEngine.Context(LocalDate.now().toString(), momentOfValue = true, busy = false)
        if (!SupportEngine.shouldAsk(load(c), ctx, SupportEngine.LIBRE_TAG)) return false
        update(c) { SupportEngine.onShown(it, ctx.today) }
        showingFor = key
        return true
    }

    fun isShowing(key: String) = showingFor == key

    fun dismiss() { showingFor = null }

    private fun url(c: Context, ref: String) = SupportCheckout.url(ref, c.isDark(), Locale.getDefault().language)

    /** The menu entry: opens the checkout, changes nothing (works after "Don't ask again"). */
    fun openFromMenu(c: Context) = c.openUrl(url(c, SupportCheckout.REF_MENU))

    /**
     * The strip as a Trail Blaze signpost plate: white plate, graphite edge, yellow stripe blaze.
     * [onGone] re-renders the host after Support, Not now or Don't ask again.
     */
    fun strip(c: Context, ref: String, onGone: () -> Unit): View {
        val plate = PlateDrawable(c, c.col(R.color.plate_bg), c.col(R.color.plate))
        return FrameLayout(c).apply {
            background = plate
            setPadding(dp(12), dp(12), plate.tipWidth + dp(4), dp(12))
            contentDescription = null
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                addView(ImageView(c).apply {
                    setImageDrawable(BlazeDrawable(c, "yellow", "stripe"))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(24), dp(28)))
                addView(c.vbox {
                    addView(c.text(R.style.TextAppearance_LibreTag_BodyMedium, c.getString(R.string.support_text)).apply {
                        setTextColor(c.col(R.color.ink))
                        typeface = androidx.core.content.res.ResourcesCompat.getFont(c, R.font.archivo_semibold)
                    })
                    addView(FlowRow(c).apply {
                        addView(c.button(c.getString(R.string.support_yes), Btn.FILLED, R.drawable.ic_heart) {
                            update(c) { SupportEngine.onSupportTapped(it) }
                            dismiss()
                            c.openUrl(url(c, ref))
                            onGone()
                        })
                        addView(c.button(c.getString(R.string.support_later), Btn.TEXT) { dismiss(); onGone() })
                        addView(c.button(c.getString(R.string.support_never), Btn.TEXT) { v ->
                            update(c) { SupportEngine.onDeclined(it) }
                            dismiss()
                            v.rootView.findViewById<View>(android.R.id.content)?.snack(c.getString(R.string.support_never_snack))
                            onGone()
                        })
                    }, lp().margins(t = dp(10)))
                }, LinearLayout.LayoutParams(0, WRAP, 1f).margins(l = dp(12)))
            }
            addView(row, flp())
        }
    }
}
