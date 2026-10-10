package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuideAnchorOnReturnTest {
    private val halfHour = 30 * 60 * 1000L
    private val now = 1_000 * halfHour

    @Test
    fun anchorLeftHoursAgoFollowsTheClock() {
        assertThat(guideAnchorOnReturn(now - 6 * halfHour, now, halfHour)).isEqualTo(now)
    }

    @Test
    fun recentAnchorIsKept() {
        assertThat(guideAnchorOnReturn(now - halfHour / 2, now, halfHour)).isEqualTo(now - halfHour / 2)
    }

    @Test
    fun futureAnchorIsKept() {
        assertThat(guideAnchorOnReturn(now + 8 * halfHour, now, halfHour)).isEqualTo(now + 8 * halfHour)
    }
}
