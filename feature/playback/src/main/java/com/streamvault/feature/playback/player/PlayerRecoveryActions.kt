package com.streamvault.feature.playback.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PROVIDER_AUTH_RETRY_GRACE_MS = 1_200L
private const val OFFLINE_RETRY_INTERVAL_MS = 3_000L
private const val OFFLINE_RETRY_ATTEMPTS = 40

/**
 * Every automatic recovery assumes the stream is at fault. When it is the wifi that dropped,
 * each source fails in turn, so the player walks the whole variant list, forgets the variant
 * that was working and marks the channel bad. Checking first keeps the stream and waits.
 */
internal fun PlayerViewModel.hasNetworkConnection(): Boolean {
    val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Waits for the network to come back and replays the same stream, up to two minutes. */
internal fun PlayerViewModel.scheduleOfflineRetry() {
    if (offlineRetryJob?.isActive == true) return
    offlineRetryJob = viewModelScope.launch {
        repeat(OFFLINE_RETRY_ATTEMPTS) {
            delay(OFFLINE_RETRY_INTERVAL_MS)
            if (hasNetworkConnection()) {
                appendRecoveryAction("Network is back, replaying the same stream")
                retryStream(currentStreamUrl, currentChannelFlow.value?.epgChannelId)
                return@launch
            }
        }
    }
}

internal fun PlayerViewModel.buildRecoveryActions(recoveryType: PlayerRecoveryType): List<PlayerNoticeAction> {
    return PlayerRecoveryPolicy.buildActions(
        hasAlternateStream = hasAlternateStream(),
        hasLastChannel = hasLastChannel(),
        shouldOfferGuide = recoveryType == PlayerRecoveryType.CATCH_UP && currentContentType == ContentType.LIVE
    )
}

internal fun shouldAttemptProviderAuthRetry(
    providerType: ProviderType,
    contentType: ContentType
): Boolean = PlayerRecoveryPolicy.shouldAttemptProviderAuthRetry(providerType, contentType)

internal fun shouldCooldownLivePreloadAfterError(message: String?): Boolean {
    return PlayerRecoveryPolicy.shouldCooldownLivePreloadAfterError(message)
}

internal fun PlayerViewModel.cooldownLivePreloadForCurrentProvider(reason: String) {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return
    if (playerRecoveryCoordinator.markLivePreloadCoolingDown(providerId)) {
        appendRecoveryAction("Disabled live preload for provider: $reason")
        clearPreloadWindow()
    }
}

internal suspend fun PlayerViewModel.tryRefreshXtreamPlaybackAfterAuthError(
    error: PlayerError,
    requestVersion: Long,
    playbackUrl: String
): Boolean {
    if (!playerRecoveryCoordinator.canRetryXtreamAuthRefresh()) return false
    if (error !is PlayerError.NetworkError) return false
    if (!isAuthExpiryPlaybackError(error.message)) return false
    if (!isXtreamPlaybackSession()) return false

    val refreshedStreamInfo = resolvePlaybackStreamInfo(
        logicalUrl = currentStreamUrl,
        internalContentId = currentContentId,
        providerId = currentProviderId,
        contentType = currentContentType
    ) ?: return false

    if (!isActivePlaybackSession(requestVersion, playbackUrl)) return false

    playerRecoveryCoordinator.markXtreamAuthRefreshRetried()
    probePassedPlaybackKeys.remove(
        resolvePlaybackProbeCacheKey(
            currentStreamUrl = currentStreamUrl,
            url = playbackUrl
        )
    )
    setLastFailureReason(error.message)
    cooldownLivePreloadForCurrentProvider("auth or provider-limit response")
    appendRecoveryAction("Retrying provider playback from a fresh live URL")
    delay(PROVIDER_AUTH_RETRY_GRACE_MS)
    if (!isActivePlaybackSession(requestVersion, playbackUrl)) return true
    clearResolvedStream()
    clearPreloadWindow()
    if (!preparePlayer(refreshedStreamInfo, requestVersion, probeBeforePlayback = false)) return true
    playerEngine.play()
    return true
}

internal suspend fun PlayerViewModel.isXtreamPlaybackSession(): Boolean {
    val providerId = currentProviderId.takeIf { it > 0L } ?: return false
    val provider = playerProviderCoordinator.getProvider(providerId) ?: return false
    return shouldAttemptProviderAuthRetry(provider.type, currentContentType)
}

internal fun PlayerViewModel.fallbackToPreviousChannel(reason: String): Boolean {
    // Opt-in (Settings > "Auto-Return on Bad Channel"): a stream that fails should say so and
    // stay put. Moving the viewer to another channel on its own hides the failure and loses
    // the channel they asked for.
    if (!zapAutoRevertEnabled) return false
    val fallbackIndex = previousChannelIndex
    if (fallbackIndex in channelList.indices && fallbackIndex != currentChannelIndex) {
        android.util.Log.w("PlayerVM", "Falling back to previous channel: $reason")
        val savedPrevious = previousChannelIndex
        changeChannel(fallbackIndex, isAutoFallback = true)
        previousChannelIndex = savedPrevious
        return true
    }
    return false
}

internal fun PlayerViewModel.scheduleZapBufferWatchdog(targetIndex: Int) {
    // Runs whatever the auto-return setting says: trying the same channel from another source
    // and reporting the stall are useful on their own, and only the channel hop is opt-in.
    zapBufferWatchdogJob?.cancel()
    val requestVersion = prepareRequestVersion
    zapBufferWatchdogJob = playbackSessionScope(requestVersion)?.launch {
        repeat(15) {
            delay(1000)
            if (!isActivePlaybackSession(requestVersion)) return@launch
            if (currentChannelIndex != targetIndex) return@launch
            val state = playerEngine.playbackState.value
            if (state == PlaybackState.READY || state == PlaybackState.ENDED) return@launch
        }
        if (!isActivePlaybackSession(requestVersion)) return@launch
        val stillOnTarget = currentChannelIndex == targetIndex
        val state = playerEngine.playbackState.value
        val stalled = state == PlaybackState.BUFFERING || state == PlaybackState.ERROR
        if (stillOnTarget && stalled) {
            markStreamFailure(currentStreamUrl)
            setLastFailureReason("Channel timed out in buffering state")
            appendRecoveryAction("Buffer watchdog triggered")
            // Same channel from another quality or playlist beats bouncing back to the last one.
            // Each candidate is tried once per session, so this ends in the fallback below.
            val stalledChannel = currentChannelFlow.value?.sanitizedForPlayer()
            if (stalledChannel != null && tryAlternateStreamInternal(stalledChannel)) {
                appendRecoveryAction("Buffer watchdog switched to an alternative source")
                showPlayerNotice(
                    message = alternateStreamNoticeText(stalledChannel),
                    recoveryType = PlayerRecoveryType.BUFFER_TIMEOUT,
                    isRetryNotice = true
                )
                scheduleZapBufferWatchdog(targetIndex)
                return@launch
            }
            val recovered = fallbackToPreviousChannel("Channel timed out in buffering state")
            showPlayerNotice(
                message = if (recovered) {
                    "That channel stalled too long. Returned to the last channel."
                } else {
                    "That channel stalled too long. Try another source or open the guide."
                },
                recoveryType = PlayerRecoveryType.BUFFER_TIMEOUT,
                actions = buildRecoveryActions(PlayerRecoveryType.BUFFER_TIMEOUT)
            )
        }
    }
}
