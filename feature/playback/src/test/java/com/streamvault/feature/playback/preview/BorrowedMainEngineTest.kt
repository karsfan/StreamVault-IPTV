package com.streamvault.feature.playback.preview

import com.streamvault.player.PlayerEngine
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

class BorrowedMainEngineTest {
    @Test
    fun releasingTheCurrentLoanResetsTheMainEngineOnce() {
        val main = mock<PlayerEngine>()
        val loan = BorrowedMainEngine.lend(main)

        loan.release()
        loan.release()

        verify(main, times(1)).resetForReuse()
        verify(main, never()).release()
    }

    @Test
    fun aRevokedLoanNoLongerTouchesTheMainEngine() {
        val main = mock<PlayerEngine>()
        val loan = BorrowedMainEngine.lend(main)

        BorrowedMainEngine.revoke(main)
        loan.release()

        verify(main, never()).resetForReuse()
        verify(main, never()).release()
    }

    @Test
    fun anOlderLoanCannotResetTheEngineAfterANewOne() {
        val main = mock<PlayerEngine>()
        val old = BorrowedMainEngine.lend(main)
        BorrowedMainEngine.lend(main)

        old.release()

        verify(main, never()).resetForReuse()
    }

    @Test
    fun stoppingAnOldLoanLeavesTheNewOnePlaying() {
        val main = mock<PlayerEngine>()
        val old = BorrowedMainEngine.lend(main)
        val new = BorrowedMainEngine.lend(main)

        old.stop()
        verify(main, never()).stop()

        new.stop()
        verify(main, times(1)).stop()
    }
}
