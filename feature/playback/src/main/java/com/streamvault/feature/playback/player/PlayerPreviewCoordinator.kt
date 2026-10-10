package com.streamvault.feature.playback.player

import android.os.Build
import com.streamvault.feature.playback.preview.BorrowedMainEngine
import com.streamvault.feature.playback.preview.LivePreviewHandoffManager
import com.streamvault.feature.playback.preview.PreviewHandoffSource
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.PlayerEngine
import android.util.Log
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Owns preview/fullscreen handoff lifecycle for the player feature. */
class PlayerPreviewCoordinator @Inject constructor(
    private val handoffManager: LivePreviewHandoffManager,
    private val engineCoordinator: PlayerEngineCoordinator,
    private val preparationCoordinator: PlayerPreparationCoordinator,
    private val preferencesCoordinator: PlayerPreferencesCoordinator,
) {
    internal fun consumeFullscreenHandoff(
        channelId: Long,
        providerId: Long?
    ): LivePreviewHandoffManager.LivePreviewHandoffSession? =
        handoffManager.consumeFullscreenHandoff(channelId, providerId)

    internal fun clear(engine: PlayerEngine?) {
        handoffManager.clear(engine)
    }

    // The screen that handed its preview over, so the engine goes back there on exit. Returned
    // to HOME after coming from the Guide, the Home picked it up and kept it decoding off screen
    // until the process died, and every fullscreen channel after that stayed black.
    private var adoptedSource = PreviewHandoffSource.HOME

    /** The live list the player returns to; null when it returns somewhere without a preview pane. */
    internal var returnSource: PreviewHandoffSource? = null

    /**
     * Leaving a channel opened without a preview: lend the fullscreen engine to the list it
     * returns to, so the pane keeps showing the channel instead of "select a channel".
     */
    internal fun lendMainEngine(channel: Channel, streamInfo: StreamInfo, main: PlayerEngine): Boolean {
        val source = returnSource ?: return false
        handoffManager.beginReverseHandoff(channel, streamInfo, BorrowedMainEngine.lend(main), source)
        return true
    }

    internal fun beginReverseHandoff(
        channel: Channel,
        streamInfo: StreamInfo,
        engine: PlayerEngine
    ) {
        handoffManager.beginReverseHandoff(channel, streamInfo, engine, adoptedSource)
    }

    internal suspend fun tryAdoptFullscreenHandoff(
        channelId: Long,
        providerId: Long,
        contentType: ContentType,
        audioVideoOffsetMs: Int,
        isCurrent: () -> Boolean,
        onAdopted: (StreamInfo) -> Unit,
        onStarted: (StreamInfo) -> Unit,
    ): Boolean {
        if (contentType != ContentType.LIVE) return false

        val session = consumeFullscreenHandoff(
            channelId = channelId,
            providerId = providerId.takeIf { it > 0L }
        ) ?: return false
        adoptedSource = session.source

        if (shouldBypassForFireTvLiveHls(session.streamInfo)) {
            logInfo("Skipping preview handoff for Fire TV live HLS; fullscreen will prepare a fresh session.")
            clear(session.engine)
            session.engine.release()
            return false
        }

        // A pane holding the lent main engine gives it straight back: the player keeps one decoder.
        val adoptedEngine = (session.engine as? BorrowedMainEngine)?.main ?: session.engine
        return runCatching {
            adoptedEngine.clearRenderBinding()
            engineCoordinator.mainEngine.setMediaSessionEnabled(false)
            engineCoordinator.switchTo(adoptedEngine)
            adoptedEngine.setAudioFocusBypassed(false)
            adoptedEngine.setMediaSessionEnabled(preferencesCoordinator.playerMediaSessionEnabled.first())
            adoptedEngine.setResolutionConstrainedForMultiView(false)
            preparationCoordinator.applyPlaybackPreferences(
                engine = adoptedEngine,
                contentType = contentType,
                audioVideoOffsetMs = audioVideoOffsetMs
            )
            if (!isCurrent()) {
                engineCoordinator.switchTo(engineCoordinator.mainEngine)
                discard(adoptedEngine)
                false
            } else {
                onAdopted(session.streamInfo)
                adoptedEngine.resetLiveHandoffGrace()
                engineCoordinator.currentEngine.play()
                onStarted(session.streamInfo)
                true
            }
        }.getOrElse {
            clear(adoptedEngine)
            if (engineCoordinator.currentEngine === adoptedEngine) {
                engineCoordinator.switchTo(engineCoordinator.mainEngine)
            }
            discard(adoptedEngine)
            false
        }
    }

    // The main engine is reused by every player session: reset it, never release it.
    private fun discard(engine: PlayerEngine) {
        if (engine === engineCoordinator.mainEngine) engine.resetForReuse() else engine.release()
    }

    private fun shouldBypassForFireTvLiveHls(streamInfo: StreamInfo): Boolean {
        val isAmazonMediaTek = Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
            Build.HARDWARE.orEmpty().startsWith("mt", ignoreCase = true)
        val isHls = streamInfo.streamType == StreamType.HLS ||
            streamInfo.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        return isAmazonMediaTek && isHls
    }

    private fun logInfo(message: String) {
        runCatching { Log.i("PlayerVM", message) }
    }
}
