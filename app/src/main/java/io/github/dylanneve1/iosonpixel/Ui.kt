package io.github.dylanneve1.iosonpixel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small, dependency-free styling kit for the code-built screens (no AndroidX,
 * no Material). Dark palette with the wallpaper's Material You accent.
 */
class Ui(private val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Number): Int = (v.toFloat() * density + 0.5f).toInt()

    /** Material You accent (wallpaper-derived); minSdk 31 guarantees the resource. */
    val accent: Int = ctx.getColor(android.R.color.system_accent1_200)
    val onAccent: Int = ctx.getColor(android.R.color.system_accent1_900)
    val tonal: Int = ctx.getColor(android.R.color.system_accent2_700)
    val onTonal: Int = ctx.getColor(android.R.color.system_accent2_50)

    fun stateColor(st: VmState.Status): Int = when (st) {
        VmState.Status.RUNNING -> GOOD
        VmState.Status.STARTING, VmState.Status.STOPPING -> WARN
        VmState.Status.STOPPED -> MUTED
    }

    fun roundRect(color: Int, radiusDp: Number, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    fun dot(color: Int, sizeDp: Int = 10): View = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun text(
        size: Float = 14f,
        color: Int = TEXT,
        bold: Boolean = false,
        mono: Boolean = false,
        selectable: Boolean = false,
    ) = TextView(ctx).apply {
        textSize = size
        setTextColor(color)
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        if (selectable) setTextIsSelectable(true)
        setLineSpacing(0f, 1.15f)
    }

    /**
     * A rounded surface with an eyebrow title; add content to the returned column.
     * [primary] gives it the accent outline the main flow uses.
     */
    fun card(parent: LinearLayout, title: String, subtitle: String? = null, primary: Boolean = false): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(SURFACE, 24, if (primary) accentOutline else null)
            setPadding(dp(S_CARD), dp(S_CARD), dp(S_CARD), dp(S_CARD + 2))
        }
        card.addView(eyebrow(title))
        if (subtitle != null) card.addView(text(13f, MUTED).apply { text = subtitle }, lp(top = 4))
        parent.addView(card, lp(top = 12))
        return card
    }

    private fun eyebrow(title: String) = text(12f, accent, bold = true).apply {
        text = title.uppercase()
        letterSpacing = 0.1f
    }

    /** A small muted heading between groups of cards. */
    fun groupLabel(parent: LinearLayout, label: String) {
        parent.addView(text(13f, MUTED, bold = true).apply {
            text = label
            letterSpacing = 0.02f
            setPadding(dp(4), 0, dp(4), 0)
        }, lp(top = 28))
    }

    /**
     * A secondary, collapsible card: a tappable header row (title, one-line [summary], chevron)
     * over a content column that starts hidden. Add content to [Section.body].
     */
    inner class Section(parent: LinearLayout, title: String, summaryText: String) {
        val summary: TextView = text(13f, MUTED).apply {
            text = summaryText
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val chevron = text(18f, MUTED).apply { text = "⌄" }
        private val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(SURFACE, 20)
        }
        val body: LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(S_CARD), 0, dp(S_CARD), dp(S_CARD + 2))
            visibility = View.GONE
        }
        val expanded get() = body.visibility == View.VISIBLE

        init {
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(S_CARD), dp(12), dp(S_CARD - 4), dp(12))
                background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, roundRect(Color.WHITE, 20))
                isClickable = true
                setOnClickListener { setExpanded(!expanded) }
            }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(16f, TEXT, bold = true).apply { text = title })
            texts.addView(summary, lp(top = 2))
            header.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            header.addView(chevron, lp(start = 8, width = WRAP_CONTENT))
            card.addView(header)
            card.addView(body)
            parent.addView(card, lp(top = 8))
        }

        fun setExpanded(shown: Boolean) {
            body.visibility = if (shown) View.VISIBLE else View.GONE
            chevron.rotation = if (shown) 180f else 0f
            summary.visibility = if (shown || summary.text.isEmpty()) View.GONE else View.VISIBLE
        }

        fun setSummary(s: CharSequence) {
            summary.text = s
            setExpanded(expanded)
        }
    }

    enum class Style { FILLED, TONAL, OUTLINED, DANGER, TEXT }

    /**
     * A pill button. Labels never wrap: give the button room (full width, an equal share of a
     * wide row, or a [flow] row that wraps whole buttons). [compact] is the smaller secondary size.
     */
    fun button(
        label: String,
        style: Style = Style.TONAL,
        compact: Boolean = false,
        onClick: (View) -> Unit,
    ): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = if (compact) 14f else 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        val h = dp(if (compact) 40 else 48)
        minHeight = h
        minimumHeight = h
        minWidth = 0
        minimumWidth = dp(if (compact) 48 else 64)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        val pad = dp(if (compact) 14 else 18)
        setPadding(pad, 0, pad, 0)
        val (bg, fg) = when (style) {
            Style.FILLED -> accent to onAccent
            Style.TONAL -> tonal to onTonal
            Style.DANGER -> DANGER_BG to DANGER_FG
            Style.OUTLINED, Style.TEXT -> Color.TRANSPARENT to accent
        }
        setTextColor(enabledList(fg, DISABLED_FG))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            color = enabledList(bg, if (bg == Color.TRANSPARENT) bg else DISABLED_BG)
            if (style == Style.OUTLINED) setStroke(dp(1), enabledList(OUTLINE, DISABLED_BG))
        }
        val mask = roundRect(Color.WHITE, 24)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, mask)
        setOnClickListener(onClick)
    }

    /** A wide button whose label may take two lines (at word breaks) rather than be cut. */
    fun wideButton(label: String, style: Style = Style.TONAL, onClick: (View) -> Unit): Button =
        button(label, style, onClick = onClick).apply {
            isSingleLine = false
            maxLines = 2
            ellipsize = null
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }

    /** A row that puts children side by side and wraps whole children onto the next line. */
    fun flow(gapDp: Int = 8): FlowRow = FlowRow(ctx, dp(gapDp))

    /** A labelled input. [suffix] renders a unit after the field ("GiB"). */
    fun field(
        parent: LinearLayout,
        label: String,
        value: String,
        help: String? = null,
        suffix: String? = null,
        numeric: Boolean = true,
    ): EditText {
        parent.addView(text(14f, TEXT, bold = true).apply { text = label }, lp(top = 16))
        if (help != null) parent.addView(text(12.5f, MUTED).apply { text = help }, lp(top = 2))
        val input = EditText(ctx).apply {
            setText(value)
            textSize = 16f
            setTextColor(enabledList(TEXT, DISABLED_FG))
            setHintTextColor(enabledList(MUTED, DISABLED_FG))
            isSingleLine = true
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_enabled), roundRect(FIELD, 12, accent))
                addState(intArrayOf(android.R.attr.state_enabled), roundRect(FIELD, 12, OUTLINE))
                addState(intArrayOf(), roundRect(BG, 12, DISABLED_BG))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (suffix != null) addView(text(14f, MUTED).apply { text = suffix }, lp(start = 12, width = WRAP_CONTENT))
        }
        parent.addView(row, lp(top = 8))
        return input
    }

    fun divider(parent: LinearLayout) {
        parent.addView(View(ctx).apply { setBackgroundColor(OUTLINE) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
            topMargin = dp(16)
        })
    }

    fun lp(top: Int = 0, start: Int = 0, width: Int = MATCH_PARENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, WRAP_CONTENT, weight).apply {
            topMargin = dp(top)
            marginStart = dp(start)
        }

    fun enabledList(enabled: Int, disabled: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(enabled, disabled),
    )

    fun pill(color: Int): Drawable = roundRect(color, 50)

    /** Accent at ~45% alpha: the primary card's outline. */
    private val accentOutline = (accent and 0x00FFFFFF) or 0x73000000

    /**
     * Lays children out left to right and moves a child that doesn't fit to the next line, so
     * buttons keep their natural width and their labels never break mid-word.
     */
    class FlowRow(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val childSpec = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
            var x = 0
            var y = 0
            var lineH = 0
            var widest = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                c.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + c.measuredWidth > maxW) {
                    x = 0
                    y += lineH + gap
                    lineH = 0
                }
                x += c.measuredWidth + gap
                widest = maxOf(widest, x - gap)
                lineH = maxOf(lineH, c.measuredHeight)
            }
            val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) widest else maxW
            setMeasuredDimension(w + paddingLeft + paddingRight, y + lineH + paddingTop + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l - paddingLeft - paddingRight
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            var x = 0
            var y = paddingTop
            var lineH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                if (x > 0 && x + c.measuredWidth > maxW) {
                    x = 0
                    y += lineH + gap
                    lineH = 0
                }
                val left = if (rtl) r - l - paddingRight - x - c.measuredWidth else paddingLeft + x
                c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth + gap
                lineH = maxOf(lineH, c.measuredHeight)
            }
        }
    }

    companion object {
        /** Spacing scale (dp): 4, 8, 12, 16, 24; cards pad by [S_CARD]. */
        const val S_CARD = 18

        val BG = Color.rgb(0x10, 0x11, 0x14)
        val SURFACE = Color.rgb(0x1C, 0x1D, 0x22)
        val FIELD = Color.rgb(0x14, 0x15, 0x19)
        val OUTLINE = Color.rgb(0x33, 0x35, 0x3C)
        val TEXT = Color.rgb(0xE8, 0xE9, 0xEE)
        val MUTED = Color.rgb(0x9A, 0x9D, 0xA8)
        val GOOD = Color.rgb(0x5E, 0xD3, 0x8A)
        val WARN = Color.rgb(0xF2, 0xB8, 0x4B)
        val BAD = Color.rgb(0xF2, 0x6D, 0x6D)
        val DANGER_BG = Color.rgb(0x4A, 0x1F, 0x22)
        val DANGER_FG = Color.rgb(0xFF, 0xB4, 0xAB)
        val DISABLED_BG = Color.rgb(0x2A, 0x2B, 0x30)
        val DISABLED_FG = Color.rgb(0x6A, 0x6C, 0x74)
    }
}
