package `is`.xyz.mpv

import android.graphics.PointF
import android.view.MotionEvent
import java.lang.Math

enum class PropertyChange {
    Init,
    Seek,
    SeekSub,
    Volume,
    Bright,
    Finalize
}

interface TouchGesturesObserver {
    fun onPropertyChange(p: PropertyChange, diff: Float);
}

class TouchGestures(val width: Float, val height: Float, val observer: TouchGesturesObserver) {

    enum class State {
        Up,
        Down,
        ControlSeek,
        ControlVolume,
        ControlBright,
    }

    private var state = State.Up
    // where user initially placed their finger (ACTION_DOWN)
    private var initialPos = PointF()
    // last non-throttled processed position
    private var lastPos = PointF()

    // minimum movement which triggers a Control state
    private var trigger: Float
    // ratio for trigger, 1/Xth of minimum dimension
    private val TRIGGER_RATE = 30

    // full sweep from left side to right side is 2:30
    private val CONTROL_SEEK_MAX = 150f
    // same as below, we rescale it inside MPVActivity
    private val CONTROL_VOLUME_MAX = 1.5f
    // brightness is scaled 0..1; max's not 1f so that user does not have to start from the bottom
    // if they want to go from none to full brightness
    private val CONTROL_BRIGHT_MAX = 1.5f

    // do not trigger on X% of screen top/bottom
    // this is so that user can open android status bar
    private val DEADZONE = 5

    init {
        trigger = Math.min(width, height) / TRIGGER_RATE
    }

    private fun processMovement(p: PointF): Boolean {
        // throttle events: only send updates when there's some movement compared to last update
        // 3 here is arbitrary
        if (PointF(lastPos.x - p.x, lastPos.y - p.y).length() < trigger / 3)
            return false
        lastPos.set(p)

        val dx = p.x - initialPos.x
        val dy = p.y - initialPos.y

        when (state) {
            State.Up -> {
            }
            State.Down -> {
                val inSubtitleArea = p.y > height * SUBTITLE_AREA_THRESHOLD

                // we might get into one of Control states if user moves enough
                if (Math.abs(dx) > trigger) {
                    // Horizontal gestures in the bottom of the screen should seek to the next/previous subtitle
                    if (inSubtitleArea) {
                        sendPropertyChange(PropertyChange.SeekSub, if (dx > 0) -1f else 1f)

                        state = State.Up
                    } else {
                        state = State.ControlSeek
                    }
                } else if (Math.abs(dy) > trigger) {
                    // depending on left/right side we might want volume or brightness control
                    if (initialPos.x > width / 2)
                        state = State.ControlVolume
                    else
                        state = State.ControlBright
                }

                // send Init so that it has a chance to cache values before we start modifying them
                if (state != State.Down) {
                    sendPropertyChange(PropertyChange.Init, 0f)
                }
            }
            State.ControlSeek ->
                sendPropertyChange(PropertyChange.Seek, CONTROL_SEEK_MAX * dx / width)
            State.ControlVolume ->
                sendPropertyChange(PropertyChange.Volume, -CONTROL_VOLUME_MAX * dy / height)
            State.ControlBright ->
                sendPropertyChange(PropertyChange.Bright, -CONTROL_BRIGHT_MAX * dy / height)
        }
        return state != State.Up && state != State.Down
    }

    private fun sendPropertyChange(p: PropertyChange, diff: Float) {
        observer.onPropertyChange(p, diff)
    }

    fun onTouchEvent(e: MotionEvent): Boolean {
        var gestureHandled = false
        when (e.action) {
            MotionEvent.ACTION_UP -> {
                gestureHandled = processMovement(PointF(e.x, e.y))
                sendPropertyChange(PropertyChange.Finalize, 0f)
                state = State.Up
                return gestureHandled
            }
            MotionEvent.ACTION_DOWN -> {
                // deadzone on top/bottom
                if (e.y < height * DEADZONE / 100 || e.y > height * (100 - DEADZONE) / 100)
                    return false
                initialPos = PointF(e.x, e.y)
                lastPos.set(initialPos)
                state = State.Down
                // always return true on ACTION_DOWN to continue receiving events
                gestureHandled = true
            }
            MotionEvent.ACTION_MOVE -> {
                gestureHandled = processMovement(PointF(e.x, e.y))
            }
        }
        return gestureHandled
    }

    companion object {
        /**
         * The percentage of the screen's height (counted from the top) that is not part of the subtitle area. Gestures
         * performed below this vertical threshold will be used for manipulation the subtitles.
         */
        private const val SUBTITLE_AREA_THRESHOLD = 0.7;
    }
}
