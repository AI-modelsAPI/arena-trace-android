package com.ati.arena.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import com.ati.arena.R
import com.ati.arena.ui.HudFormat.QuotaLevel
import com.ati.arena.ui.HudFormat.Tone
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Flat floating capsule: a quota ring (with the % inside), a one-line label (current
 * model or task progress) and, optionally, a refresh button at the end:
 *
 *   ( ◔ 72  claude-opus-5 | ⟳ )
 *
 * With no label it collapses to the ring (plus the button). The refresh zone reacts
 * to a single tap (see [FloatingDragHelper.TouchZones]); anywhere else a tap opens the
 * panel. Static by design: it only redraws when its data changes. The one animation
 * (an arc orbiting the ring while a task is busy, the refresh icon spinning while the
 * page loads) runs only while the view is visible on screen.
 */
class StatusPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), FloatingDragHelper.TouchZones {

    private val pillHeight = dp(36f)
    private val ringSize = dp(26f)
    private val ringStroke = dp(2.5f)
    private val padStart = dp(5f)
    private val gap = dp(8f)
    private val padEnd = dp(14f)
    private val padEndBeforeButton = dp(8f)
    private val maxLabelWidth = dp(180f)
    private val buttonWidth = dp(36f)
    private val buttonIconSize = dp(18f)
    private val buttonPressRadius = dp(14f)
    private val dividerInset = dp(9f)
    private val buttonHitSlop = dp(4f) // a little extra room on the divider side

    private val colorBg = context.getColor(R.color.pill_bg)
    private val colorStroke = context.getColor(R.color.pill_stroke)
    private val colorTrack = context.getColor(R.color.surface_high)
    private val colorBrand = context.getColor(R.color.brand)
    private val colorText = context.getColor(R.color.on_surface)
    private val colorMuted = context.getColor(R.color.on_surface_muted)
    private val colorWarn = context.getColor(R.color.warn)
    private val colorDanger = context.getColor(R.color.danger)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = colorBg }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = colorStroke
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ringStroke; strokeCap = Paint.Cap.ROUND
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = colorTrack }
    private val percentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(9.5f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val refreshIcon: Drawable? = context.getDrawable(R.drawable.ic_reload)?.mutate()

    private val bounds = RectF()
    private val ring = RectF()
    private var spinAngle = 0f
    private var spinner: ValueAnimator? = null
    private var ellipsized: CharSequence = ""
    private var ellipsizedFor = -1f
    private var buttonPressed = false
    private val releasePress = Runnable {
        if (buttonPressed) {
            buttonPressed = false
            invalidate()
        }
    }

    /** Called when the refresh zone is tapped. */
    var onRefreshTap: (() -> Unit)? = null

    /** Remaining quota 0..100, or -1 when unknown. */
    var percent: Int = -1
        set(value) {
            val v = if (value in 0..100) value else -1
            if (field == v) return
            field = v
            updateDescription()
            invalidate()
        }

    var label: String = ""
        set(value) {
            if (field == value) return
            field = value
            ellipsizedFor = -1f
            updateDescription()
            requestLayout()
            invalidate()
        }

    var tone: Tone = Tone.MUTED
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var busy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncSpinner()
            invalidate()
        }

    /** Show the refresh button at the end of the capsule. */
    var showRefresh: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            buttonPressed = false
            ellipsizedFor = -1f
            syncSpinner()
            requestLayout()
            invalidate()
        }

    /** The page is loading: the refresh icon spins. */
    var refreshing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncSpinner()
            invalidate()
        }

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        updateDescription()
    }

    fun render(pill: HudFormat.Pill, quotaPercent: Int) {
        label = pill.label
        tone = pill.tone
        busy = pill.busy
        percent = quotaPercent
    }

    /** True when view-x [x] falls on the refresh button. */
    fun isRefreshHit(x: Float): Boolean = showRefresh && x >= width - buttonWidth - buttonHitSlop

    // ---------------------------------------------------------------- touch zones

    override fun onZoneDown(x: Float, y: Float) {
        removeCallbacks(releasePress)
        if (isRefreshHit(x) && !buttonPressed) {
            buttonPressed = true
            invalidate()
        }
    }

    override fun onZoneUp() {
        // Linger briefly so even a quick tap visibly "presses" the button.
        removeCallbacks(releasePress)
        if (buttonPressed) postDelayed(releasePress, PRESS_LINGER_MS)
    }

    override fun onZoneTap(x: Float, y: Float): Boolean {
        if (!isRefreshHit(x)) return false
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onRefreshTap?.invoke()
        return true
    }

    // ---------------------------------------------------------------- measure / draw

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelWidth = if (label.isEmpty()) 0f else min(labelPaint.measureText(label), maxLabelWidth)
        val content = when {
            labelWidth == 0f -> padStart * 2 + ringSize
            showRefresh -> padStart + ringSize + gap + labelWidth + padEndBeforeButton
            else -> padStart + ringSize + gap + labelWidth + padEnd
        }
        val w = content + if (showRefresh) buttonWidth else 0f
        setMeasuredDimension(
            resolveSize(ceil(w).toInt(), widthMeasureSpec),
            resolveSize(ceil(pillHeight).toInt(), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ellipsizedFor = -1f
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        val contentEnd = if (showRefresh) w - buttonWidth else w

        // Capsule: fill + hairline border (no blur, no glow).
        bounds.set(0f, 0f, w, h)
        canvas.drawRoundRect(bounds, radius, radius, bgPaint)
        val inset = strokePaint.strokeWidth / 2f
        bounds.inset(inset, inset)
        canvas.drawRoundRect(bounds, radius - inset, radius - inset, strokePaint)

        // Quota ring.
        val cx = padStart + ringSize / 2f
        val cy = h / 2f
        val r = ringSize / 2f - ringStroke / 2f
        ring.set(cx - r, cy - r, cx + r, cy + r)
        ringPaint.color = colorTrack
        canvas.drawOval(ring, ringPaint)
        val level = HudFormat.quotaLevel(percent)
        if (busy) {
            ringPaint.color = colorBrand
            canvas.drawArc(ring, spinAngle - 90f, 80f, false, ringPaint)
        } else if (percent > 0) {
            ringPaint.color = levelColor(level, text = false)
            canvas.drawArc(ring, -90f, 360f * percent / 100f, false, ringPaint)
        }
        percentPaint.color = levelColor(level, text = true)
        val number = if (percent >= 0) percent.toString() else "–"
        val pfm = percentPaint.fontMetrics
        canvas.drawText(number, cx, cy - (pfm.ascent + pfm.descent) / 2f, percentPaint)

        // Label.
        if (label.isNotEmpty()) {
            val x = padStart + ringSize + gap
            val available = contentEnd - x - if (showRefresh) padEndBeforeButton else padEnd
            if (available != ellipsizedFor) {
                ellipsized = TextUtils.ellipsize(label, labelPaint, maxOf(available, 0f), TextUtils.TruncateAt.END)
                ellipsizedFor = available
            }
            labelPaint.color = toneColor(tone)
            val lfm = labelPaint.fontMetrics
            canvas.drawText(ellipsized, 0, ellipsized.length, x, cy - (lfm.ascent + lfm.descent) / 2f, labelPaint)
        }

        if (showRefresh) drawRefreshButton(canvas, contentEnd, h)
    }

    private fun drawRefreshButton(canvas: Canvas, start: Float, h: Float) {
        canvas.drawLine(start, dividerInset, start, h - dividerInset, strokePaint)
        // Optically centred between the divider and the capsule's rounded end.
        val bx = start + buttonWidth / 2f - dp(1f)
        val by = h / 2f
        if (buttonPressed) canvas.drawCircle(bx, by, buttonPressRadius, pressPaint)
        val icon = refreshIcon ?: return
        val half = buttonIconSize / 2f
        icon.setBounds((bx - half).roundToInt(), (by - half).roundToInt(), (bx + half).roundToInt(), (by + half).roundToInt())
        icon.setTint(if (refreshing) colorBrand else colorMuted)
        val save = canvas.save()
        if (refreshing) canvas.rotate(spinAngle, bx, by)
        icon.draw(canvas)
        canvas.restoreToCount(save)
    }

    // ---------------------------------------------------------------- animation

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncSpinner()
    }

    override fun onDetachedFromWindow() {
        stopSpinner()
        removeCallbacks(releasePress)
        buttonPressed = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncSpinner()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncSpinner()
    }

    private fun syncSpinner() {
        val run = (busy || (refreshing && showRefresh)) && isAttachedToWindow && isShown
        if (run && spinner == null) {
            spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1100L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    spinAngle = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!run) {
            stopSpinner()
        }
    }

    private fun stopSpinner() {
        spinner?.cancel()
        spinner = null
    }

    // ---------------------------------------------------------------- helpers

    private fun levelColor(level: QuotaLevel, text: Boolean): Int = when (level) {
        QuotaLevel.OK -> if (text) colorText else colorBrand
        QuotaLevel.LOW -> colorWarn
        QuotaLevel.CRITICAL -> colorDanger
        QuotaLevel.UNKNOWN -> colorMuted
    }

    private fun toneColor(tone: Tone): Int = when (tone) {
        Tone.NORMAL -> colorText
        Tone.ROUTED -> colorWarn
        Tone.MUTED -> colorMuted
        Tone.ACTIVE -> colorBrand
    }

    private fun updateDescription() {
        contentDescription = buildString {
            append("Arena Trace")
            if (label.isNotEmpty()) append("，").append(label)
            if (percent >= 0) append("，剩余额度 ").append(percent).append('%')
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private companion object {
        const val PRESS_LINGER_MS = 90L
    }
}
