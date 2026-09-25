package com.ati.arena.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import com.ati.arena.R
import com.ati.arena.ui.HudFormat.QuotaLevel
import com.ati.arena.ui.HudFormat.Tone
import kotlin.math.ceil
import kotlin.math.min

/**
 * Flat floating capsule: a quota ring (with the % inside) plus a one-line label
 * (current model or task progress). With no label it collapses to a 36dp circle.
 *
 * Static by design: it only redraws when its data changes. The one animation (a
 * short arc orbiting the ring) runs only while a task is busy AND the view is
 * visible on screen.
 */
class StatusPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val pillHeight = dp(36f)
    private val ringSize = dp(26f)
    private val ringStroke = dp(2.5f)
    private val padStart = dp(5f)
    private val gap = dp(8f)
    private val padEnd = dp(14f)
    private val maxLabelWidth = dp(180f)

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
    private val percentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(9.5f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val bounds = RectF()
    private val ring = RectF()
    private var spinAngle = 0f
    private var spinner: ValueAnimator? = null
    private var ellipsized: CharSequence = ""
    private var ellipsizedFor = -1f

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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelWidth = if (label.isEmpty()) 0f else min(labelPaint.measureText(label), maxLabelWidth)
        val w = if (labelWidth == 0f) padStart * 2 + ringSize else padStart + ringSize + gap + labelWidth + padEnd
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
            val available = w - x - padEnd
            if (available != ellipsizedFor) {
                ellipsized = TextUtils.ellipsize(label, labelPaint, available, TextUtils.TruncateAt.END)
                ellipsizedFor = available
            }
            labelPaint.color = toneColor(tone)
            val lfm = labelPaint.fontMetrics
            canvas.drawText(ellipsized, 0, ellipsized.length, x, cy - (lfm.ascent + lfm.descent) / 2f, labelPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncSpinner()
    }

    override fun onDetachedFromWindow() {
        stopSpinner()
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
        val run = busy && isAttachedToWindow && isShown
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
}
