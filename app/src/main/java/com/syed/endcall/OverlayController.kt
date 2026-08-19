package com.syed.endcall

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast

/**
 * Owns the floating window.
 *
 * The window only exists while a call is running. When no call is active there
 * is no view, no layout, no composition layer and no timer — the idle cost of
 * this feature is genuinely zero.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY, which an accessibility service may add
 * without the "draw over other apps" permission, and which floats above
 * full-screen apps and the keyguard.
 */
class OverlayController(private val service: AccessibilityService) : EndCallButtonView.Callbacks {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(service)
    private val handler = Handler(Looper.getMainLooper())

    private var view: EndCallButtonView? = null
    private var params: WindowManager.LayoutParams? = null
    private var currentCall: ActiveCall? = null

    private val collapseRunnable = Runnable { view?.setState(EndCallButtonView.State.COLLAPSED) }

    fun onCallChanged(call: ActiveCall?) {
        currentCall = call
        if (call != null) show() else hide()
    }

    /** Used by the volume-key shortcut, which bypasses the button entirely. */
    fun endCurrentCall() {
        val call = currentCall ?: return
        view?.setState(EndCallButtonView.State.BUSY)
        CallEnder.endAsync(service, call) { method -> onEndResult(method) }
    }

    private fun show() {
        if (view != null) return

        val v = EndCallButtonView(service).apply {
            callbacks = this@OverlayController
            setButtonDp(prefs.buttonDp)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Anchoring to the docked EDGE (not an absolute x) is what makes the
            // confirm pill open inward on both sides. Parked on the right the
            // window grows leftwards on its own; parked left, rightwards.
            gravity = Gravity.TOP or (if (prefs.dockedRight) Gravity.RIGHT else Gravity.LEFT)
            x = 0
            // Default: upper third. Deliberately clear of the bottom centre,
            // where the call app puts its own hang-up button, and clear of a
            // video call's face.
            val metrics = service.resources.displayMetrics
            y = if (prefs.posY >= 0) prefs.posY else (metrics.heightPixels * 0.32f).toInt()
        }

        v.mirrored = prefs.dockedRight

        runCatching { wm.addView(v, lp) }
            .onSuccess {
                view = v
                params = lp
            }
    }

    private fun hide() {
        handler.removeCallbacks(collapseRunnable)
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
        params = null
    }

    // ---- button callbacks -------------------------------------------------

    override fun onExpandRequested() {
        view?.setState(EndCallButtonView.State.EXPANDED)
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, prefs.confirmSeconds * 1000L)
    }

    override fun onConfirmed() {
        handler.removeCallbacks(collapseRunnable)
        endCurrentCall()
    }

    private fun onEndResult(method: String?) {
        prefs.lastMethod = method ?: "failed"

        // A test call has no real call behind it, so nothing will ever clear it
        // from the registry. Clear it here, exactly as a real hang-up would.
        currentCall?.let { if (it.isTest && method != null) CallRegistry.onCallEnded(it.key) }

        if (method != null) {
            view?.setState(EndCallButtonView.State.SUCCESS)
        } else {
            view?.setState(EndCallButtonView.State.FAILED)
            Toast.makeText(service, R.string.ended_fail, Toast.LENGTH_SHORT).show()
        }

        // Unconditional reset. Whatever happened, the button must never be left
        // stuck mid-state: either the call is gone and it disappears, or it
        // folds back to a circle ready to be tried again.
        handler.postDelayed({
            if (CallRegistry.active == null) hide()
            else view?.setState(EndCallButtonView.State.COLLAPSED)
        }, if (method != null) 900 else 1400)
    }

    override fun onDragStart() {
        handler.removeCallbacks(collapseRunnable)
        val lp = params ?: return
        val v = view ?: return
        val metrics = service.resources.displayMetrics

        // While dragging we work in absolute left-edge coordinates, so switch
        // off the edge anchoring for the duration and convert the position.
        if (lp.gravity and Gravity.RIGHT == Gravity.RIGHT) {
            lp.x = metrics.widthPixels - v.width - lp.x
        }
        lp.gravity = Gravity.TOP or Gravity.LEFT
        runCatching { wm.updateViewLayout(v, lp) }
    }

    override fun onDrag(dxRaw: Float, dyRaw: Float) {
        val lp = params ?: return
        val v = view ?: return
        val metrics = service.resources.displayMetrics
        lp.x = (lp.x + dxRaw.toInt()).coerceIn(0, (metrics.widthPixels - v.width).coerceAtLeast(0))
        lp.y = (lp.y + dyRaw.toInt()).coerceIn(0, (metrics.heightPixels - v.height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(v, lp) }
    }

    override fun onDragEnd() {
        val lp = params ?: return
        val v = view ?: return
        val metrics = service.resources.displayMetrics

        // Snap to whichever side edge is nearer and re-anchor to it, so the
        // pill will open inward from there.
        val centreX = lp.x + v.width / 2
        val right = centreX >= metrics.widthPixels / 2

        lp.gravity = Gravity.TOP or (if (right) Gravity.RIGHT else Gravity.LEFT)
        lp.x = 0
        lp.y = lp.y.coerceIn(0, metrics.heightPixels - v.height)

        v.mirrored = right
        runCatching { wm.updateViewLayout(v, lp) }

        prefs.dockedRight = right
        prefs.posY = lp.y
    }

    fun destroy() = hide()
}
