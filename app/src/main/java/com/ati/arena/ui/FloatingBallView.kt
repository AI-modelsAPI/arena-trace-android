package com.ati.arena.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Neon quota ring, inspired by the "blue neon circle on black" reference:
 * a dark obsidian core with a glowing ring whose arc length encodes remaining
 * quota, a bright highlight ("comet") that continuously sweeps around the ring,
 * and a soft glow that gently breathes. The ring color reflects quota health
 * (cyan/blue ≥20%, amber <20%, red <10%). The center shows the model name and
 * version on two lines, or the quota percent when no model is known yet.
 *
 * Pure Canvas/Paint — no image assets. Software layer is enabled so
 * BlurMaskFilter (the neon bloom) renders on all API levels.
 */
class FloatingBallView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 0..100; negative means "unknown" (ring drawn as a dim full circle). */
    var percent: Int = -1
        set(value) { field = value; invalidate() }

    /** Center line 1 (model name) and line 2 (version). Empty line 2 = single line. */
    var centerTop: String = "…"
        set(value) { field = value; invalidate() }
    var centerBottom: String = ""
        set(value) { field = value; invalidate() }

    private val density = context.resources.displayMetrics.density
    private val ringWidth = 4.5f * density

    // Rotating sweep highlight + breathing glow.
    private var sweepDeg = 0f
    private var glowPhase = 0f
    private val sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2600L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { sweepDeg = it.animatedValue as Float; invalidate() }
    }
    private val glowAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 2200L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { glowPhase = it.animatedValue as Float }
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#22000000")
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#22101826")
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        strokeCap = Paint.Cap.ROUND
    }
    private val topText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val bottomText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B7C4D6")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val arcRect = RectF()

    init {
        // BlurMaskFilter needs a software layer to render the neon bloom.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!sweepAnimator.isStarted) sweepAnimator.start()
        if (!glowAnimator.isStarted) glowAnimator.start()
    }

    override fun onDetachedFromWindow() {
        sweepAnimator.cancel()
        glowAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // Tight outer band: just enough to keep the ring's round cap + a thin
        // bloom inside the view. A large band made the blur spread into a faint
        // halo that looked like a background tint on light pages.
        val radius = min(w, h) / 2f - ringWidth * 1.4f

        // Obsidian core: near-black with a faint blue lift toward the center.
        corePaint.shader = RadialGradient(
            cx, cy - radius * 0.15f, radius,
            intArrayOf(Color.parseColor("#161B26"), Color.parseColor("#0A0C12")),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, corePaint)
        canvas.drawCircle(cx, cy, radius, rimPaint)

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        val p = percent
        val (base, bright) = neonPalette(p)
        val sweepFraction = if (p in 0..100) p / 100f else 1f
        val sweepLen = 360f * sweepFraction

        // A sweep gradient whose bright band rotates → the neon "comet".
        val sweep = SweepGradient(
            cx, cy,
            intArrayOf(base, base, bright, Color.WHITE, bright, base, base),
            floatArrayOf(0f, 0.30f, 0.44f, 0.5f, 0.56f, 0.70f, 1f)
        )
        sweep.setLocalMatrix(Matrix().apply { postRotate(sweepDeg, cx, cy) })

        // Breathing glow: a tight bloom hugging the ring. Kept small so it reads
        // as neon on the ring itself, not as a halo/background wash behind the ball.
        val breathe = 0.5f + 0.5f * kotlin.math.sin(glowPhase)      // 0..1
        val glowBlur = (ringWidth * (0.9f + breathe * 0.6f)).coerceAtLeast(1f)
        glowPaint.shader = sweep
        glowPaint.strokeWidth = ringWidth * 1.25f
        glowPaint.alpha = (90 + breathe * 70).toInt().coerceIn(0, 255)
        glowPaint.maskFilter = BlurMaskFilter(glowBlur, BlurMaskFilter.Blur.NORMAL)
        canvas.drawArc(arcRect, -90f, sweepLen, false, glowPaint)

        // Crisp ring on top of the bloom.
        ringPaint.shader = sweep
        ringPaint.alpha = 255
        canvas.drawArc(arcRect, -90f, sweepLen, false, ringPaint)

        drawCenter(canvas, cx, cy, radius)
    }

    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val top = centerTop.ifBlank { "…" }
        val bottom = centerBottom
        val maxW = radius * 1.5f

        fun fit(paint: Paint, text: String, baseSize: Float): Float {
            paint.textSize = baseSize
            val measured = paint.measureText(text)
            if (measured > maxW && measured > 0f) {
                paint.textSize = (baseSize * maxW / measured).coerceAtLeast(7f * density)
            }
            return paint.textSize
        }

        if (bottom.isBlank()) {
            fit(topText, top, radius * 0.52f)
            val fm = topText.fontMetrics
            canvas.drawText(top, cx, cy - (fm.ascent + fm.descent) / 2f, topText)
        } else {
            fit(topText, top, radius * 0.40f)
            fit(bottomText, bottom, radius * 0.32f)
            val gap = 2f * density
            val topH = topText.fontMetrics.let { it.descent - it.ascent }
            val botH = bottomText.fontMetrics.let { it.descent - it.ascent }
            val totalH = topH + gap + botH
            val startY = cy - totalH / 2f
            val topBaseline = startY - topText.fontMetrics.ascent
            canvas.drawText(top, cx, topBaseline, topText)
            val botBaseline = topBaseline + topText.fontMetrics.descent + gap - bottomText.fontMetrics.ascent
            canvas.drawText(bottom, cx, botBaseline, bottomText)
        }
    }

    /** (base, bright) neon colors by quota health. */
    private fun neonPalette(percent: Int): Pair<Int, Int> = when {
        percent in 0..9 -> Color.parseColor("#E11D2A") to Color.parseColor("#FF7A7A")   // 红
        percent in 10..19 -> Color.parseColor("#FF8A00") to Color.parseColor("#FFC85C") // 黄
        else -> Color.parseColor("#2563FF") to Color.parseColor("#4CE3FF")              // 青蓝
    }

    companion object {
        /** Solid quota color for the panel progress bar (matches ring health). */
        fun colorFor(percent: Int): Int = when {
            percent < 10 -> Color.parseColor("#E11D2A")
            percent < 20 -> Color.parseColor("#FF8A00")
            else -> Color.parseColor("#2FA7FF")
        }
    }
}
