package io.github.dylanneve1.iosonpixel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
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

    /** A rounded surface with a title; add content to the returned column. */
    fun card(parent: LinearLayout, title: String, subtitle: String? = null): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(SURFACE, 24)
            setPadding(dp(20), dp(18), dp(20), dp(20))
        }
        card.addView(text(13f, accent, bold = true).apply {
            text = title.uppercase()
            letterSpacing = 0.08f
        })
        if (subtitle != null) card.addView(text(13f, MUTED).apply { text = subtitle }, lp(top = 4))
        parent.addView(card, lp(top = 12))
        return card
    }

    enum class Style { FILLED, TONAL, OUTLINED, DANGER, TEXT }

    fun button(label: String, style: Style = Style.TONAL, onClick: (View) -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(20), 0, dp(20), 0)
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
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            isSingleLine = true
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            background = roundRect(FIELD, 12, OUTLINE)
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

    private fun enabledList(enabled: Int, disabled: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(enabled, disabled),
    )

    fun pill(color: Int): Drawable = roundRect(color, 50)

    companion object {
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
