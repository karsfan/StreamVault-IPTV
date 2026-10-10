package com.streamvault.feature.playback.preview

import com.streamvault.player.PlayerEngine

/**
 * The fullscreen engine lent to a live list's preview pane, so leaving the player keeps the
 * channel on screen instead of an empty pane.
 *
 * The main engine is a singleton the next player reuses, so the borrower must never tear it
 * down once the player has taken it back: release() only resets it while this loan is still
 * the current one, and the player revokes every loan as soon as it starts.
 */
class BorrowedMainEngine private constructor(
    val main: PlayerEngine
) : PlayerEngine by main {

    override fun release() {
        if (returnLoan(this)) main.resetForReuse()
    }

    override fun resetForReuse() = release()

    // A pane swapping one loan for the next stops the old one first; that must not stop the
    // engine the new loan is still playing.
    override fun stop() {
        if (isCurrent(this)) main.stop()
    }

    companion object {
        @Volatile
        private var current: BorrowedMainEngine? = null

        @Synchronized
        fun lend(main: PlayerEngine): BorrowedMainEngine =
            BorrowedMainEngine(main).also { current = it }

        /** The player is starting: any preview still holding the engine stops owning it. */
        @Synchronized
        fun revoke(main: PlayerEngine) {
            if (current?.main === main) {
                current = null
                // The preview muted focus handling; the player needs it back.
                main.setAudioFocusBypassed(false)
            }
        }

        private fun isCurrent(loan: BorrowedMainEngine): Boolean = current === loan

        @Synchronized
        private fun returnLoan(loan: BorrowedMainEngine): Boolean {
            if (current !== loan) return false
            current = null
            return true
        }
    }
}
