package com.shilapi.xcertplay.media

/** Detection is 20Hz; this separate gate allows at most five lamp submissions per second. */
internal class AmbientMusicApplyCadence {
    private var lastSubmit: Long? = null
    fun trySubmit(nowMillis: Long): Boolean {
        if (lastSubmit?.let { nowMillis - it < 200 } == true) return false
        lastSubmit = nowMillis
        return true
    }
    fun reset() { lastSubmit = null }
}
