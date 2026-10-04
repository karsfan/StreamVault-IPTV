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
    fun `the provider group separates two feeds that share the exact same name`() {
        val italian = ChannelNormalizer.classify("VH1", 1L, groupTitle = "Musica").attributes.languageHint
        val english = ChannelNormalizer.classify("VH1", 1L, groupTitle = "24/7 English").attributes.languageHint

        assertThat(italian).isNull()
        assertThat(english).isEqualTo("EN")
        assertThat(ChannelNormalizer.sameLanguage(italian, english)).isTrue()
        assertThat(ChannelNormalizer.sameLanguage("IT", english)).isFalse()
    }

    @Test
    fun `a tag in the name wins over the group it was filed under`() {
        val hint = ChannelNormalizer.classify("IT| Sky Cinema Comedy", 1L, groupTitle = "English")
            .attributes.languageHint

        assertThat(hint).isEqualTo("IT")
    }

    @Test
    fun `an untagged name still backs a tagged one, so a reserve playlist keeps working`() {
        assertThat(ChannelNormalizer.sameLanguage("IT", null)).isTrue()
        assertThat(ChannelNormalizer.sameLanguage(null, null)).isTrue()
    }
}
