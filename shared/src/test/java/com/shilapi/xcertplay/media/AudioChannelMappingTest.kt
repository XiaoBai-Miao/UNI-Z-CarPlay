package com.shilapi.xcertplay.media

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChannelMappingTest {
    @Test
    fun mobileCompatibleMappingMatchesTheOriginalRouting() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "media",
            payloadType = 100,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
    }

    /**
     * B561: 关掉「系统提示音走全车」后必须**逐字回到** DiPlay 0.2.14 的映射。
     *
     * 这是那个开关存在的意义 —— 实车上做 A/B 对照，
     * 确认"从导航喇叭出来的咔哒声"到底由哪条流带来。
     *
     * ★ automotive 模式下 `compatibility` 原本就并进 **MEDIA**（不是 NAVIGATION），
     *   这里跟着原样断言 —— 把它"顺手改成"导航就成了第二处行为变更。
     */
    @Test
    fun disablingSystemAudioRoutingRestoresTheOriginalNavigationMapping() {
        listOf("default", "alert").forEach { audioType ->
            listOf(AudioChannelMappingMode.MOBILE_COMPATIBLE, AudioChannelMappingMode.AUTOMOTIVE_BUS)
                .forEach { mode ->
                    assertEquals(
                        "$mode/$audioType",
                        AudioChannelSelection(
                            AudioChannel.NAVIGATION, AudioContentType.SPEECH,
                            AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
                        ),
                        AudioChannelMapper.map(
                            audioType, 100, mode,
                            navigationVoiceScope = NavigationVoiceScope.ALWAYS,
                            navigationActive = false,
                            systemAudioRouting = false,
                        ),
                    )
                }
        }
        // 兼容流两条模式各不相同（mobile 走导航专声区、automotive 走媒体总线），
        // 断言时分开写，别合并成一张列表。
        assertEquals(
            AudioChannelSelection(
                AudioChannel.NAVIGATION, AudioContentType.SPEECH,
                AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
            ),
            AudioChannelMapper.map(
                "compatibility", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE,
                navigationVoiceScope = NavigationVoiceScope.ALWAYS,
                navigationActive = false,
                systemAudioRouting = false,
            ),
        )
        assertEquals(
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC),
            AudioChannelMapper.map(
                "compatibility", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS,
                navigationVoiceScope = NavigationVoiceScope.ALWAYS,
                navigationActive = false,
                systemAudioRouting = false,
            ),
        )
    }

    /**
     * B561: 开着开关时，`default`/`alert`/`compatibility` 都必须离开导航专声区。
     *
     * 这三条流上正是 iOS 放「界面咔哒声 + 键盘声」的地方 ——
     * 实测反馈的"打字声从车辆导航声道出来"就是 usage 12 泄漏到它们身上。
     */
    @Test
    fun uiSoundsStayOutOfTheNavigationZoneWhileNoRouteIsRunning() {
        listOf("default", "alert", "compatibility").forEach { audioType ->
            listOf(AudioChannelMappingMode.MOBILE_COMPATIBLE, AudioChannelMappingMode.AUTOMOTIVE_BUS)
                .forEach { mode ->
                    assertEquals(
                        "$mode/$audioType",
                        AudioChannelSelection(AudioChannel.SYSTEM, AudioContentType.SONIFICATION),
                        AudioChannelMapper.map(
                            audioType, 100, mode,
                            navigationVoiceScope = NavigationVoiceScope.GUIDANCE_ONLY,
                            navigationActive = false,
                            systemAudioRouting = true,
                        ),
                    )
                }
        }
    }

    /**
     * B561: 导航进行中，`default` 必须回到「只主驾发声」——
     * 否则这条修复就把用户特意要的导航专声一起砍掉了。
     */
    @Test
    fun theDefaultStreamFollowsTheNavigationSpeakerWhileNavigating() {
        assertEquals(
            AudioChannelSelection(
                AudioChannel.NAVIGATION, AudioContentType.SPEECH,
                AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
            ),
            AudioChannelMapper.map(
                "default", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE,
                navigationVoiceScope = NavigationVoiceScope.GUIDANCE_ONLY,
                navigationActive = true,
                systemAudioRouting = true,
            ),
        )
        // ALWAYS：有没有路线都进导航专声（0.2.14 行为）。
        listOf(true, false).forEach { active ->
            assertEquals(
                AudioChannel.NAVIGATION,
                AudioChannelMapper.map(
                    "default", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE,
                    navigationVoiceScope = NavigationVoiceScope.ALWAYS,
                    navigationActive = active,
                    systemAudioRouting = true,
                ).channel,
            )
        }
        // OFF：导航语音也不进专声区。
        assertEquals(
            AudioChannel.SYSTEM,
            AudioChannelMapper.map(
                "default", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE,
                navigationVoiceScope = NavigationVoiceScope.OFF,
                navigationActive = true,
                systemAudioRouting = true,
            ).channel,
        )
    }

    @Test
    fun automotiveMappingUsesTheBusSpecificCarPlayTypes() {
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "media",
            payloadType = 100,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
        )
    }

    @Test
    fun unknownTypesKeepTheMainHighAudioFallback() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "unknown",
            payloadType = AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "unknown",
            payloadType = 100,
            channel = AudioChannel.SYSTEM,
            contentType = AudioContentType.SONIFICATION,
        )
    }

    @Test
    fun navigationStreamTypeIsOnlyAppliedToGuidanceChannels() {
        val custom = 7
        // 只有真的落在导航专声区的那条流才吃自定义 stream；
        // 提示音走 SYSTEM 时用的是 STREAM_MUSIC（3），不受这个设置影响。
        assertEquals(
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, custom),
            AudioChannelMapper.map(
                "alert", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE, custom,
                navigationVoiceScope = NavigationVoiceScope.ALWAYS,
                systemAudioRouting = false,
            ),
        )
        assertEquals(
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, custom),
            AudioChannelMapper.map(
                "default", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS, custom,
                navigationVoiceScope = NavigationVoiceScope.ALWAYS,
                systemAudioRouting = false,
            ),
        )
        assertEquals(
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC, AudioManager.STREAM_MUSIC),
            AudioChannelMapper.map("media", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS, custom),
        )
        assertEquals(
            AudioChannelSelection(AudioChannel.SYSTEM, AudioContentType.SONIFICATION),
            AudioChannelMapper.map("alert", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE, custom),
        )
    }

    /**
     * B561: 主高音频流（type 102）永远是音乐，
     * 哪怕它叫 `default` 且当前没有导航 —— 不能被提示音策略误伤。
     */
    @Test
    fun theMainHighAudioStreamStaysMusic() {
        listOf(true, false).forEach { active ->
            assertEquals(
                AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC),
                AudioChannelMapper.map(
                    "default", AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO,
                    AudioChannelMappingMode.MOBILE_COMPATIBLE,
                    navigationActive = active,
                ),
            )
        }
    }

    /**
     * ★ 三方一致性：usage=12 与原厂 `STREAM_NAVI=12` 是同一个数字。
     *
     * 证据链：
     *  1. v59 实测：`AudioAttributes.setUsage(12)` → 只主驾发声（11 不行）
     *  2. 甘风 CarPlay（`com.ganfandw.carplay` 1.3.2，商业接收端）
     *     `AudioRenderer.usageFor(NAVIGATION)` 返回 12
     *  3. 原厂 `B561_Launcher.apk` 的 `SettingManager`：`const/16 v0, 0xc` → `STREAM_NAVI = 12`
     *
     * 三方都用 12 标记导航声区，说明这是 MTK/厂商约定，不是偏门技巧。
     *
     * ★ **注意这不等于 `setLegacyStreamType(12)` 有效** ——
     *   `STREAM_NAVI` 是 MTK 私有音频流 ID，不是 Android 的 streamType。
     *   实测 stream 2/3/4/9 无效，12 没测过。要试必须实车 A/B，
     *   不能照抄常量名就当成已验证。
     *
     * 本测试只**锁定这个观察值**，防止有人误把它当成已验证的路由参数。
     */
    @Test
    fun theVendorNavigationStreamIdMatchesTheNavigationUsage() {
        // 两个常量都是 12 —— 这个"巧合"是三方一致性的直接体现。
        // 用 assertTrue 而不是 assertEquals：本项目的 JUnit 没有三参数重载，
        // 而 (Int, Int) 会和 (long, long) / (Object, Object) 产生歧义。
        val usage = B561AudioConstants.NAVIGATION_USAGE_VENDOR_ROUTED
        val vendorStream = B561AudioConstants.VENDOR_STREAM_NAVI_OBSERVED
        assertTrue(
            "导航专声区的 usage 必须保持 12（v59 实测有效），实际=$usage",
            usage == 12,
        )
        assertTrue(
            "原厂 launcher 的 STREAM_NAVI 应与我们的 usage 观测值一致；" +
                "不等说明厂商改了约定（usage=$usage stream=$vendorStream），需要重新实测",
            usage == vendorStream,
        )
    }

    /**
     * 导航声道仍然用**默认** streamType，不是 12。
     *
     * 明确钉住这一点：`VENDOR_STREAM_NAVI_OBSERVED` 只是**观察记录**，
     * 不能因为它存在就把它接进 [AudioChannelMapper.map] ——
     * stream 路由实测无效（2/3/4/9 全试过），12 未测。
     * 真要试必须走设置页的显式开关 + 实车验证，不能默认打开。
     */
    @Test
    fun navigationKeepsTheDefaultStreamTypeRatherThanTheVendorStreamId() {
        listOf(AudioChannelMappingMode.MOBILE_COMPATIBLE, AudioChannelMappingMode.AUTOMOTIVE_BUS)
            .forEach { mode ->
                // ★ 必须显式关掉 systemAudioRouting —— v64 起 `alert`/`default`
                //   在没有导航路线时走 SYSTEM（这是修复"打字声误入导航专声区"的正解）。
                //   开着它的话这里拿到的是 SYSTEM，测的就不是导航声道了。
                val selection = AudioChannelMapper.map(
                    "alert", 100, mode,
                    navigationVoiceScope = NavigationVoiceScope.ALWAYS,
                    navigationActive = true,
                    systemAudioRouting = false,
                )
                assertTrue("$mode 应路由到 NAVIGATION", selection.channel == AudioChannel.NAVIGATION)
                assertTrue(
                    // DEFAULT_NAVIGATION_STREAM_TYPE = 14，不是厂商观测到的 12。
                    // 看到 STREAM_NAVI=12 就改streamType 是错的——那是 MTK 私有流 ID。
                    "$mode 的 streamType 应保持默认值，实际=${selection.streamType}",
                    selection.streamType == AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
                )
                assertTrue(
                    "$mode 的 streamType 不应等于厂商的 VENDOR_STREAM_NAVI_OBSERVED",
                    selection.streamType != B561AudioConstants.VENDOR_STREAM_NAVI_OBSERVED,
                )
            }
    }

    private fun assertMapped(
        mode: AudioChannelMappingMode,
        audioType: String,
        payloadType: Int,
        channel: AudioChannel,
        contentType: AudioContentType,
        streamType: Int = AudioManager.STREAM_MUSIC,
    ) {
        assertEquals(
            AudioChannelSelection(channel, contentType, streamType),
            AudioChannelMapper.map(audioType, payloadType, mode),
        )
    }
}
