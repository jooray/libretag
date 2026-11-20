package app.libretag.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.Reach
import app.libretag.data.Status
import app.libretag.data.TagStatus
import app.libretag.data.Tracker

/** The top app bar of the home screen: "LibreTag", the connection chip and the app menu. */
@SuppressLint("ViewConstructor")
class HomeBar(ctx: Context, onConn: () -> Unit, private val onMenu: (View) -> Unit) : LinearLayout(ctx) {
    private val dot = View(ctx)
    private val conn = ctx.text(R.style.TextAppearance_LibreTag_Status)
    private val chip = ctx.hbox {
        background = ctx.rounded(0, 18f, ctx.col(R.color.line))
        setPadding(dp(12), 0, dp(12), 0)
        minimumHeight = dp(36)
        addView(dot, LayoutParams(dp(9), dp(9)).margins(r = dp(8)))
        addView(conn)
        isClickable = true
        isFocusable = true
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        foreground = ctx.getDrawable(tv.resourceId)
        setOnClickListener { onConn() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(16), 0, dp(4), 0)
        addView(ctx.text(R.style.TextAppearance_LibreTag_TitleLarge, ctx.getString(R.string.app_name)).apply {
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            ellipsize1()
        }, LayoutParams(0, WRAP, 1f))
        addView(chip, LayoutParams(WRAP, dp(36)).margins(r = dp(2)))
        addView(ctx.iconButton(R.drawable.ic_more, ctx.getString(R.string.a11y_more)) { onMenu(this) })
        setBackgroundColor(ctx.col(R.color.bg))
    }

    fun bind(reach: Reach) {
        val ctx = context
        val (label, fill) = when (reach) {
            Reach.MESH -> ctx.getString(R.string.conn_mesh) to ctx.col(R.color.ok)
            Reach.NOSTR -> ctx.getString(R.string.conn_nostr) to ctx.col(R.color.nostr)
            Reach.OFFLINE -> ctx.getString(R.string.conn_offline) to 0
        }
        conn.text = label
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (reach == Reach.OFFLINE) setStroke(dp(2), ctx.col(R.color.ink3))
        }
        chip.contentDescription = ctx.getString(R.string.a11y_connection, label)
    }

    /** the menu anchor (overflow button) */
    val anchor: View get() = getChildAt(childCount - 1)
}

/** The tag detail's top bar: back or close, the blaze, name, "type · mark · 2 of 6", prev/next, menu. */
@SuppressLint("ViewConstructor")
class DetailHead(ctx: Context, back: Boolean, onBack: () -> Unit, private val onStep: (Int) -> Unit, private val onMenu: (View) -> Unit) : LinearLayout(ctx) {
    private val blaze = BlazeDrawable(ctx, "yellow", "stripe")
    private val title = ctx.text(R.style.TextAppearance_LibreTag_TitleLarge).apply { ellipsize1() }
    private val sub = ctx.text(R.style.TextAppearance_LibreTag_LabelMedium, color = R.color.ink3).apply { ellipsize1() }
    private val prev = ctx.iconButton(R.drawable.ic_chev_l, ctx.getString(R.string.a11y_prev_tag)) { onStep(-1) }
    private val next = ctx.iconButton(R.drawable.ic_chev_r, ctx.getString(R.string.a11y_next_tag)) { onStep(1) }
    val menu = ctx.iconButton(R.drawable.ic_more, ctx.getString(R.string.a11y_tag_options)) { onMenu(it()) }
    private fun it(): View = menu

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(4), 0, dp(4), 0)
        setBackgroundColor(ctx.col(R.color.bg))
        if (back) addView(ctx.iconButton(R.drawable.ic_back, ctx.getString(R.string.a11y_back)) { onBack() })
        else setPadding(dp(16), 0, dp(4), 0)
        addView(ImageView(ctx).apply { setImageDrawable(blaze); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
            LayoutParams(dp(25), dp(30)).margins(l = dp(if (back) 4 else 0), r = dp(12)))
        addView(ctx.vbox { addView(title); addView(sub) }, LayoutParams(0, WRAP, 1f))
        addView(prev); addView(next); addView(menu)
        if (!back) addView(ctx.iconButton(R.drawable.ic_close, ctx.getString(R.string.a11y_close_detail)) { onBack() })
        elevation = 0f
    }

    fun bind(t: Tracker, index: Int, count: Int) {
        val ctx = context
        blaze.color = t.markColor; blaze.symbol = t.markSymbol; blaze.invalidateSelf()
        title.text = t.name
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true)
        sub.text = if (t.isShared) ctx.getString(R.string.detail_sub_shared, t.sharedBy, index + 1, count)
            else ctx.getString(R.string.detail_sub, TagText.typeName(ctx, t.type),
                BlazeColors.markName(ctx, t.markColor, t.markSymbol).lowercase(), index + 1, count)
        prev.isEnabled = index > 0; prev.alpha = if (index > 0) 1f else 0.38f
        next.isEnabled = index < count - 1; next.alpha = if (index < count - 1) 1f else 0.38f
    }
}

/** The list of signpost rows (home sheet, list pane), with its header and banners. */
@SuppressLint("ViewConstructor")
class TagListView(ctx: Context, private val onOpen: (String) -> Unit, private val onBanner: (String) -> Unit) : LinearLayout(ctx) {
    val header = ctx.hbox { setPadding(dp(16), 0, dp(8), dp(6)); minimumHeight = dp(40) }
    val headerText = ctx.text(R.style.TextAppearance_LibreTag_Band, color = R.color.ink2).apply { textSize = 14f }
    private val banners = ctx.vbox()
    /** 0.12: strips above the rows that belong to no tag (a bootloader on the cable) */
    val extra = ctx.vbox { setPadding(dp(12), 0, dp(12), 0) }
    val recycler = RecyclerView(ctx)
    private val adapter = RowAdapter()
    val empty: View
    var listOrderNote = false

    init {
        orientation = VERTICAL
        header.addView(headerText, LayoutParams(0, WRAP, 1f))
        addView(header, lp())
        addView(banners, lp())
        addView(extra, lp())
        recycler.layoutManager = LinearLayoutManager(ctx)
        recycler.adapter = adapter
        recycler.clipToPadding = false
        recycler.setPadding(dp(12), dp(2), dp(12), dp(96))
        recycler.itemAnimator = null
        addView(recycler, LayoutParams(MATCH, 0, 1f))
        empty = emptyState(ctx)
        addView(empty, LayoutParams(MATCH, 0, 1f))
    }

    private fun emptyState(ctx: Context): View = ctx.vbox {
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(24), dp(32), dp(96))
        addView(ImageView(ctx).apply { setImageDrawable(CableIllustration(ctx)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
            LayoutParams(dp(220), dp(138)).margins(b = dp(16)))
        addView(ctx.text(R.style.TextAppearance_LibreTag_BodyLarge, ctx.getString(R.string.empty_title)).apply {
            gravity = Gravity.CENTER; textSize = 17f; maxWidth = dp(320)
        })
        addView(ctx.text(R.style.TextAppearance_LibreTag_BodySmall, ctx.getString(R.string.empty_sub), R.color.ink3).apply {
            gravity = Gravity.CENTER; maxWidth = dp(340)
        }, lp(WRAP, WRAP).margins(t = dp(16)))
        addView(ctx.button(ctx.getString(R.string.empty_scan_shared), Btn.TEXT, R.drawable.ic_qr) { onBanner("scan-share") }, lp(WRAP, WRAP).margins(t = dp(12)))
        addView(ctx.button(ctx.getString(R.string.bk_restore_first), Btn.TEXT, R.drawable.ic_download) { onBanner("restore-backup") }, lp(WRAP, WRAP))
    }

    fun render(tags: List<Tracker>, selected: String?, reach: Reach, now: Long = System.currentTimeMillis()) {
        val ctx = context
        val statuses = tags.associate { it.id to Status.of(it, now) }
        val need = statuses.values.count { it.needsLook }
        val count = ctx.resources.getQuantityString(R.plurals.home_tags, tags.size, tags.size)
        val head = if (need > 0) ctx.getString(R.string.home_count_need, count, ctx.resources.getQuantityString(R.plurals.home_need_look, need, need)) else count
        headerText.text = if (listOrderNote && need == 0) ctx.getString(R.string.home_list_order, count) else head
        header.visibility = if (tags.isEmpty()) GONE else VISIBLE
        empty.visibility = if (tags.isEmpty()) VISIBLE else GONE
        recycler.visibility = if (tags.isEmpty()) GONE else VISIBLE
        banners.removeAllViews()
        if (tags.isNotEmpty()) {
            when (reach) {
                Reach.OFFLINE -> banners.addView(banner(R.drawable.ic_wifioff, ctx.getString(R.string.banner_offline)))
                Reach.NOSTR -> banners.addView(banner(R.drawable.ic_cloud, ctx.getString(R.string.banner_nostr)))
                else -> {}
            }
            val db = LibreTagApp.instance.repeaters
            val needsDb = tags.any { t -> t.last?.let { !it.hasFix && it.neighbours.isNotEmpty() } == true }
            if (needsDb && db.downloadedAt == 0L) {
                banners.addView(banner(R.drawable.ic_rpt, ctx.getString(R.string.banner_no_repeaters), ctx.getString(R.string.banner_action_map)) { onBanner("repeaters") })
            } else if (db.downloadedAt > 0 && now - db.downloadedAt > 30 * 86_400_000L) {
                banners.addView(banner(R.drawable.ic_rpt, ctx.getString(R.string.banner_old_repeaters,
                    Format.duration(ctx, now - db.downloadedAt)), ctx.getString(R.string.banner_action_map)) { onBanner("repeaters") })
            }
        }
        adapter.submit(tags, statuses, selected, now)
    }

    private fun banner(icon: Int, text: String, action: String? = null, onClick: (() -> Unit)? = null): View {
        val ctx = context
        return ctx.hbox {
            gravity = Gravity.TOP
            background = ctx.rounded(ctx.col(R.color.surf3), 8f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(ctx.icon(icon, R.color.ink, 18).apply { (layoutParams as LayoutParams).topMargin = dp(1) })
            addView(ctx.vbox {
                addView(ctx.text(R.style.TextAppearance_LibreTag_Status, text).apply { accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE })
                if (action != null && onClick != null) addView(ctx.button(action, Btn.TEXT) { onClick() }.apply {
                    setPadding(0, 0, 0, 0); minWidth = 0
                }, lp(WRAP, WRAP))
            }, LayoutParams(0, WRAP, 1f).margins(l = dp(10)))
            layoutParams = lp().margins(l = dp(12), r = dp(12), b = dp(8))
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var tags: List<Tracker> = emptyList()
        private var statuses: Map<String, TagStatus> = emptyMap()
        private var selected: String? = null
        private var now = 0L

        fun submit(t: List<Tracker>, s: Map<String, TagStatus>, sel: String?, n: Long) {
            tags = t; statuses = s; selected = sel; now = n
            notifyDataSetChanged()
        }

        override fun getItemCount() = tags.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(TagRowView(parent.context)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val t = tags[position]
            (holder.itemView as TagRowView).apply {
                bind(t, statuses[t.id] ?: Status.of(t, now), t.id == selected, now)
                setOnClickListener { onOpen(t.id) }
            }
        }
    }
}

/** Swipe between tags: one [TagBodyView] per page. */
class BodyAdapter(private val host: DetailHost, private val onScroll: (Int) -> Unit) : RecyclerView.Adapter<BodyAdapter.H>() {
    var tags: List<Tracker> = emptyList()
    class H(val body: TagBodyView) : RecyclerView.ViewHolder(body)

    override fun getItemCount() = tags.size
    override fun getItemId(position: Int) = tags[position].id.hashCode().toLong()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = H(TagBodyView(parent.context, host).apply {
        layoutParams = RecyclerView.LayoutParams(MATCH, MATCH)
        onScrollChange = onScroll
    })
    override fun onBindViewHolder(holder: H, position: Int) = holder.body.bind(tags[position])
    fun bodyFor(pager: ViewPager2, id: String): TagBodyView? {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val b = (rv.getChildViewHolder(rv.getChildAt(i)) as? H)?.body ?: continue
            if (b.tagId == id) return b
        }
        return null
    }
}

/** A cable from the phone to a tag: the empty state and the wizard's first step. */
class CableIllustration(private val ctx: Context) : android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val blaze = BlazeDrawable(ctx, "yellow", "triangle")
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val s = minOf(b.width() / 240f, b.height() / 150f)
        c.save()
        c.translate(b.left + (b.width() - 240 * s) / 2, b.top + (b.height() - 150 * s) / 2)
        c.scale(s, s)
        val ink = ctx.col(R.color.ink)
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 3f; p.color = ink; p.strokeCap = android.graphics.Paint.Cap.ROUND
        c.drawRoundRect(18f, 14f, 82f, 136f, 10f, 10f, p)
        p.style = android.graphics.Paint.Style.FILL
        c.drawRoundRect(40f, 22f, 60f, 25f, 1.5f, 1.5f, p)
        c.drawRoundRect(42f, 132f, 58f, 140f, 2f, 2f, p)
        p.style = android.graphics.Paint.Style.STROKE
        val path = android.graphics.Path().apply {
            moveTo(50f, 140f); lineTo(50f, 144f); cubicTo(50f, 148f, 53f, 150f, 58f, 150f); lineTo(154f, 150f)
            cubicTo(162f, 150f, 166f, 146f, 166f, 140f); lineTo(166f, 110f)
        }
        c.drawPath(path, p)
        p.style = android.graphics.Paint.Style.FILL
        c.drawRoundRect(159f, 98f, 173f, 108f, 2f, 2f, p)
        p.color = ctx.col(R.color.plate_bg)
        c.drawRoundRect(150f, 40f, 220f, 98f, 10f, 10f, p)
        p.style = android.graphics.Paint.Style.STROKE; p.color = ink
        c.drawRoundRect(150f, 40f, 220f, 98f, 10f, 10f, p)
        c.drawLine(206f, 40f, 206f, 24f, p)
        blaze.setBounds(176, 52, 201, 82)
        blaze.draw(c)
        c.restore()
    }
    override fun getIntrinsicWidth() = ctx.dp(240)
    override fun getIntrinsicHeight() = ctx.dp(150)
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** A popup menu with icons (app menu, tag menu); [danger] items are red. */
fun Context.iconMenu(anchor: View, items: List<Triple<Int, Int, Boolean>>, onPick: (Int) -> Unit) {
    val pm = PopupMenu(this, anchor, Gravity.END)
    items.forEachIndexed { i, (label, icon, danger) ->
        val title = getString(label)
        val it = pm.menu.add(0, i, i, if (danger) android.text.SpannableString(title).apply {
            setSpan(android.text.style.ForegroundColorSpan(col(R.color.crit)), 0, length, 0)
        } else title)
        it.icon = tinted(icon, col(if (danger) R.color.crit else R.color.ink2), 24)
    }
    pm.setForceShowIcon(true)
    pm.setOnMenuItemClickListener { onPick(items[it.itemId].first); true }
    pm.show()
}

/** Text that fits a single line for the age column (never cut). */
fun TextView.noWrap() { maxLines = 1; isSingleLine = true }

/** helper for FrameLayout children */
fun FrameLayout.addFill(v: View) = addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
