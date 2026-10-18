package com.steve.mcpodface.core

/** Wall-clock time in epoch millis. Inject this instead of calling System.currentTimeMillis() so tests can fake time. */
fun interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
