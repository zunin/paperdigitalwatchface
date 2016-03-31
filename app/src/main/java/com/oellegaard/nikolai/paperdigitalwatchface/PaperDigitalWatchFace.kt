/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oellegaard.nikolai.paperdigitalwatchface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.text.format.Time
import android.view.SurfaceHolder
import android.view.WindowInsets

import java.lang.ref.WeakReference
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
class PaperDigitalWatchFace : CanvasWatchFaceService() {

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: PaperDigitalWatchFace.Engine) : Handler() {
        private val mWeakReference: WeakReference<PaperDigitalWatchFace.Engine>

        init {
            mWeakReference = WeakReference(reference)
        }

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine() {
        internal val mUpdateTimeHandler: Handler = EngineHandler(this)
        internal var mRegisteredTimeZoneReceiver = false
        internal var mBackgroundPaint: Paint? = null
        internal var mTextPaint: Paint? = null
        internal var mAmbient: Boolean = false
        internal var mTime: Time? = null
        internal val mTimeZoneReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val time = mTime?.let { it } ?: return
                time.clear(intent.getStringExtra("time-zone"))
                time.setToNow()
            }
        }
        internal var mTapCount: Int = 0

        internal var mXOffset: Float = 0.toFloat()
        internal var mYOffset: Float = 0.toFloat()

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        internal var mLowBitAmbient: Boolean = false

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(WatchFaceStyle.Builder(this@PaperDigitalWatchFace).setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE).setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE).setShowSystemUiTime(false).setAcceptsTapEvents(true).build())
            val resources = this@PaperDigitalWatchFace.resources
            mYOffset = resources.getDimension(R.dimen.digital_y_offset)

            mBackgroundPaint = Paint()
            mBackgroundPaint?.color = resources.getColor(R.color.background)

            //mTextPaint = Paint()
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text))

            mTime = Time()
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        private fun createTextPaint(textColor: Int): Paint {
            val paint = Paint()
            paint.color = textColor
            paint.typeface = NORMAL_TYPEFACE
            paint.isAntiAlias = true
            return paint
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()

                // Update time zone in case it changed while we weren't visible.

                mTime?.clear(TimeZone.getDefault().id)
                mTime?.setToNow()
            } else {
                unregisterReceiver()
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer()
        }

        private fun registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@PaperDigitalWatchFace.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            this@PaperDigitalWatchFace.unregisterReceiver(mTimeZoneReceiver)
        }

        override fun onApplyWindowInsets(insets: WindowInsets) {
            super.onApplyWindowInsets(insets)

            // Load resources that have alternate values for round watches.
            val resources = this@PaperDigitalWatchFace.resources
            val isRound = insets.isRound
            mXOffset = resources.getDimension(if (isRound)
                R.dimen.digital_x_offset_round
            else
                R.dimen.digital_x_offset)
            val textSize = resources.getDimension(if (isRound)
                R.dimen.digital_text_size_round
            else
                R.dimen.digital_text_size)

            mTextPaint?.textSize = textSize
        }

        override fun onPropertiesChanged(properties: Bundle?) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties!!.getBoolean(WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false)
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode
                if (mLowBitAmbient) {
                    mTextPaint?.isAntiAlias = !inAmbientMode
                }
                invalidate()
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer()
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            val resources = this@PaperDigitalWatchFace.resources
            when (tapType) {
                WatchFaceService.TAP_TYPE_TOUCH -> {
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> {
                }
                WatchFaceService.TAP_TYPE_TAP -> {
                    // The user has completed the tap gesture.
                    mTapCount++
                    mBackgroundPaint?.color = resources.getColor(if (mTapCount % 2 == 0)
                        R.color.background
                    else
                        R.color.background2)
                }
            }// The user has started touching the screen.
            // The user has started a different gesture or otherwise cancelled the tap.
            invalidate()
        }

        override fun onDraw(canvas: Canvas?, bounds: Rect?) {
            // Draw the background.
            if (isInAmbientMode) {
                canvas!!.drawColor(Color.BLACK)
            } else {
                canvas!!.drawRect(0f, 0f, bounds!!.width().toFloat(), bounds.height().toFloat(), mBackgroundPaint)
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime?.setToNow()
            val text = if (mAmbient)
                String.format("%d:%02d", mTime?.hour, mTime?.minute)
            else
                String.format("%d:%02d:%02d", mTime?.hour, mTime?.minute, mTime?.second)
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint)
        }

        /**
         * Starts the [.mUpdateTimeHandler] timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        public fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        public fun shouldTimerBeRunning(): Boolean {
            return isVisible && !isInAmbientMode
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        public fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }
    }

    companion object {
        public val NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

        /**
         * Update rate in milliseconds for interactive mode. We update once a second since seconds are
         * displayed in interactive mode.
         */
        public val INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1)

        /**
         * Handler message id for updating the time periodically in interactive mode.
         */
        public val MSG_UPDATE_TIME = 0
    }
}
