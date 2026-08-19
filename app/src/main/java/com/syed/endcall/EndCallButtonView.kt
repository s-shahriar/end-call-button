package com.syed.endcall

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat

/**
 * The floating hang-up button.
 *
 * Power notes, because this thing sits on screen for the whole call:
 *  - Nothing animates while it is idle. No pulse, no glow, no invalidate loop.
 *    Once drawn, it costs the same as a static image.
 *  - Animation runs only during the 200ms expand/collapse and while a finger
 *    is down.
 *  - The only timer that ever exists is the confirm countdown, and it is
 *    created when the pill opens and cancelled when it closes.
 */
class EndCallButtonView(context: Context) : View(context) {

    enum class State { COLLAPSED, EXPANDED, BUSY, SUCCESS, FAILED }

    interface Callbacks {
        /** First tap: open the confirm pill. */
        fun onExpandRequested()
        /** Second tap, on the pill: actually end the call. */
        fun onConfirmed()
        fun onDragStart()
        fun onDrag(dxRaw: Float, dyRaw: Float)
        fun onDragEnd()
    }

    var callbacks: Callbacks? = null

    var state: State = State.COLLAPSED
        private set

    /**
     * True when parked on the right edge: the icon moves to the right and the
     * label sits to its left, so the pill opens inward instead of running off
     * the screen.
     */
    var mirrored: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val shadowPad = dp(8f)
    private var collapsedSize = dp(64f)
    private val expandedWidth get() = dp(232f)

    /** 0 = circle, 1 = full confirm pill. */
    private var expand = 0f
    private var pressAmount = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val rect = RectF()

    private val icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_call_end)

    private val red = ContextCompat.getColor(context, R.color.hangup_red)
    private val redPressed = ContextCompat.getColor(context, R.color.hangup_red_pressed)
    private val grey = ContextCompat.getColor(context, R.color.hangup_disabled)
    private val green = ContextCompat.getColor(context, R.color.hangup_ok)

    private val label = context.getString(R.string.overlay_end_call)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val handler = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var movedBeyondSlop = false
    private var expandAnimator: ValueAnimator? = null
    private var pressAnimator: ValueAnimator? = null

    private val longPressRunnable = Runnable {
        if (!dragging && !movedBeyondSlop) {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            animatePress(0f)
            callbacks?.onDragStart()
        }
    }

    init {
        // setShadowLayer needs software rendering, and this view is tiny.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        bgPaint.setShadowLayer(dp(5f), 0f, dp(2f), 0x55000000)
        contentDescription = context.getString(R.string.overlay_cd)
        isHapticFeedbackEnabled = true
    }

    fun setButtonDp(size: Int) {
        collapsedSize = dp(size.toFloat())
        textPaint.textSize = collapsedSize * 0.28f
        requestLayout()
    }

    fun setState(newState: State) {
        if (state == newState) return
        state = newState
        when (newState) {
            State.COLLAPSED -> animateExpand(0f)
            State.EXPANDED -> animateExpand(1f)
            else -> invalidate()
        }
    }

    private fun animateExpand(target: Float) {
        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(expand, target).apply {
            duration = 200
            addUpdateListener {
                expand = it.animatedValue as Float
                requestLayout()
            }
            start()
        }
    }

    private fun animatePress(target: Float) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressAmount, target).apply {
            duration = 90
            addUpdateListener {
                pressAmount = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val contentW = collapsedSize + (expandedWidth - collapsedSize) * expand
        setMeasuredDimension(
            (contentW + shadowPad * 2).toInt(),
            (collapsedSize + shadowPad * 2).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        val h = collapsedSize
        val w = width - shadowPad * 2
        rect.set(shadowPad, shadowPad, shadowPad + w, shadowPad + h)

        val base = when (state) {
            State.BUSY -> grey
            State.SUCCESS -> green
            State.FAILED -> grey
            else -> red
        }
        bgPaint.color = if (pressAmount > 0f && base == red) blend(red, redPressed, pressAmount) else base

        val r = h / 2f
        canvas.drawRoundRect(rect, r, r, bgPaint)

        // The icon stays pinned to whichever end is docked, so it never jumps
        // as the pill opens — the text simply fades in beside it.
        val iconSize = (h * 0.42f).toInt()
        val cx = (if (mirrored) rect.right - r else rect.left + r).toInt()
        val cy = (shadowPad + h / 2f).toInt()
        icon?.setBounds(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2)
        icon?.alpha = 255
        icon?.draw(canvas)

        if (expand > 0.05f) {
            textPaint.alpha = (255 * ((expand - 0.05f) / 0.95f)).toInt().coerceIn(0, 255)
            val textCx = if (mirrored) {
                rect.left + (rect.right - h - rect.left) / 2f
            } else {
                (rect.left + h) + (rect.right - (rect.left + h)) / 2f
            }
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, textCx, baseline, textPaint)
        }
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) =
            (((a shr shift and 0xFF) * (1 - f)) + ((b shr shift and 0xFF) * f)).toInt()
        return Color.argb(255, ch(16), ch(8), ch(0))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                movedBeyondSlop = false
                if (state == State.COLLAPSED || state == State.EXPANDED) animatePress(1f)
                handler.postDelayed(longPressRunnable, longPressMs)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (dragging) {
                    callbacks?.onDrag(dx, dy)
                    downX = event.rawX
                    downY = event.rawY
                } else if (kotlin.math.hypot(dx, dy) > touchSlop) {
                    // A slide is not a tap. Cancel the long-press so a brush of
                    // the hand can neither move the button nor end the call.
                    movedBeyondSlop = true
                    handler.removeCallbacks(longPressRunnable)
                    animatePress(0f)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                animatePress(0f)
                if (dragging) {
                    dragging = false
                    callbacks?.onDragEnd()
                } else if (!movedBeyondSlop) {
                    when (state) {
                        State.COLLAPSED -> {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            callbacks?.onExpandRequested()
                        }
                        State.EXPANDED -> {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            callbacks?.onConfirmed()
                        }
                        else -> Unit
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                animatePress(0f)
                if (dragging) {
                    dragging = false
                    callbacks?.onDragEnd()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        expandAnimator?.cancel()
        pressAnimator?.cancel()
    }
}
