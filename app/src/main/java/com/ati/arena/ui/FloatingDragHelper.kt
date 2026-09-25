package com.ati.arena.ui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot

/**
 * Makes a floating view draggable inside its parent:
 *  - tap → [View.performClick], long-press → [View.performLongClick] (so the
 *    view's normal click listeners and accessibility actions keep working);
 *    a view implementing [TouchZones] can claim taps on part of itself first;
 *  - drag → follows the finger, then snaps to the nearest side edge;
 *  - the position is kept as (side, vertical fraction), so it survives rotation,
 *    the keyboard resizing the window, and the view changing width.
 *
 * Only x/y are animated here (with a dedicated animator), so fades that other code
 * runs on the view's alpha are never cancelled by a touch.
 */
class FloatingDragHelper(
    private val target: View,
    private val marginPx: Float,
    private val onMoved: (onRight: Boolean, yFraction: Float) -> Unit,
) {
    /** Optional button-like zones inside the dragged view (view coordinates). */
    interface TouchZones {
        /** Finger down at (x, y): show pressed feedback if it is on a zone. */
        fun onZoneDown(x: Float, y: Float) {}

        /** The touch ended, was cancelled or turned into a drag / long-press. */
        fun onZoneUp() {}

        /** A tap at (x, y); return true when a zone handled it (no performClick). */
        fun onZoneTap(x: Float, y: Float): Boolean = false
    }

    var onRight: Boolean = true
        private set
    var yFraction: Float = DEFAULT_Y
        private set

    private val zones: TouchZones? = target as? TouchZones
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var snap: Animator? = null
    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop

    private val gestures = GestureDetector(
        target.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!dragging && zones?.onZoneTap(e.x, e.y) != true) target.performClick()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (dragging) return
                zones?.onZoneUp()
                target.performLongClick()
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility") // taps are forwarded to performClick()
    fun attach(initialOnRight: Boolean, initialYFraction: Float) {
        onRight = initialOnRight
        yFraction = sanitize(initialYFraction)
        target.setOnTouchListener { v, e -> onTouch(v, e) }
        // Re-place when the parent resizes (rotation, IME) or the pill changes width.
        (target.parent as? View)?.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (!dragging && (r - l != or - ol || b - t != ob - ot)) place(animate = false)
        }
        target.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (!dragging && r - l != or - ol) place(animate = false)
        }
        target.post { place(animate = false) }
    }

    /** Put the view at its stored side / height, clamped inside the parent. */
    fun place(animate: Boolean) {
        val parent = target.parent as? View ?: return
        if (parent.width == 0 || target.width == 0) return
        val x = if (onRight) maxX(parent) else marginPx
        val y = marginPx + yFraction * (maxY(parent) - marginPx)
        snap?.cancel()
        snap = null
        if (animate) {
            snap = ObjectAnimator.ofPropertyValuesHolder(
                target,
                PropertyValuesHolder.ofFloat(View.X, x),
                PropertyValuesHolder.ofFloat(View.Y, y),
            ).apply {
                duration = SNAP_MS
                interpolator = DecelerateInterpolator()
                start()
            }
        } else {
            target.x = x
            target.y = y
        }
    }

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        val parent = v.parent as? View ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                downRawX = e.rawX
                downRawY = e.rawY
                snap?.cancel()
                snap = null
                startX = v.x
                startY = v.y
                zones?.onZoneDown(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    zones?.onZoneUp()
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    v.x = (startX + dx).coerceIn(marginPx, maxX(parent))
                    v.y = (startY + dy).coerceIn(marginPx, maxY(parent))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                zones?.onZoneUp()
                if (dragging) {
                    onRight = v.x + v.width / 2f > parent.width / 2f
                    val range = maxY(parent) - marginPx
                    yFraction = if (range > 0f) sanitize((v.y - marginPx) / range) else DEFAULT_Y
                    place(animate = true)
                    onMoved(onRight, yFraction)
                    // Let a pending tap/long-press see dragging=true, then reset.
                    v.post { dragging = false }
                }
            }
        }
        return true
    }

    private fun maxX(parent: View): Float = (parent.width - target.width - marginPx).coerceAtLeast(marginPx)

    private fun maxY(parent: View): Float = (parent.height - target.height - marginPx).coerceAtLeast(marginPx)

    private fun sanitize(f: Float): Float = if (f.isNaN()) DEFAULT_Y else f.coerceIn(0f, 1f)

    companion object {
        const val DEFAULT_Y = 0.18f
        private const val SNAP_MS = 180L
    }
}
