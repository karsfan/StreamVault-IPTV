package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.repository.ChannelRepository
import org.junit.Test

class VirtualCategoryIdsTest {

    @Test
    fun `all channels is a real list, not a virtual one`() {
        assertThat(VirtualCategoryIds.isVirtual(ChannelRepository.ALL_CHANNELS_ID)).isFalse()
    }

    @Test
    fun `favorites, recents and custom groups are virtual`() {
        assertThat(VirtualCategoryIds.isVirtual(VirtualCategoryIds.FAVORITES)).isTrue()
        assertThat(VirtualCategoryIds.isVirtual(VirtualCategoryIds.RECENT)).isTrue()
        assertThat(VirtualCategoryIds.isVirtual(-3L)).isTrue()
    }

    @Test
    fun `provider categories are not virtual`() {
        assertThat(VirtualCategoryIds.isVirtual(42L)).isFalse()
    }
}
