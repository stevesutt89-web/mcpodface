package com.steve.mcpodface.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.steve.mcpodface.core.Clock
import com.steve.mcpodface.core.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** What the sleep timer is doing. */
sealed interface SleepTimerState {
    data object Off : SleepTimerState
    /** Pauses at [endsAt] (epoch millis), fading out over the last [SleepTimer.FADE_MS]. */
    data class Running(val endsAt: Long) : SleepTimerState
    /** Pauses when the current episode finishes instead of moving to the next. */
    data object EndOfEpisode : SleepTimerState
}

/**
 * Sleep timer (PLAN.md §8.6): 5–60 minutes or end of episode. Shaking the phone while it runs
 * adds 5 minutes; the volume fades out over the final 10 seconds. Shared by the UI (via
 * [PlayerController]) and the [PlaybackEngine] (end-of-episode) — same process, one instance.
 */
@Singleton
class SleepTimer @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state

    /** Volume multiplier 0..1 for the fade-out; the engine applies it to the player. */
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume

    /** Called when time runs out; set by the engine to pause playback. */
    var onExpire: () -> Unit = {}

    private var ticker: Job? = null
    private val shake = ShakeDetector { extend(EXTEND_MS) }

    fun start(minutes: Int) {
        _state.value = SleepTimerState.Running(clock.now() + minutes * 60_000L)
        startTicker()
        shake.start(context)
    }

    fun stopAtEndOfEpisode() {
        cancelTicker()
        _state.value = SleepTimerState.EndOfEpisode
        _volume.value = 1f
        shake.stop()
    }

    fun cancel() {
        cancelTicker()
        _state.value = SleepTimerState.Off
        _volume.value = 1f
        shake.stop()
    }

    /** Adds time to a running timer (shake, or the "+5 min" button). */
    fun extend(ms: Long) {
        val s = _state.value as? SleepTimerState.Running ?: return
        _state.value = s.copy(endsAt = s.endsAt + ms)
        _volume.value = 1f
    }

    /** The engine calls this when an episode ends; true = pause here instead of continuing. */
    fun consumeEndOfEpisode(): Boolean {
        if (_state.value != SleepTimerState.EndOfEpisode) return false
        _state.value = SleepTimerState.Off
        return true
    }

    private fun startTicker() {
        cancelTicker()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val s = _state.value as? SleepTimerState.Running ?: break
                val left = s.endsAt - clock.now()
                _volume.value = fadeVolume(left)
                if (left <= 0) {
                    onExpire()
                    _state.value = SleepTimerState.Off
                    shake.stop()
                    delay(500) // let the pause land before restoring volume for next time
                    _volume.value = 1f
                    break
                }
                delay(if (left <= FADE_MS) 100 else 1_000)
            }
        }
    }

    private fun cancelTicker() {
        ticker?.cancel()
        ticker = null
    }

    companion object {
        const val FADE_MS = 10_000L
        const val EXTEND_MS = 5 * 60_000L

        /** Linear fade over the last [FADE_MS]. */
        fun fadeVolume(msLeft: Long): Float = when {
            msLeft >= FADE_MS -> 1f
            msLeft <= 0 -> 0f
            else -> msLeft.toFloat() / FADE_MS
        }
    }
}

/** Fires [onShake] on a firm shake (about 2.5 g), at most once every 1.5 s. */
private class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {
    private var manager: SensorManager? = null
    private var lastShake = 0L

    fun start(context: Context) {
        if (manager != null) return
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        manager = sm
    }

    fun stop() {
        manager?.unregisterListener(this)
        manager = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val now = System.nanoTime() / 1_000_000
        if (g > 2.5f && now - lastShake > 1_500) {
            lastShake = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
