package com.streamvault.domain.model

import com.streamvault.domain.repository.ChannelRepository

object VirtualCategoryIds {
    const val RECENT = -998L
    const val FAVORITES = -999L

    /**
     * Favorites, recents and custom groups all use negative ids, and so does "All channels".
     * Treating that one as virtual sent the player looking for favorites group 1,000,000:
     * an empty list, "Channels (0)", and up/down and CH+/- with nothing to step through.
     */
    fun isVirtual(categoryId: Long): Boolean =
        categoryId < 0L && categoryId != ChannelRepository.ALL_CHANNELS_ID
}
