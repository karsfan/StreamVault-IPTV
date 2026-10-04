package com.streamvault.domain.util

import com.streamvault.domain.model.LiveChannelVariantAttributes
import java.text.Normalizer
import java.util.Locale

data class ChannelClassification(
    val logicalGroupId: String,
    val canonicalName: String,
    val attributes: LiveChannelVariantAttributes
)

object ChannelNormalizer {
    private val bracketRegex = Regex("""\[(.*?)]|\((.*?)\)|\|(.*?)\|""")
    // "IT| RAI 1" is as common a country tag as "IT: RAI 1". Without the pipe the tag stayed in
    // the name, so the same channel taken from two playlists (one that keeps the provider's
    // naming, one renamed by hand) landed in two different logical groups and never showed up
    // as a variant of the other.
    private val leadingRegionRegex = Regex("""^\s*([a-z]{2,3})\s*[:\-|]\s*""", RegexOption.IGNORE_CASE)
    private val separatorRegex = Regex("""[\s_\-./]+""")
    private val collapseWhitespaceRegex = Regex("""\s+""")
    private val nonAlphaNumericRegex = Regex("""[^a-z0-9 ]""")
    private val frameRateRegex = Regex("""(?<!\d)(24|25|30|50|60)\s*fps(?!\d)""", RegexOption.IGNORE_CASE)
    private val heightRegex = Regex("""(?<!\d)(4320|2160|1440|1080|720|576|540|480|360|240)\s*p?(?!\d)""", RegexOption.IGNORE_CASE)
    private val plusRegex = Regex("""\+""")
    private val colonPipeRegex = Regex("""[:|]""")
    private val combiningMarksRegex = Regex("\\p{InCombiningDiacriticalMarks}+")

    // Classifying runs over every channel of a playlist each time the list is rebuilt, and Room
    // rebuilds it on any write to the channels table, playback error counters included. On a
    // 5,300 channel list that was the whole lag of All Channels on a slow TV, so a channel's
    // classification is computed once and reused. Entries are immutable, so sharing is safe.
    private const val CLASSIFY_CACHE_SIZE = 8_192
    private data class ClassifyKey(
        val name: String,
        val providerId: Long,
        val streamUrl: String,
        val groupTitle: String?
    )
    private val classifyCache = object : LinkedHashMap<ClassifyKey, ChannelClassification>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ClassifyKey, ChannelClassification>?): Boolean =
            size > CLASSIFY_CACHE_SIZE
    }

    private val resolutionTags = linkedMapOf(
        "8k" to 4320,
        "4320p" to 4320,
        "uhd" to 2160,
        "ultra hd" to 2160,
        "ultrahd" to 2160,
        "4k" to 2160,
        "2160p" to 2160,
        "2k" to 1440,
        "qhd" to 1440,
        "1440p" to 1440,
        "full hd" to 1080,
        "fullhd" to 1080,
        "fhd" to 1080,
        "1080p" to 1080,
        "1080i" to 1080,
        "hd" to 720,
        "720p" to 720,
        "576p" to 576,
        "540p" to 540,
        "hq" to 576,
        "sd" to 576
    )
    private val codecTags = linkedMapOf(
        "dolby vision" to "Dolby Vision",
        "dv" to "Dolby Vision",
        "hdr10" to "HDR10",
        "hdr" to "HDR",
        "hevc" to "HEVC",
        "h265" to "HEVC",
        "x265" to "HEVC",
        "av1" to "AV1",
        "h264" to "H.264",
        "x264" to "H.264"
    )
    private val transportTags = linkedMapOf(
        "mpeg ts" to "MPEG-TS",
        "mpeg-ts" to "MPEG-TS",
        "ts" to "MPEG-TS",
        "hls" to "HLS",
        "m3u8" to "HLS"
    )
    private val sourceHintTags = linkedMapOf(
        "backup" to "Backup",
        "alt" to "Alternate",
        "alternate" to "Alternate",
        "raw" to "Raw",
        "lite" to "Lite",
        "mobile" to "Mobile",
        "test" to "Test",
        "low" to "Low",
        "vip" to "VIP",
        "premium" to "Premium",
        "pro" to "Pro"
    )
    private val languageTags = linkedMapOf(
        "en" to "EN",
        "eng" to "EN",
        "english" to "EN",
        "fr" to "FR",
        "fre" to "FR",
        "french" to "FR",
        "de" to "DE",
        "ger" to "DE",
        "german" to "DE",
        "it" to "IT",
        "ita" to "IT",
        "italian" to "IT",
        "es" to "ES",
        "esp" to "ES",
        "spanish" to "ES",
        "pt" to "PT",
        "por" to "PT",
        "portuguese" to "PT",
        "ar" to "AR",
        "ara" to "AR",
        "arabic" to "AR"
    )

    private val removableCanonicalPhrases = (
        resolutionTags.keys +
            codecTags.keys +
            transportTags.keys +
            sourceHintTags.keys +
            listOf("fps")
        )
        .sortedByDescending { it.length }
        .map { phrase -> phrase to Regex("""(?<![a-z0-9])${Regex.escape(phrase)}(?![a-z0-9])""", RegexOption.IGNORE_CASE) }

    fun getLogicalGroupId(channelName: String, providerId: Long): String =
        classify(channelName, providerId).logicalGroupId

    /**
     * Returns true when the channel name is wrapped in hash markers,
     * e.g. "#### GENERAL HD/4K ####". These are category-header entries
     * from some providers (like Strong8k) that are not actual playable channels.
     */
    fun isHashWrappedHeader(channelName: String): Boolean {
        val trimmed = channelName.trim()
        return trimmed.startsWith("##") && trimmed.endsWith("##")
    }

    fun classify(
        channelName: String,
        providerId: Long,
        streamUrl: String = "",
        groupTitle: String? = null
    ): ChannelClassification {
        val key = ClassifyKey(channelName, providerId, streamUrl, groupTitle)
        synchronized(classifyCache) { classifyCache[key] }?.let { return it }
        val computed = classifyUncached(channelName, providerId, streamUrl, groupTitle)
        synchronized(classifyCache) { classifyCache[key] = computed }
        return computed
    }

    private fun classifyUncached(
        channelName: String,
        providerId: Long,
        streamUrl: String,
        groupTitle: String?
    ): ChannelClassification {
        val originalName = channelName.trim().ifBlank { "Channel" }
        val lowerName = originalName.lowercase(Locale.ROOT)
        val lowerUrl = streamUrl.lowercase(Locale.ROOT)

        val extractedTags = buildList {
            bracketRegex.findAll(originalName).forEach { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.let(::add)
            }
        }
        val regionHint = resolveRegionHint(originalName, extractedTags)
        val languageHint = resolveLanguageHint(lowerName, extractedTags)
            ?: resolveGroupLanguageHint(groupTitle)
        val declaredHeight = resolveDeclaredHeight(lowerName, lowerUrl)
        val frameRate = frameRateRegex.find(lowerName)?.groupValues?.get(1)?.toIntOrNull()
        val codecLabel = resolveCodecLabel(lowerName, lowerUrl)
        val transportLabel = resolveTransportLabel(lowerName, lowerUrl)
        val sourceHint = resolveSourceHint(lowerName)
        val rawTags = buildRawTags(
            declaredHeight = declaredHeight,
            frameRate = frameRate,
            codecLabel = codecLabel,
            transportLabel = transportLabel,
            sourceHint = sourceHint,
            regionHint = regionHint,
            languageHint = languageHint,
            lowerName = lowerName
        )

        val canonicalName = buildCanonicalName(originalName)
        val logicalKey = canonicalName
            .stripAccents()
            .lowercase(Locale.ROOT)
            .replace(nonAlphaNumericRegex, " ")
            .replace(collapseWhitespaceRegex, " ")
            .trim()
            .replace(" ", "")
            .ifEmpty {
                originalName.stripAccents()
                    .lowercase(Locale.ROOT)
                    .replace(nonAlphaNumericRegex, "")
                    .ifBlank { "channel${providerId}" }
            }

        return ChannelClassification(
            logicalGroupId = "${providerId}_$logicalKey",
            canonicalName = canonicalName,
            attributes = LiveChannelVariantAttributes(
                resolutionLabel = declaredHeight?.let(::heightToResolutionLabel),
                declaredHeight = declaredHeight,
                qualityTier = declaredHeight?.let(::heightToQualityTier) ?: 0,
                codecLabel = codecLabel,
                transportLabel = transportLabel,
                frameRate = frameRate,
                isHdr = lowerName.contains("hdr") || lowerName.contains("dolby vision") || containsStandalone(lowerName, "dv"),
                sourceHint = sourceHint,
                regionHint = regionHint,
                languageHint = languageHint,
                rawTags = rawTags
            )
        )
    }

    private fun buildCanonicalName(originalName: String): String {
        var cleaned = originalName
            .replace(bracketRegex, " ")
            .replace(leadingRegionRegex, " ")

        // A plain substring test first: most names hold none of these ~40 phrases, and skipping
        // the regex scan for them is most of the cost of the first pass over a large playlist.
        // Safe because the steps above only blank text out, so nothing absent here appears later.
        val lowerOriginal = originalName.lowercase(Locale.ROOT)
        removableCanonicalPhrases.forEach { (phrase, regex) ->
            if (lowerOriginal.contains(phrase)) cleaned = cleaned.replace(regex, " ")
        }
        cleaned = heightRegex.replace(cleaned, " ")
        cleaned = frameRateRegex.replace(cleaned, " ")

        cleaned = cleaned
            .replace(plusRegex, " + ")
            .replace(colonPipeRegex, " ")
            .replace(separatorRegex, " ")
            .replace(collapseWhitespaceRegex, " ")
            .trim()

        return cleaned.ifBlank { originalName.trim() }
    }

    private fun resolveDeclaredHeight(lowerName: String, lowerUrl: String): Int? {
        val directHeight = heightRegex.find(lowerName)?.groupValues?.get(1)?.toIntOrNull()
        if (directHeight != null) {
            return directHeight
        }
        resolutionTags.forEach { (tag, height) ->
            if (containsStandalone(lowerName, tag)) {
                return height
            }
        }
        return heightRegex.find(lowerUrl)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun resolveCodecLabel(lowerName: String, lowerUrl: String): String? =
        codecTags.entries.firstOrNull { (token, _) ->
            containsStandalone(lowerName, token) || containsStandalone(lowerUrl, token)
        }?.value

    private fun resolveTransportLabel(lowerName: String, lowerUrl: String): String? = when {
        lowerUrl.endsWith(".m3u8") || containsStandalone(lowerName, "m3u8") || containsStandalone(lowerName, "hls") -> "HLS"
        lowerUrl.endsWith(".ts") || containsStandalone(lowerName, "ts") || containsStandalone(lowerName, "mpeg ts") || containsStandalone(lowerName, "mpeg-ts") -> "MPEG-TS"
        else -> transportTags.entries.firstOrNull { (token, _) ->
            containsStandalone(lowerName, token) || containsStandalone(lowerUrl, token)
        }?.value
    }

    private fun resolveSourceHint(lowerName: String): String? =
        sourceHintTags.entries.firstOrNull { (token, _) -> containsStandalone(lowerName, token) }?.value

    private fun resolveRegionHint(originalName: String, extractedTags: List<String>): String? {
        val prefixMatch = leadingRegionRegex.find(originalName)
        val prefixCode = prefixMatch?.groupValues?.getOrNull(1)
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.length in 2..3 }
        if (prefixCode != null) {
            return prefixCode
        }
        return extractedTags.firstNotNullOfOrNull { tag ->
            val normalized = tag.trim().uppercase(Locale.ROOT)
            normalized.takeIf { it.length in 2..3 && it.all(Char::isLetter) }
        }
    }

    /**
     * Two feeds are the same channel only when nothing says they are in different languages.
     * An untagged name stays compatible with everything: most Italian lists tag nothing, and a
     * reserve playlist that writes "Rai 1" must still back "IT| Rai 1".
     */
    fun sameLanguage(first: String?, second: String?): Boolean =
        first == null || second == null || first.equals(second, ignoreCase = true)

    /**
     * The group a provider files a channel under is often the only thing that says which feed it
     * is: on one real playlist 4,954 of 5,304 entries sit in "English" / "24/7 English" while the
     * Italian ones are spread over Sky, Digitale Terrestre and Musica, and both carry the exact
     * same channel name ("VH1"). Only foreign languages are listed: the list's own language is
     * left untagged so untagged feeds stay compatible with each other.
     */
    private val groupLanguageWords = linkedMapOf(
        "english" to "EN",
        "inglese" to "EN",
        "spanish" to "ES",
        "spagna" to "ES",
        "espana" to "ES",
        "french" to "FR",
        "francia" to "FR",
        "francese" to "FR",
        "german" to "DE",
        "germania" to "DE",
        "deutsch" to "DE",
        "portuguese" to "PT",
        "portogallo" to "PT",
        "arabic" to "AR",
        "arabo" to "AR"
    )

    private fun resolveGroupLanguageHint(groupTitle: String?): String? {
        val lower = groupTitle?.stripAccents()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
            ?: return null
        return groupLanguageWords.entries
            .firstOrNull { (word, _) -> containsStandalone(lower, word) }
            ?.value
    }

    private val leadingLanguageTagRegex = Regex("""^\s*\|?\s*([a-z]{2,10})\s*[|:\-]\s*\S""")
    private val trailingLanguageTagRegex = Regex("""[|:]\s*([a-z]{2,10})\s*$""")

    /**
     * Only a real tag counts: bracketed, or fenced by | or : at either end of the name. Matching
     * any standalone word read a language out of "Leave It To Beaver" (IT), "Robert De Niro
     * Movies" (DE) and "EN Tout Cas" (EN): on one real 5,300 channel playlist 45 of the 419 hits
     * were titles, and those false tags would split a channel away from its own variants.
     */
    private fun resolveLanguageHint(lowerName: String, extractedTags: List<String>): String? {
        val extractedMatch = extractedTags.firstNotNullOfOrNull { tag ->
            languageTags[tag.trim().lowercase(Locale.ROOT)]
        }
        if (extractedMatch != null) {
            return extractedMatch
        }
        val fenced = leadingLanguageTagRegex.find(lowerName)?.groupValues?.getOrNull(1)
            ?: trailingLanguageTagRegex.find(lowerName)?.groupValues?.getOrNull(1)
        return fenced?.let { languageTags[it] }
    }

    private fun buildRawTags(
        declaredHeight: Int?,
        frameRate: Int?,
        codecLabel: String?,
        transportLabel: String?,
        sourceHint: String?,
        regionHint: String?,
        languageHint: String?,
        lowerName: String
    ): List<String> = buildList {
        declaredHeight?.let { add(heightToResolutionLabel(it)) }
        frameRate?.let { add("${it}fps") }
        codecLabel?.let { add(it) }
        transportLabel?.let { add(it) }
        sourceHint?.let { add(it) }
        regionHint?.let { add(it) }
        languageHint?.let { if (it != regionHint) add(it) }
        if (lowerName.contains("hdr")) add("HDR")
        if (lowerName.contains("dolby vision") || containsStandalone(lowerName, "dv")) {
            add("Dolby Vision")
        }
    }.distinct()

    /**
     * Same match as `(?<![a-z0-9])token(?![a-z0-9])`, without compiling a regex per call: this ran
     * about seventy times per channel. Every caller passes text already lowercased.
     */
    private fun containsStandalone(text: String, token: String): Boolean {
        if (token.isEmpty() || token.length > text.length) return false
        var start = text.indexOf(token)
        while (start >= 0) {
            val end = start + token.length
            val freeBefore = start == 0 || !text[start - 1].isAsciiWordChar()
            val freeAfter = end == text.length || !text[end].isAsciiWordChar()
            if (freeBefore && freeAfter) return true
            start = text.indexOf(token, start + 1)
        }
        return false
    }

    private fun Char.isAsciiWordChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun heightToResolutionLabel(height: Int): String = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height > 0 -> "${height}p"
        else -> "Unknown"
    }

    private fun heightToQualityTier(height: Int): Int = when {
        height >= 4320 -> 7
        height >= 2160 -> 6
        height >= 1440 -> 5
        height >= 1080 -> 4
        height >= 720 -> 3
        height >= 576 -> 2
        else -> 1
    }

    private fun String.stripAccents(): String =
        if (all { it.code < 128 }) this
        else Normalizer.normalize(this, Normalizer.Form.NFD).replace(combiningMarksRegex, "")
}
