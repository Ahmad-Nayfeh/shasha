package com.ahmadnayfeh.shasha.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private class Sink : SleepSink {
        val volumes = mutableListOf<Float>()
        var stops = 0
        override fun setVolume(volume: Float) {
            volumes += volume
        }

        override fun stop() {
            stops++
        }
    }

    private fun TestScope.timer(sink: Sink) = SleepTimer(backgroundScope, { testScheduler.currentTime }).also { it.attach(sink) }

    @Test
    fun firesAtTheRightTime_afterFadingOut() = runTest {
        val sink = Sink()
        val timer = timer(sink)
        timer.start(15)
        runCurrent()
        assertThat(timer.state.value).isInstanceOf(SleepState.Counting::class.java)
        advanceTimeBy(15 * 60_000L - 11_000L)
        runCurrent()
        assertThat(sink.stops).isEqualTo(0)
        assertThat(sink.volumes).isEmpty() // full volume until the last 10 s
        advanceTimeBy(5_000L)
        runCurrent()
        assertThat(sink.volumes.last()).isLessThan(0.7f)
        assertThat(sink.stops).isEqualTo(0)
        advanceTimeBy(6_000L)
        runCurrent()
        assertThat(sink.stops).isEqualTo(1)
        assertThat(timer.state.value).isEqualTo(SleepState.Fired)
        assertThat(sink.volumes.last()).isEqualTo(1f) // restored for the next play
        timer.acknowledge()
        assertThat(timer.state.value).isEqualTo(SleepState.Off)
    }

    @Test
    fun reportsTheRemainingTime() = runTest {
        val timer = timer(Sink())
        timer.start(20)
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()
        val left = (timer.state.value as SleepState.Counting).remainingMs
        assertThat(left).isIn(com.google.common.collect.Range.closed(19 * 60_000L - 1_000L, 19 * 60_000L))
    }

    @Test
    fun cancel_neverFires_andRestoresTheVolume() = runTest {
        val sink = Sink()
        val timer = timer(sink)
        timer.start(15)
        advanceTimeBy(14 * 60_000L + 55_000L)
        runCurrent()
        timer.cancel()
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertThat(sink.stops).isEqualTo(0)
        assertThat(sink.volumes.last()).isEqualTo(1f)
        assertThat(timer.state.value).isEqualTo(SleepState.Off)
    }

    @Test
    fun startingAgain_replacesTheEarlierTimer() = runTest {
        val sink = Sink()
        val timer = timer(sink)
        timer.start(15)
        advanceTimeBy(60_000L)
        timer.start(60)
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertThat(sink.stops).isEqualTo(0)
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertThat(sink.stops).isEqualTo(1)
    }

    @Test
    fun endOfVideo_waitsForTheEnd_notForTime() = runTest {
        val sink = Sink()
        val timer = timer(sink)
        timer.choose(SleepChoice.EndOfVideo)
        advanceTimeBy(3 * 3_600_000L)
        runCurrent()
        assertThat(sink.stops).isEqualTo(0)
        assertThat(timer.state.value).isEqualTo(SleepState.EndOfVideo)
        timer.onVideoEnded()
        assertThat(timer.state.value).isEqualTo(SleepState.Fired)
        assertThat(sink.stops).isEqualTo(1)
    }

    @Test
    fun aVideoEndingWithTheTimerOff_doesNothing() = runTest {
        val sink = Sink()
        val timer = timer(sink)
        timer.onVideoEnded()
        timer.choose(SleepChoice.Minutes(20))
        timer.onVideoEnded() // a countdown is not an end-of-video timer
        assertThat(sink.stops).isEqualTo(0)
        timer.choose(SleepChoice.Off)
        assertThat(timer.state.value).isEqualTo(SleepState.Off)
    }
}
