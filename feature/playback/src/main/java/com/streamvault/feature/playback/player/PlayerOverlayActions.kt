package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.guideLookupKey
import com.streamvault.domain.repository.ChannelRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DIAGNOSTICS_EXTRA_VISIBLE_MS = 10_000L
private const val CHANNEL_LIST_GUIDE_RECHECK_MS = 5 * 60_000L

fun PlayerViewModel.openChannelListOverlay() {
    clearNumericChannelInput()
    ensureChannelListLoaded()
    showChannelListOverlayFlow.value = true
    showCategoryListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showControlsFlow.value = false
    scheduleLiveOverlayAutoHide()
}

/**
 * A channel opened from the guide, from a recent row or from the home shelves carries no category,
 * so the player never loaded a playlist and the side list came up empty ("Channels (0)" with only
 * the recent ones). Fall back to the channel's own category, then to the whole provider.
 */
internal fun PlayerViewModel.ensureChannelListLoaded() {
    if (currentContentType != ContentType.LIVE || channelList.isNotEmpty()) return
    val channel = currentChannelFlow.value
    val knownCategoryId = currentCategoryId.takeIf { it != -1L }
    val categoryId = knownCategoryId ?: channel?.categoryId ?: ChannelRepository.ALL_CHANNELS_ID
    val providerId = channel?.providerId?.takeIf { it > 0L } ?: currentProviderId
    if (providerId <= 0L) return
    // "All channels" is read like a normal category; only recents and favourites are virtual.
    if (knownCategoryId == null) isVirtualCategory = false
    currentCategoryId = categoryId
    activeCategoryIdFlow.value = categoryId
    loadPlaylist(
        categoryId = categoryId,
        providerId = providerId,
        isVirtual = isVirtualCategory,
        initialChannelId = currentContentId
    )
}

/**
 * Loads what is on now for the side list rows the viewer can see. Only keys never checked,
 * checked more than five minutes ago, or whose programme has ended hit the database, so
 * scrolling back and forth or the overlay clock ticking costs nothing when all is fresh.
 */
fun PlayerViewModel.loadChannelListNowPlaying(channelIds: Collection<Long>) {
    val now = System.currentTimeMillis()
    val cached = channelListNowPlayingFlow.value
    val ids = channelIds.toSet()
    val stale = currentChannelFlowList.value.filter { channel ->
        if (channel.id !in ids) return@filter false
        val key = channel.guideLookupKey() ?: return@filter false
        val program = cached[key]
        if (program != null) program.endTime <= now
        else now - (channelListGuideCheckedAt[key] ?: 0L) > CHANNEL_LIST_GUIDE_RECHECK_MS
    }
    if (stale.isEmpty()) return
    channelListNowPlayingJob?.cancel()
    channelListNowPlayingJob = viewModelScope.launch {
        val fresh = epgCoordinator.nowPlaying(stale, now)
        stale.forEach { channel -> channel.guideLookupKey()?.let { channelListGuideCheckedAt[it] = now } }
        channelListNowPlayingFlow.update { it + fresh }
    }
}

fun PlayerViewModel.openCategoryListOverlay() {
    if (currentProviderId <= 0 || availableCategoriesFlow.value.isEmpty()) return
    showCategoryListOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.selectCategoryFromOverlay(category: Category) {
    showCategoryListOverlayFlow.value = false
    currentCategoryId = category.id
    activeCategoryIdFlow.value = category.id
    isVirtualCategory = category.isVirtual
    loadPlaylist(
        categoryId = category.id,
        providerId = currentProviderId,
        isVirtual = category.isVirtual,
        initialChannelId = currentContentId
    )
    openChannelListOverlay()
}

fun PlayerViewModel.openEpgOverlay() {
    clearNumericChannelInput()
    showEpgOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showControlsFlow.value = false
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.openFullGuideOverlay() {
    if (currentContentType != ContentType.LIVE) return
    clearNumericChannelInput()
    showFullGuideOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    showCategoryListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showDiagnosticsFlow.value = false
    showControlsFlow.value = false
    channelInfoHideJob?.cancel()
    clearLiveOverlayAutoHide()
    clearDiagnosticsAutoHide()
}

fun PlayerViewModel.closeFullGuideOverlay() {
    showFullGuideOverlayFlow.value = false
    playerEngine.setScrubbingMode(false)
}

fun PlayerViewModel.playChannelFromGuideOverlay(
    channel: Channel,
    selectedGuideCategoryId: Long,
    favoritesOnly: Boolean,
    combinedProfileId: Long?
) {
    if (currentContentType != ContentType.LIVE || channel.streamUrl.isBlank()) return
    val playbackCategoryId = when {
        favoritesOnly -> VirtualCategoryIds.FAVORITES
        selectedGuideCategoryId != ChannelRepository.ALL_CHANNELS_ID -> selectedGuideCategoryId
        else -> ChannelRepository.ALL_CHANNELS_ID
    }
    val categoryIsVirtual = playbackCategoryId == VirtualCategoryIds.FAVORITES || playbackCategoryId < 0L
    val currentListIndex = channelList.indexOfFirst { it.id == channel.id }

    clearNumericChannelInput()
    closeFullGuideOverlay()

    if (currentListIndex != -1) {
        changeChannel(currentListIndex)
        closeOverlays()
        playerEngine.setScrubbingMode(false)
        return
    }

    prepare(
        streamUrl = channel.streamUrl,
        epgChannelId = channel.epgChannelId,
        internalChannelId = channel.id,
        categoryId = playbackCategoryId,
        providerId = channel.providerId,
        isVirtual = categoryIsVirtual,
        combinedProfileId = combinedProfileId,
        contentType = ContentType.LIVE.name,
        title = channel.name,
        artworkUrl = channel.logoUrl,
        showResumePrompt = false
    )
    playerEngine.setScrubbingMode(false)
    closeOverlays()
}

fun PlayerViewModel.openChannelInfoOverlay() {
    clearNumericChannelInput()
    showChannelInfoOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    showControlsFlow.value = false
    channelInfoHideJob?.cancel()
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.closeChannelInfoOverlay() {
    channelInfoHideJob?.cancel()
    showChannelInfoOverlayFlow.value = false
    if (!hasVisibleTransientLiveOverlay()) clearLiveOverlayAutoHide()
}

fun PlayerViewModel.closeOverlays() {
    clearNumericChannelInput()
    showChannelListOverlayFlow.value = false
    showCategoryListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showDiagnosticsFlow.value = false
    channelInfoHideJob?.cancel()
    clearLiveOverlayAutoHide()
    clearDiagnosticsAutoHide()
}

fun PlayerViewModel.toggleDiagnostics() {
    showDiagnosticsFlow.value = !showDiagnosticsFlow.value
    if (showDiagnosticsFlow.value) {
        scheduleDiagnosticsAutoHide()
    } else {
        clearDiagnosticsAutoHide()
    }
}

fun PlayerViewModel.onLiveOverlayInteraction() {
    if (hasVisibleTransientLiveOverlay()) {
        scheduleLiveOverlayAutoHide()
    }
    if (showDiagnosticsFlow.value) {
        scheduleDiagnosticsAutoHide()
    }
}

fun PlayerViewModel.hideControlsAfterDelay() {
    controlsHideJob?.cancel()
    controlsHideJob = viewModelScope.launch {
        delay(playerControlsTimeoutMs)
        showControlsFlow.value = false
    }
}

fun PlayerViewModel.refreshControlsAutoHide() {
    if (showControlsFlow.value) {
        hideControlsAfterDelay()
    }
}

fun PlayerViewModel.cancelControlsAutoHide() {
    controlsHideJob?.cancel()
    controlsHideJob = null
}

internal fun PlayerViewModel.hideZapOverlayAfterDelay() {
    zapOverlayJob?.cancel()
    zapOverlayJob = viewModelScope.launch {
        delay(liveOverlayTimeoutMs)
        showZapOverlayFlow.value = false
    }
}

internal fun PlayerViewModel.hasVisibleTransientLiveOverlay(): Boolean =
    showChannelInfoOverlayFlow.value ||
        showChannelListOverlayFlow.value ||
        showEpgOverlayFlow.value

internal fun PlayerViewModel.clearLiveOverlayAutoHide() {
    liveOverlayHideJob?.cancel()
    liveOverlayHideJob = null
}

internal fun PlayerViewModel.clearDiagnosticsAutoHide() {
    diagnosticsHideJob?.cancel()
    diagnosticsHideJob = null
}

internal fun PlayerViewModel.scheduleLiveOverlayAutoHide() {
    if (currentContentType != ContentType.LIVE) {
        clearLiveOverlayAutoHide()
        return
    }
    liveOverlayHideJob?.cancel()
    liveOverlayHideJob = viewModelScope.launch {
        delay(liveOverlayTimeoutMs)
        showChannelInfoOverlayFlow.value = false
        showChannelListOverlayFlow.value = false
        showEpgOverlayFlow.value = false
    }
}

internal fun PlayerViewModel.scheduleDiagnosticsAutoHide() {
    if (currentContentType != ContentType.LIVE) {
        clearDiagnosticsAutoHide()
        return
    }
    diagnosticsHideJob?.cancel()
    diagnosticsHideJob = viewModelScope.launch {
        delay(diagnosticsTimeoutMs + DIAGNOSTICS_EXTRA_VISIBLE_MS)
        showDiagnosticsFlow.value = false
    }
}
