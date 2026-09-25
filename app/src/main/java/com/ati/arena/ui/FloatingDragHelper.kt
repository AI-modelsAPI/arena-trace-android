package com.ati.arena.ui

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
 *  - drag → follows the finger, then snaps to the nearest side edge;
 *  - the position is kept as (side, vertical fraction), so it survives rotation,
 *    the keyboard resizing the window, and the view changing width.
 */
class FloatingDragHelper(
    private val target: View,
    private val marginPx: Float,
    private val onMoved: (onRight: Boolean, yFraction: Float) -> Unit,
) {
    var onRight: Boolean = true
        private set
    var yFraction: Float = DEFAULT_Y
        private set

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop

    private val gestures = GestureDetector(
        target.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!dragging) target.performClick()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!dragging) target.performLongClick()
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
        if (animate) {
            target.animate().x(x).y(y).setDuration(SNAP_MS).setInterpolator(DecelerateInterpolator()).start()
        } else {
            target.animate().cancel()
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
                startX = v.x
                startY = v.y
                v.animate().cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    v.x = (startX + dx).coerceIn(marginPx, maxX(parent))
                    v.y = (startY + dy).coerceIn(marginPx, maxY(parent))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                onRight = v.x + v.width / 2f > parent.width / 2f
                val range = maxY(parent) - marginPx
                yFraction = if (range > 0f) sanitize((v.y - marginPx) / range) else DEFAULT_Y
                place(animate = true)
                onMoved(onRight, yFraction)
                // Let a pending tap/long-press see dragging=true, then reset.
                v.post { dragging = false }
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
