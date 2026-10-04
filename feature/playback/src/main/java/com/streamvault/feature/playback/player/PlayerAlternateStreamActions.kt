package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LiveChannelVariant
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import android.os.SystemClock
import com.streamvault.player.PlaybackState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the same channel from the other playlists attached as variants of the current live channel.
 *
 * Every emission is checked, not only a change of group: channel preparation loads the channel
 * again from the database and replaces the current value with a copy that has no cross-playlist
 * variants. Deduplicating by group swallowed that copy, so on some channels (Sky Uno) the
 * variants vanished right after being attached. The lookup still runs once per group.
 */
internal fun PlayerViewModel.observeEquivalentVariants() {
    viewModelScope.launch {
        var poolGroup: String? = null
        var pool: List<LiveChannelVariant> = emptyList()
        currentChannelFlow
            .filterNotNull()
            .collectLatest { channel ->
                if (currentContentType != ContentType.LIVE) return@collectLatest
                val group = channel.logicalGroupId.ifBlank { channel.id.toString() }
                if (group != poolGroup) {
                    pool = try {
                        playerChannelCoordinator.getEquivalentVariants(channel)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("PlayerVM", "equivalent-variants lookup failed", e)
                        return@collectLatest
                    }
                    poolGroup = group
                }
                val current = currentChannelFlow.value ?: return@collectLatest
                if (current.logicalGroupId != channel.logicalGroupId) return@collectLatest
                val enriched = current.withEquivalentVariants(pool)
                if (enriched !== current) currentChannelFlow.value = enriched.sanitizedForPlayer()
                startOnRememberedCrossPlaylistVariant(enriched)
            }
    }
}

/**
 * The playlist the channel belongs to. After a switch to a variant from another playlist the
 * channel carries that playlist's provider, so the remembered choice is keyed on the channel's own
 * variants, which always come first, instead.
 */
internal fun Channel.homeProviderId(): Long = variants.firstOrNull()?.providerId?.takeIf { it > 0L } ?: providerId

/**
 * The variant this channel is remembered on has just failed: forget it, so the next start goes
 * back to the automatic choice instead of opening on a broken source every time.
 */
private fun PlayerViewModel.forgetFailedPreferredVariant(channel: Channel) {
    if (channel.logicalGroupId.isBlank()) return
    val failedId = channel.selectedVariantId.takeIf { it > 0 } ?: channel.id
    viewModelScope.launch {
        val remembered = playerPreferencesCoordinator.preferredLiveVariant(channel.homeProviderId(), channel.logicalGroupId)
        if (remembered == failedId) {
            playerPreferencesCoordinator.clearPreferredLiveVariant(channel.homeProviderId(), channel.logicalGroupId)
        }
    }
}

/**
 * A remembered variant from the same playlist is already picked when the channel list is built.
 * One from another playlist only exists once the equivalents are attached, so it is applied here,
 * at most once per playback session and never on top of a switch already made.
 */
private suspend fun PlayerViewModel.startOnRememberedCrossPlaylistVariant(channel: Channel) {
    val session = prepareRequestVersion
    if (session == crossPlaylistVariantSession || isCatchUpPlayback()) return
    crossPlaylistVariantSession = session
    if (variantToRemember != null && variantToRemember?.first == session) return
    val home = channel.homeProviderId()
    val remembered = playerPreferencesCoordinator.preferredLiveVariant(home, channel.logicalGroupId) ?: return
    if (remembered == channel.selectedVariantId || session != prepareRequestVersion) return
    val target = channel.variants.firstOrNull { it.rawChannelId == remembered } ?: return
    if (target.providerId == home) return
    selectLiveVariant(remembered)
}

private const val STABLE_PLAYBACK_MS = 60_000L

/**
 * A variant reached by a switch becomes the channel's starting variant only after a minute of
 * playback without a rebuffer. Writing it at switch time remembered the last one tried even when
 * every variant failed.
 */
private fun PlayerViewModel.rememberVariantIfStillClean(session: Long) {
    val (switchSession, rawChannelId) = variantToRemember ?: return
    if (switchSession != session || session != prepareRequestVersion) return
    val channel = currentChannelFlow.value ?: return
    if (channel.logicalGroupId.isBlank() || channel.selectedVariantId != rawChannelId) return
    variantToRemember = null
    viewModelScope.launch {
        val home = channel.homeProviderId()
        if (playerPreferencesCoordinator.preferredLiveVariant(home, channel.logicalGroupId) == rawChannelId) return@launch
        playerPreferencesCoordinator.setPreferredLiveVariant(home, channel.logicalGroupId, rawChannelId)
    }
}

private const val REBUFFER_WINDOW_MS = 60_000L
private const val REBUFFERS_BEFORE_SWITCH = 3

/** Keeps the rebuffer times still inside the window, and says whether they are enough to move on. */
internal fun recordRebuffer(stalls: ArrayDeque<Long>, now: Long): Boolean {
    stalls.addLast(now)
    while (stalls.isNotEmpty() && now - stalls.first() > REBUFFER_WINDOW_MS) stalls.removeFirst()
    return stalls.size >= REBUFFERS_BEFORE_SWITCH
}

/**
 * A stream that keeps stuttering (three rebuffers within a minute) moves to the next variant, the
 * same way one that never starts does. The stall watchdog only covers the first seconds after a
 * zap; this covers the rest of the viewing. Each candidate is tried once per session, so a line
 * that is bad everywhere ends on the last variant instead of cycling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun PlayerViewModel.observeRepeatedRebuffering() {
    viewModelScope.launch {
        val stalls = ArrayDeque<Long>()
        var previous: PlaybackState? = null
        var readySession = -1L
        var cleanPlaybackTimer: Job? = null
        activePlayerEngine.flatMapLatest { it.playbackState }.collect { state ->
            // Every READY starts the clean-minute clock again, anything else stops it.
            cleanPlaybackTimer?.cancel()
            if (state == PlaybackState.READY && variantToRemember?.first == prepareRequestVersion) {
                val session = prepareRequestVersion
                cleanPlaybackTimer = viewModelScope.launch {
                    delay(STABLE_PLAYBACK_MS)
                    rememberVariantIfStillClean(session)
                }
            }
            // A zap or a variant switch also goes READY -> BUFFERING, but it starts a new playback
            // session. Only a stall inside the session that reached READY is a rebuffer; counting
            // zaps made three quick channel changes look like a stuttering stream.
            if (state == PlaybackState.READY && readySession != prepareRequestVersion) {
                stalls.clear()
                readySession = prepareRequestVersion
            }
            val rebuffered = previous == PlaybackState.READY &&
                state == PlaybackState.BUFFERING &&
                readySession == prepareRequestVersion
            previous = state
            if (!rebuffered || currentContentType != ContentType.LIVE || isCatchUpPlayback()) return@collect
            if (!recordRebuffer(stalls, SystemClock.elapsedRealtime())) return@collect
            stalls.clear()
            val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return@collect
            if (tryAlternateStreamInternal(channel)) {
                appendRecoveryAction("Repeated rebuffering, switched to an alternative source")
                showPlayerNotice(
                    message = alternateStreamNoticeText(channel),
                    recoveryType = PlayerRecoveryType.BUFFER_TIMEOUT,
                    isRetryNotice = true
                )
            }
        }
    }
}

fun PlayerViewModel.hasAlternateStream(): Boolean {
    if (isCatchUpPlayback()) {
        return nextCatchUpVariant() != null
    }
    if (currentContentType != ContentType.LIVE) return false
    val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return false
    return selectNextLiveRecoveryCandidate(
        channel = channel,
        currentVariantId = channel.selectedVariantId.takeIf { it > 0 } ?: channel.id,
        currentStreamUrl = currentStreamUrl,
        currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
        triedAlternativeStreams = playerRecoveryCoordinator.streamAttemptSnapshot(),
        failedStreamsThisSession = playerRecoveryCoordinator.failedStreamSnapshot(),
        preferXtreamTsFallback = false
    ) != null
}

fun PlayerViewModel.tryAlternateStream(): Boolean {
    if (isCatchUpPlayback()) {
        return tryNextCatchUpVariantInternal()
    }
    if (currentContentType != ContentType.LIVE) return false
    val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return false
    return tryAlternateStreamInternal(channel)
}

internal fun PlayerViewModel.tryAlternateStreamInternal(
    channel: Channel,
    preferXtreamTsFallback: Boolean = false,
    allowXtreamTsFallback: Boolean = true
): Boolean {
    // With no network every source fails in turn: keep this one and wait instead of burning
    // the whole variant list and the remembered variant with it.
    if (!hasNetworkConnection()) {
        appendRecoveryAction("No network: kept the current source")
        showPlayerNotice(
            message = appContext.getString(com.streamvault.feature.playback.R.string.player_error_network),
            recoveryType = PlayerRecoveryType.SOURCE,
            isRetryNotice = true
        )
        scheduleOfflineRetry()
        return false
    }
    forgetFailedPreferredVariant(channel)
    val candidate = selectNextLiveRecoveryCandidate(
        channel = channel,
        currentVariantId = channel.selectedVariantId.takeIf { it > 0 } ?: channel.id,
        currentStreamUrl = currentStreamUrl,
        currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
        triedAlternativeStreams = playerRecoveryCoordinator.streamAttemptSnapshot(),
        failedStreamsThisSession = playerRecoveryCoordinator.failedStreamSnapshot(),
        preferXtreamTsFallback = preferXtreamTsFallback,
        allowXtreamTsFallback = allowXtreamTsFallback
    ) ?: run {
        android.util.Log.w(
            "PlayerVM",
            "live-recovery no-candidate preferTsFallback=$preferXtreamTsFallback " +
                "allowTsFallback=$allowXtreamTsFallback " +
                "hasResolvedUrl=${currentResolvedPlaybackUrl.isNotBlank()} " +
                "alternates=${channel.alternativeStreams.size} variants=${channel.variants.size}"
        )
        return false
    }
    android.util.Log.i(
        "PlayerVM",
        "live-recovery selected=${candidate.kind} preferTsFallback=$preferXtreamTsFallback " +
            "allowTsFallback=$allowXtreamTsFallback"
    )

    if (candidate.kind == LiveRecoveryCandidateKind.VARIANT) {
        val nextVariant = candidate.variant ?: return false
        val updatedChannel = channel.withSelectedVariant(nextVariant.rawChannelId)?.sanitizedForPlayer()
            ?: return false
        val requestVersion = beginPlaybackSession()
        playerRecoveryCoordinator.markStreamAttempt(nextVariant.streamUrl)
        currentContentId = updatedChannel.id
        currentStreamUrl = updatedChannel.streamUrl
        currentTitle = nextVariant.originalName.ifBlank { updatedChannel.name }
        playbackTitleFlow.value = currentTitle
        currentChannelFlow.value = updatedChannel
        if (currentChannelIndex in channelList.indices) {
            channelList = channelList.mapIndexed { index, existing ->
                if (index == currentChannelIndex || existing.logicalGroupId == updatedChannel.logicalGroupId) {
                    updatedChannel
                } else {
                    existing
                }
            }
            currentChannelFlowList.value = channelList
        }
        if (currentChannelIndex >= 0) {
            displayChannelNumberFlow.value = resolveChannelNumber(updatedChannel, currentChannelIndex)
        }
        refreshCurrentChannelRecording()
        updateChannelDiagnostics(updatedChannel)
        updateStreamClass("Variant")
        // Same plate as a channel change, so a silent switch of source is never silent.
        showZapOverlayFlow.value = true
        hideZapOverlayAfterDelay()
        variantToRemember = requestVersion to nextVariant.rawChannelId
        playbackSessionScope(requestVersion)?.launch {
            val streamInfo = resolvePlaybackStreamInfo(
                logicalUrl = nextVariant.streamUrl,
                internalContentId = updatedChannel.id,
                providerId = updatedChannel.providerId,
                contentType = ContentType.LIVE
            ) ?: return@launch
            if (!isActivePlaybackSession(requestVersion, nextVariant.streamUrl)) return@launch
            requestEpg(
                providerId = updatedChannel.providerId,
                epgChannelId = updatedChannel.epgChannelId,
                streamId = updatedChannel.streamId,
                internalChannelId = updatedChannel.id,
                fallbackKeys = updatedChannel.guideFallbackKeys()
            )
            if (!preparePlayer(streamInfo.copy(title = streamInfo.title ?: currentTitle), requestVersion)) return@launch
            playerEngine.play()
        }
        return true
    }

    val requestVersion = beginPlaybackSession()
    val nextStream = candidate.url
    playerRecoveryCoordinator.markStreamAttempt(nextStream)
    currentStreamUrl = nextStream
    updateStreamClass(
        when (candidate.kind) {
            LiveRecoveryCandidateKind.XTREAM_TS_FALLBACK -> "MPEG-TS fallback"
            LiveRecoveryCandidateKind.ALTERNATE -> "Alternate"
            LiveRecoveryCandidateKind.VARIANT -> "Variant"
        }
    )
    playbackSessionScope(requestVersion)?.launch {
        val streamInfo = resolvePlaybackStreamInfo(nextStream, channel.id, channel.providerId, ContentType.LIVE)
            ?: return@launch
        if (!isActivePlaybackSession(requestVersion, nextStream)) return@launch
        if (!preparePlayer(streamInfo.copy(title = streamInfo.title ?: currentTitle), requestVersion)) return@launch
        playerEngine.play()
    }
    return true
}

internal fun PlayerViewModel.isCatchUpPlayback(): Boolean = isCatchUpPlayback.value

private fun PlayerViewModel.nextCatchUpVariant(): String? {
    return selectNextAlternateUrl(
        candidateUrls = pendingCatchUpUrls,
        currentStreamUrl = currentStreamUrl,
        triedAlternativeStreams = playerRecoveryCoordinator.streamAttemptSnapshot(),
        failedStreamsThisSession = playerRecoveryCoordinator.failedStreamSnapshot()
    )
}

internal fun PlayerViewModel.tryNextCatchUpVariantInternal(): Boolean {
    val nextStream = nextCatchUpVariant() ?: return false
    val requestVersion = beginPlaybackSession()
    playerRecoveryCoordinator.markStreamAttempt(nextStream)
    currentStreamUrl = nextStream
    updateStreamClass("Catch-up")
    playbackSessionScope(requestVersion)?.launch {
        val streamInfo = resolveCatchUpStreamInfo(
            candidateUrl = nextStream,
            title = currentTitle,
            currentContentId = currentContentId,
            currentProviderId = currentProviderId,
            resolveStreamInfo = ::resolvePlaybackStreamInfo
        )
            ?: return@launch
        if (!isActivePlaybackSession(requestVersion, nextStream)) return@launch
        if (!preparePlayer(streamInfo, requestVersion)) return@launch
        playerEngine.play()
    }
    return true
}
