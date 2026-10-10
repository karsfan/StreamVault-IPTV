package com.streamvault.app.remote

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhoneRemoteKeyCodeTest {
    @Test
    fun namedButtonsMapToRemoteKeys() {
        assertThat(PhoneRemoteServer.keyCode("ok")).isEqualTo(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(PhoneRemoteServer.keyCode("chup")).isEqualTo(KeyEvent.KEYCODE_CHANNEL_UP)
        assertThat(PhoneRemoteServer.keyCode("red")).isEqualTo(KeyEvent.KEYCODE_PROG_RED)
    }

    @Test
    fun digitsMapToNumberKeys() {
        assertThat(PhoneRemoteServer.keyCode("0")).isEqualTo(KeyEvent.KEYCODE_0)
        assertThat(PhoneRemoteServer.keyCode("7")).isEqualTo(KeyEvent.KEYCODE_7)
    }

    @Test
    fun unknownNamesAreRejected() {
        assertThat(PhoneRemoteServer.keyCode("home")).isNull()
        assertThat(PhoneRemoteServer.keyCode("12")).isNull()
        assertThat(PhoneRemoteServer.keyCode(null)).isNull()
    }
}
