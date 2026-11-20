package app.libretag.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import app.libretag.LibreTagApp
import app.libretag.R

/** A secondary screen: a top bar with Back and a title, and a scrolling column (max 720 dp wide). */
abstract class ScreenActivity : AppCompatActivity() {
    protected val app get() = LibreTagApp.instance
    protected lateinit var content: LinearLayout
    protected lateinit var bar: LinearLayout
    protected lateinit var titleView: android.widget.TextView
    protected lateinit var subView: android.widget.TextView
    protected lateinit var rootCol: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        titleView = text(R.style.TextAppearance_LibreTag_TitleLarge).apply { ellipsize1(); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }
        subView = text(R.style.TextAppearance_LibreTag_LabelMedium, color = R.color.ink3).apply { ellipsize1(); visibility = View.GONE }
        bar = hbox {
            minimumHeight = dp(64)
            setPadding(dp(4), 0, dp(8), 0)
            addView(iconButton(R.drawable.ic_back, getString(R.string.a11y_back)) { onBackPressedDispatcher.onBackPressed() })
            addView(vbox { addView(titleView); addView(subView) }, LinearLayout.LayoutParams(0, WRAP, 1f).margins(l = dp(4)))
        }
        content = vbox { setPadding(0, dp(8), 0, dp(32)) }
        val scroll = NestedScrollView(this).apply {
            addView(android.widget.FrameLayout(this@ScreenActivity).apply {
                addView(content, flp(MATCH, WRAP, android.view.Gravity.CENTER_HORIZONTAL).apply { width = MATCH })
            })
        }
        content.layoutParams = flp(MATCH, WRAP, android.view.Gravity.CENTER_HORIZONTAL)
        rootCol = vbox {
            setBackgroundColor(col(R.color.bg))
            addView(bar, lp())
            addView(divider())
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        setContentView(rootCol)
        rootCol.insetPadding(top = true, bottom = true)
        // keep long lines readable on a tablet
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            val max = dp(720)
            val lpc = content.layoutParams as android.widget.FrameLayout.LayoutParams
            val w = if (scroll.width > max) max else MATCH
            if (lpc.width != w) { lpc.width = w; content.layoutParams = lpc }
        }
    }

    fun setTitles(title: String, sub: String? = null) {
        titleView.text = title
        subView.text = sub
        subView.visibility = if (sub != null) View.VISIBLE else View.GONE
        this.title = title
    }

    protected fun h3(s: String) = content.addView(text(R.style.TextAppearance_LibreTag_TitleSmall, s).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) },
        lp().margins(l = dp(16), t = dp(20), r = dp(16), b = dp(4)))
    protected fun pad(v: View) = content.addView(v, lp().margins(l = dp(16), r = dp(16)))
    protected fun para(s: CharSequence) = content.addView(text(R.style.TextAppearance_LibreTag_BodyMedium, s, R.color.ink2),
        lp().margins(l = dp(16), t = dp(8), r = dp(16), b = dp(8)))
    protected fun hr() = content.addView(divider(), lp().margins(t = dp(4), b = dp(4)))

    /** A card (surf2 ground, 1 dp line, 12 dp corners) with a title row. */
    protected fun card(title: String, icon: Int? = null, block: LinearLayout.() -> Unit): LinearLayout = vbox {
        background = rounded(col(R.color.surf2), 12f, col(R.color.line))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(text(R.style.TextAppearance_LibreTag_Section, title).apply {
            icon?.let { setCompoundDrawablesRelative(tinted(it, col(R.color.ink), 22), null, null, null); compoundDrawablePadding = dp(8) }
        })
        block()
        layoutParams = lp().margins(l = dp(16), t = dp(12), r = dp(16), b = dp(12))
    }
}
