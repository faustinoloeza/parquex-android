package com.faustinoloeza.parquex.map

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Gestos de mapa sobre la vista de Filament:
 * - un dedo: paneo (con inercia al soltar);
 * - dos dedos: zoom (pellizco) + giro + paneo del centroide;
 * - dos dedos en vertical y paralelos: inclinación (se decide al inicio del gesto, como Google Maps);
 * - doble toque: acercar; toque simple: [onTap].
 *
 * Cada cambio llama a [requestRender]: el render es a demanda.
 */
class MapGestures(
    context: Context,
    private val camera: MapCamera,
    private val requestRender: () -> Unit,
    private val onTap: (x: Float, y: Float) -> Unit = { _, _ -> },
    private val onLongPress: (x: Float, y: Float) -> Unit = { _, _ -> },
    /** Al empezar cualquier gesto (p. ej. pausa el seguimiento de la navegación). */
    private val onGesture: () -> Unit = {},
) : View.OnTouchListener {

    private enum class Mode { NONE, PAN, TRANSFORM, TILT, UNDECIDED }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var mode = Mode.NONE
    private var velocity: VelocityTracker? = null

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    // Estado de dos dedos.
    private var prevCx = 0f
    private var prevCy = 0f
    private var prevSpan = 0f
    private var prevAngle = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var startSpan = 0f
    private var startAngle = 0f
    private var startY0 = 0f
    private var startY1 = 0f

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap(e.x, e.y); return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            camera.zoomInAt(e.x, e.y); requestRender(); return true
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress(e.x, e.y)
        }
    })

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        tapDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onGesture()
                camera.cancelMotion()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                mode = Mode.NONE
            }

            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) beginTwoFingers(e)

            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (e.pointerCount >= 2) moveTwoFingers(e) else moveOneFinger(e)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Al quedar un dedo, continúa el paneo desde el dedo que queda.
                val remaining = if (e.actionIndex == 0) 1 else 0
                if (e.pointerCount == 2) {
                    lastX = e.getX(remaining); lastY = e.getY(remaining)
                    mode = Mode.PAN
                    velocity?.clear()
                } else if (e.pointerCount > 2) {
                    beginTwoFingers(e, skip = e.actionIndex)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (mode == Mode.PAN) {
                    velocity?.let {
                        it.addMovement(e)
                        it.computeCurrentVelocity(1000)
                        camera.startFling(it.xVelocity, it.yVelocity, e.x, e.y)
                        requestRender()
                    }
                }
                velocity?.recycle(); velocity = null
                mode = Mode.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle(); velocity = null
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun moveOneFinger(e: MotionEvent) {
        if (mode == Mode.NONE) {
            if (hypot(e.x - downX, e.y - downY) < touchSlop) return
            mode = Mode.PAN
        }
        if (mode != Mode.PAN) return
        camera.pan(lastX, lastY, e.x, e.y)
        lastX = e.x; lastY = e.y
        requestRender()
    }

    private fun beginTwoFingers(e: MotionEvent, skip: Int = -1) {
        val (i0, i1) = firstTwo(e, skip)
        prevCx = (e.getX(i0) + e.getX(i1)) / 2f
        prevCy = (e.getY(i0) + e.getY(i1)) / 2f
        prevSpan = hypot(e.getX(i1) - e.getX(i0), e.getY(i1) - e.getY(i0)).coerceAtLeast(1f)
        prevAngle = MapCamera.angle(e.getX(i0), e.getY(i0), e.getX(i1), e.getY(i1))
        startCx = prevCx; startCy = prevCy; startSpan = prevSpan; startAngle = prevAngle
        startY0 = e.getY(i0); startY1 = e.getY(i1)
        mode = Mode.UNDECIDED
        camera.cancelMotion()
    }

    private fun moveTwoFingers(e: MotionEvent) {
        if (mode != Mode.UNDECIDED && mode != Mode.TRANSFORM && mode != Mode.TILT) {
            beginTwoFingers(e); return
        }
        val x0 = e.getX(0); val y0 = e.getY(0); val x1 = e.getX(1); val y1 = e.getY(1)
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val span = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
        val angle = MapCamera.angle(x0, y0, x1, y1)

        if (mode == Mode.UNDECIDED) {
            val dy0 = y0 - startY0; val dy1 = y1 - startY1
            val moved = hypot(cx - startCx, cy - startCy)
            val spanChange = abs(span - startSpan)
            val rot = abs(MapCamera.wrapAngle(angle - startAngle))
            when {
                // Ambos dedos se mueven en la misma dirección vertical, sin pellizco ni giro → inclinar.
                dy0 * dy1 > 0 && abs(dy0) > touchSlop && abs(dy1) > touchSlop &&
                    abs(cy - startCy) > 2f * abs(cx - startCx) && spanChange < touchSlop && rot < 0.12f ->
                    mode = Mode.TILT

                moved > touchSlop || spanChange > touchSlop || rot > 0.08f -> mode = Mode.TRANSFORM
                else -> return
            }
            // Arranca desde el estado actual para no dar un salto.
            prevCx = startCx; prevCy = startCy; prevSpan = startSpan; prevAngle = startAngle
            if (mode == Mode.TILT) prevCy = startCy
        }

        when (mode) {
            Mode.TILT -> camera.tilt(cy - prevCy)
            Mode.TRANSFORM -> camera.transform(
                prevCx, prevCy, cx, cy,
                scale = span / prevSpan,
                rotation = MapCamera.wrapAngle(angle - prevAngle),
            )
            else -> {}
        }
        prevCx = cx; prevCy = cy; prevSpan = span; prevAngle = angle
        requestRender()
    }

    private fun firstTwo(e: MotionEvent, skip: Int): Pair<Int, Int> {
        val idx = (0 until e.pointerCount).filter { it != skip }
        return idx[0] to idx[1]
    }
}
