package com.streamvault.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelNormalizerLanguageTest {

    @Test
    fun `an english feed is not a variant of the italian one`() {
        val italian = ChannelNormalizer.classify("Eurosport 1", 1L).attributes.languageHint
        val english = ChannelNormalizer.classify("EN| Eurosport 1", 1L).attributes.languageHint

        assertThat(ChannelNormalizer.sameLanguage(italian, english)).isTrue()
        assertThat(ChannelNormalizer.sameLanguage("IT", english)).isFalse()
    }

    @Test
    fun `an untagged name still backs a tagged one, so a reserve playlist keeps working`() {
        assertThat(ChannelNormalizer.sameLanguage("IT", null)).isTrue()
        assertThat(ChannelNormalizer.sameLanguage(null, null)).isTrue()
    }
}
