package com.example.weatherlwp.easter

class MultiTapDetector(
    private val tapThresholdMs: Long = 300,
    private val tapCount: Int = 5
) {
    interface Listener {
        fun onTriggered(taps: Int)
    }

    private var lastTap = 0L
    private var count = 0
    private var listener: Listener? = null

    fun setListener(l: Listener) {
        listener = l
    }

    fun onTap(nowMs: Long = System.currentTimeMillis()) {
        count = if (nowMs - lastTap > tapThresholdMs) 1 else (count + 1)
        lastTap = nowMs

        if (count >= tapCount) {
            listener?.onTriggered(count)
            count = 0
        }
    }
}
