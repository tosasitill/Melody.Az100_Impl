package com.tosasitill.az100

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The module draws its own UI in two places -- the LSPosed entry activity and the
 * AZ100 page inside Melody's detail activity -- and they have to look like one
 * product.  Everything visual lives here so a change lands in both.
 *
 * Built by hand rather than from a resource layout: the module's only
 * compile-time dependency is the Xposed API, so there is no appcompat or material
 * theme to inherit from, and pulling in a theme engine to draw four switches is
 * not worth the install size.  The pieces here are the few things the stock
 * widgets do badly at this size -- a card, a switch with a label on the left, a
 * meter -- and nothing more.
 *
 * Deliberately flat: no elevation, no shadows.  Cards are told apart by a
 * one-step color difference from the page background, which survives both themes
 * and cannot clip inside a [ScrollView] on a ROM that shadows badly.
 */
object Ui {

    /** Colors for one page, resolved once from the current night mode. */
    class Palette private constructor(night: Boolean) {
        val bg: Int
        val card: Int
        val fg: Int
        val fgSecondary: Int
        val fgTertiary: Int
        val accent: Int
        val accentSoft: Int
        val divider: Int
        val trackOff: Int
        val good: Int

        init {
            if (night) {
                bg = Color.rgb(14, 15, 18)
                card = Color.rgb(26, 28, 32)
                fg = Color.rgb(242, 243, 245)
                fgSecondary = Color.rgb(162, 167, 176)
                fgTertiary = Color.rgb(113, 118, 127)
                accent = Color.rgb(107, 155, 255)
                accentSoft = Color.rgb(29, 40, 56)
                divider = Color.rgb(38, 40, 45)
                trackOff = Color.rgb(58, 62, 69)
                good = Color.rgb(61, 214, 140)
            } else {
                bg = Color.rgb(244, 245, 247)
                card = Color.WHITE
                fg = Color.rgb(22, 24, 29)
                fgSecondary = Color.rgb(95, 100, 112)
                fgTertiary = Color.rgb(139, 144, 153)
                accent = Color.rgb(47, 111, 237)
                accentSoft = Color.rgb(232, 239, 254)
                divider = Color.rgb(236, 238, 242)
                trackOff = Color.rgb(211, 215, 222)
                good = Color.rgb(23, 166, 115)
            }
        }

        companion object {
            fun of(context: Context): Palette {
                val night = (context.resources.configuration.uiMode
                        and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                return Palette(night)
            }
        }
    }

    /** A scrollable page and the column to add content to. */
    class Page(val scroll: ScrollView, val column: LinearLayout)

    fun page(context: Context, palette: Palette): Page {
        val scroll = ScrollView(context)
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        scroll.setBackgroundColor(palette.bg)
        // targetSdk 35 puts the window edge-to-edge on Android 15+, so without
        // this the first line of the page sits behind the action bar.  Inset
        // rather than hard-coded padding: the bar's height is the ROM's business,
        // and Melody's container has already consumed these insets, so this is a
        // no-op on the detail page.
        scroll.fitsSystemWindows = true
        // Stop the first focusable child (a switch) from taking focus on open,
        // which scrolls the page past its own header.
        scroll.isFocusable = false
        scroll.isFocusableInTouchMode = false
        scroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(context, 18f), dp(context, 26f), dp(context, 18f), dp(context, 34f))
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return Page(scroll, column)
    }

    /* ---------------------------------------------------------------- text */

    /** The product name, once per page. */
    fun display(context: Context, palette: Palette, value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 26f
            setTextColor(palette.fg)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    fun body(context: Context, palette: Palette, value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 15f
            setTextColor(palette.fg)
        }

    /** Secondary line under a title.  Also used for the config path note. */
    fun caption(context: Context, palette: Palette, value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 13f
            setTextColor(palette.fgSecondary)
            setLineSpacing(dp(context, 3f).toFloat(), 1f)
        }

    /** Small all-caps group label above a card. */
    fun sectionLabel(context: Context, palette: Palette, value: String): TextView =
        TextView(context).apply {
            text = value.uppercase()
            textSize = 11f
            setTextColor(palette.fgTertiary)
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    /** A status chip.  Re-colorable through [setPill]. */
    fun pill(context: Context, palette: Palette, value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(context, 11f), dp(context, 5f), dp(context, 11f), dp(context, 5f))
            background = rounded(context, palette.accentSoft, 100f)
            setTextColor(palette.accent)
        }

    fun setPill(pill: TextView, palette: Palette, on: Boolean, value: String) {
        pill.text = value
        pill.setTextColor(if (on) palette.good else palette.fgSecondary)
        pill.background = rounded(
            pill.context,
            if (on) soft(palette.good) else palette.accentSoft,
            100f
        )
    }

    /* --------------------------------------------------------------- cards */

    fun card(context: Context, palette: Palette): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, palette.card, 18f)
            clipToPadding = false
        }

    /** One settings row: label and summary on the left, switch on the right. */
    fun row(
        context: Context,
        palette: Palette,
        label: String,
        summary: String?,
        toggle: Toggle
    ): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(context, 16f), dp(context, 14f), dp(context, 16f), dp(context, 14f))

        val texts = LinearLayout(context)
        texts.orientation = LinearLayout.VERTICAL

        texts.addView(TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(palette.fg)
        })

        if (!summary.isNullOrEmpty()) {
            texts.addView(caption(context, palette, summary).apply {
                setPadding(0, dp(context, 3f), 0, 0)
            })
        }

        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.addView(
            toggle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(context, 16f) }
        )

        // The whole row is a touch target, not just the switch.
        row.setOnClickListener { toggle.toggle() }
        row.isClickable = true
        return row
    }

    fun divider(context: Context, palette: Palette): View =
        View(context).apply {
            setBackgroundColor(palette.divider)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                max(1, dp(context, 1f))
            ).apply { leftMargin = dp(context, 16f) }
        }

    /** Body text inside a card, with the card's own horizontal padding. */
    fun paragraph(context: Context, palette: Palette, value: String, topPadding: Int): TextView =
        caption(context, palette, value).apply {
            setPadding(dp(context, 16f), dp(context, topPadding.toFloat()), dp(context, 16f), 0)
        }

    /** A filled accent button. */
    fun button(context: Context, palette: Palette, value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 15f
            setTextColor(palette.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(context, 18f), dp(context, 12f), dp(context, 18f), dp(context, 12f))
            background = rounded(context, palette.accentSoft, 14f)
            isClickable = true
            isFocusable = false
        }

    fun setButtonEnabled(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    /* -------------------------------------------------------------- switch */

    fun interface OnChange {
        fun onChanged(checked: Boolean)
    }

    /**
     * A switch with no text of its own.
     *
     * The stock `Switch` puts its label to the left of the track, which forces
     * the label and the track onto one baseline and leaves no room for a summary
     * line.  This draws only the track, so the row can lay out the text properly
     * and the track stays put no matter how long the label is.
     */
    class Toggle(context: Context, palette: Palette) : View(context) {

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val offColor = palette.trackOff
        private val onColor = palette.accent
        private val thumbColor = palette.card
        private val trackW = dp(context, 48f).toFloat()
        private val trackH = dp(context, 29f).toFloat()
        private val inset = dp(context, 3f).toFloat()

        private var progress = 0f
        private var checked = false
        private var animator: ValueAnimator? = null
        private var listener: OnChange? = null

        init {
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
        }

        fun setOnChange(listener: OnChange) {
            this.listener = listener
        }

        val isChecked: Boolean get() = checked

        /** Sets the state without notifying the listener. */
        fun setChecked(value: Boolean) = setChecked(value, false)

        private fun setChecked(value: Boolean, notify: Boolean) {
            if (checked == value && progress == (if (value) 1f else 0f)) return
            checked = value
            animator?.cancel()
            animator = ValueAnimator.ofFloat(progress, if (value) 1f else 0f).apply {
                duration = 160
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            if (notify) listener?.onChanged(value)
        }

        fun toggle() = setChecked(!checked, true)

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(
                resolveSize(trackW.toInt(), widthSpec),
                resolveSize(trackH.toInt(), heightSpec)
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h / 2f

            trackPaint.color = blend(offColor, onColor, progress)
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, trackPaint)

            val thumbRadius = radius - inset
            val center = radius + (w - radius * 2f) * progress
            thumbPaint.color = thumbColor
            canvas.drawCircle(center, h / 2f, thumbRadius, thumbPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                alpha = 0.7f
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                alpha = 1f
                true
            }

            MotionEvent.ACTION_UP -> {
                alpha = 1f
                if (isEnabled) toggle()
                true
            }

            else -> super.onTouchEvent(event)
        }
    }

    /* --------------------------------------------------------------- meter */

    /** A thin rounded progress bar, for battery levels. */
    class Meter(context: Context, palette: Palette) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackColor = palette.divider
        private val fillColor = palette.accent
        private val thickness = dp(context, 5f).toFloat()
        private var fraction = 0f

        fun setFraction(value: Float) {
            fraction = max(0f, min(1f, value))
            invalidate()
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(
                resolveSize(dp(context, 120f), widthSpec),
                resolveSize(thickness.toInt(), heightSpec)
            )
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val radius = h / 2f
            paint.color = trackColor
            canvas.drawRoundRect(0f, 0f, width.toFloat(), h, radius, radius, paint)
            if (fraction <= 0f) return
            paint.color = fillColor
            canvas.drawRoundRect(
                0f, 0f,
                max(h, width * fraction), h,
                radius, radius, paint
            )
        }
    }

    /* -------------------------------------------------------------- shared */

    fun rounded(context: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
        }

    /** A same-hue background for a foreground color, for status chips. */
    private fun soft(color: Int): Int =
        Color.argb(38, Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(from: Int, to: Int, t: Float): Int = Color.argb(
        (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).roundToInt(),
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt()
    )

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    /** The page shown when the config file cannot be opened at all. */
    fun errorScreen(
        context: Context,
        palette: Palette,
        title: String,
        detail: String,
        cause: Throwable
    ): ScrollView {
        val page = page(context, palette)
        page.column.addView(display(context, palette, title))
        page.column.addView(caption(context, palette, "$detail\n\n$cause").apply {
            setPadding(0, dp(context, 12f), 0, 0)
        })
        return page.scroll
    }
}
