package com.ahmadnayfeh.shasha.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeekAcceleratorTest {
    private val accelerator = SeekAccelerator()

    @Test
    fun singlePressIsTenSeconds() {
        assertThat(accelerator.step(eventTimeMs = 1_000, repeatCount = 0)).isEqualTo(10_000)
        assertThat(accelerator.step(eventTimeMs = 5_000, repeatCount = 0)).isEqualTo(10_000)
    }

    @Test
    fun repeatsAreThrottled() {
        accelerator.step(1_000, 0)
        assertThat(accelerator.step(1_050, 1)).isEqualTo(0)
        assertThat(accelerator.step(1_100, 2)).isEqualTo(0)
        assertThat(accelerator.step(1_160, 3)).isEqualTo(10_000)
    }

    @Test
    fun holdingAcceleratesAndIsCapped() {
        accelerator.step(0, 0)
        assertThat(accelerator.step(1_000, 20)).isEqualTo(15_000)
        assertThat(accelerator.step(2_000, 40)).isEqualTo(22_500)
        assertThat(accelerator.step(3_000, 60)).isEqualTo(33_750)
        assertThat(accelerator.step(20_000, 400)).isEqualTo(120_000)
    }

    @Test
    fun releaseStartsOver() {
        accelerator.step(0, 0)
        accelerator.step(3_000, 60)
        accelerator.reset()
        assertThat(accelerator.step(3_200, 1)).isEqualTo(10_000)
    }
}
