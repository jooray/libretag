package app.libretag.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.libretag.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

/** Small view helpers: the screens are built in code, styled through the theme (themes.xml). */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)
fun Context.col(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)
fun Context.isDark(): Boolean = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int = MATCH, h: Int = WRAP) = LinearLayout.LayoutParams(w, h)
fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }
fun flp(w: Int = MATCH, h: Int = WRAP, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)

fun Context.vbox(pad: Int = 0, block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (pad > 0) setPadding(pad, pad, pad, pad)
    block()
}

fun Context.hbox(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    block()
}

fun Context.text(@StyleRes style: Int, s: CharSequence? = null, @ColorRes color: Int? = null, block: TextView.() -> Unit = {}) =
    TextView(this).apply {
        setTextAppearance(style)
        text = s
        color?.let { setTextColor(col(it)) }
        block()
    }

fun Context.icon(@DrawableRes id: Int, @ColorRes tint: Int = R.color.ink, size: Int = 24): ImageView = ImageView(this).apply {
    setImageResource(id)
    imageTintList = ColorStateList.valueOf(col(tint))
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

fun Context.tinted(@DrawableRes id: Int, color: Int, sizeDp: Int = 18): Drawable =
    ContextCompat.getDrawable(this, id)!!.mutate().apply {
        setTint(color)
        setBounds(0, 0, dp(sizeDp), dp(sizeDp))
    }

/** A 48 dp icon button (top bar, rows). */
fun Context.iconButton(@DrawableRes id: Int, desc: String, @ColorRes tint: Int = R.color.ink, onClick: () -> Unit): ImageButton =
    ImageButton(this).apply {
        setImageResource(id)
        imageTintList = ColorStateList.valueOf(col(tint))
        contentDescription = desc
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

enum class Btn { FILLED, OUTLINED, TEXT, TONAL, CRIT, CRIT_OUTLINED, ON_MAP }

fun Context.button(label: CharSequence, kind: Btn = Btn.FILLED, @DrawableRes iconRes: Int? = null, onClick: (View) -> Unit = {}): MaterialButton {
    val attr = when (kind) {
        Btn.OUTLINED, Btn.CRIT_OUTLINED -> com.google.android.material.R.attr.materialButtonOutlinedStyle
        Btn.TEXT -> com.google.android.material.R.attr.borderlessButtonStyle
        else -> com.google.android.material.R.attr.materialButtonStyle
    }
    val b = MaterialButton(this, null, attr)
    when (kind) {
        Btn.TONAL -> {
            b.backgroundTintList = ColorStateList.valueOf(col(R.color.tonal))
            b.setTextColor(col(R.color.on_tonal)); b.iconTint = ColorStateList.valueOf(col(R.color.on_tonal))
        }
        Btn.CRIT -> {
            b.backgroundTintList = ColorStateList.valueOf(col(R.color.crit))
            b.setTextColor(col(R.color.on_crit)); b.iconTint = ColorStateList.valueOf(col(R.color.on_crit))
        }
        Btn.CRIT_OUTLINED -> {
            b.strokeColor = ColorStateList.valueOf(col(R.color.crit))
            b.setTextColor(col(R.color.crit)); b.iconTint = ColorStateList.valueOf(col(R.color.crit))
        }
        Btn.ON_MAP -> {
            b.backgroundTintList = ColorStateList.valueOf(col(R.color.chip))
            b.setTextColor(col(R.color.ink)); b.iconTint = ColorStateList.valueOf(col(R.color.ink))
            b.elevation = dpf(2f)
            b.setPadding(dp(16), 0, dp(16), 0)
        }
        else -> {}
    }
    b.text = label
    b.isAllCaps = false
    iconRes?.let { b.setIconResource(it); b.iconSize = dp(18); b.iconPadding = dp(8) }
    b.setOnClickListener(onClick)
    return b
}

fun Context.rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 1f): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
        strokeColor?.let { setStroke(dpf(strokeDp).toInt().coerceAtLeast(1), it) }
    }

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(col(R.color.line))
    layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, dp(1)))
}

fun Context.copyText(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun View.snack(text: CharSequence, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null): Snackbar {
    val s = Snackbar.make(this, text, if (action != null) 6000 else if (long) Snackbar.LENGTH_LONG else 4000)
    if (action != null && onAction != null) s.setAction(action) { onAction() }
    s.setActionTextColor(context.col(R.color.inverse_act))
    s.show()
    return s
}

/** Apply system-bar insets as padding (edge-to-edge): top, bottom or both. */
fun View.insetPadding(top: Boolean = false, bottom: Boolean = false, sides: Boolean = true) {
    val l0 = paddingLeft; val t0 = paddingTop; val r0 = paddingRight; val b0 = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.updatePadding(
            left = l0 + if (sides) i.left else 0,
            top = t0 + if (top) i.top else 0,
            right = r0 + if (sides) i.right else 0,
            bottom = b0 + if (bottom) maxOf(i.bottom, ime.bottom) else 0,
        )
        insets
    }
}

fun TextView.ellipsize1() { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

fun Context.str(@StringRes id: Int, vararg args: Any): String = getString(id, *args)

/** A segmented button: outlined, the checked one tonal with a check mark (M3 segmented). */
fun Context.segmentButton(label: String): MaterialButton = button(label, Btn.OUTLINED).apply {
    id = View.generateViewId()
    isCheckable = true
    backgroundTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(col(R.color.tonal), android.graphics.Color.TRANSPARENT))
    val check = tinted(R.drawable.ic_check, col(R.color.ink), 18)
    addOnCheckedChangeListener { b, c -> b.icon = if (c) check else null }
    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
}

/** An M3 outlined text field built in code (the box drawn by the layout, not the EditText). */
class OutlinedField(ctx: Context) : com.google.android.material.textfield.TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle) {
    init {
        boxBackgroundMode = BOX_BACKGROUND_OUTLINE
        val r = ctx.dpf(4f)
        setBoxCornerRadii(r, r, r, r)
        boxStrokeColor = ctx.col(R.color.focus)
        setBoxStrokeColorStateList(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(ctx.col(R.color.focus), ctx.col(R.color.outline))))
        hintTextColor = android.content.res.ColorStateList.valueOf(ctx.col(R.color.focus))
    }
    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (child is android.widget.EditText) child.background = null
        super.addView(child, index, params)
    }
}
