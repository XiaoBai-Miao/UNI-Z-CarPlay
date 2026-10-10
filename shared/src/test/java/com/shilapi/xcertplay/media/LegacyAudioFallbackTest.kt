package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class LegacyAudioFallbackTest {
    @Test fun constructorRejectionStillCreatesUsageTrack() {
        val fallback = Any()
        val result = LegacyAudioFallback.build(
            createLegacy = { throw IllegalArgumentException("Unsupported stream") },
            isInitialized = { _: Any -> fail("No candidate should exist"); false },
            release = { fail("No candidate should exist") },
            createFallback = { fallback },
        )
        assertSame(fallback, result)
    }

    @Test fun uninitializedCandidateIsReleasedBeforeFallback() {
        val events = mutableListOf<String>()
        val result = LegacyAudioFallback.build(
            createLegacy = { "legacy" }, isInitialized = { false },
            release = { events += "release:$it" },
            createFallback = { events += "fallback"; "usage" },
        )
        assertEquals("usage", result)
        assertEquals(listOf("release:legacy", "fallback"), events)
    }

    @Test fun workingLegacyTrackDoesNotFallBackOrRelease() {
        assertEquals("legacy", LegacyAudioFallback.build(
            createLegacy = { "legacy" }, isInitialized = { true },
            release = { fail("Working track must remain open") },
            createFallback = { fail("No fallback needed"); "usage" },
        ))
    }

    /**
     * B561: legacy stream 只给**真的落在导航专声区**的流用。
     *
     * 上游原本认为 `default`/`alert`/`compatibility` 三条都在导航声区；
     * 修了"打字声走导航喇叭"之后，无导航时它们落到 SYSTEM（不吃 legacy），
     * 导航进行中的 `default` 才回到导航声区。这条守住新的分界。
     */
    @Test fun onlyGuidanceUsesLegacyStreams() {
        for (type in listOf("telephony", "speechRecognition", "media")) {
            assertFalse(type, AudioChannelMapper.usesNavigationStream(type, 100, false))
        }
        for (type in listOf("default", "alert", "compatibility")) {
            // 没有导航 → 提示音声道，不用 legacy stream。
            assertFalse("$type/noRoute", AudioChannelMapper.usesNavigationStream(type, 100, false))
            // 导航中只有 default 真的进导航专声区。
            val navigating = AudioChannelMapper.usesNavigationStream(
                type, 100, false,
                navigationVoiceScope = NavigationVoiceScope.GUIDANCE_ONLY,
                navigationActive = true,
            )
            assertEquals("navigating/$type", type == "default", navigating)
            assertFalse("$type/automotive", AudioChannelMapper.usesNavigationStream(type, 100, true))
        }
        assertFalse(AudioChannelMapper.usesNavigationStream("unknown", 102, false))
        assertTrue(
            AudioChannelMapper.usesNavigationStream(
                "unknown", 100, false,
                navigationVoiceScope = NavigationVoiceScope.GUIDANCE_ONLY,
                navigationActive = true,
            ),
        )
    }
}
