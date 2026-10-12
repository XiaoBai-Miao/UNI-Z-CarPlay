package com.shilapi.xcertplay.media

import android.media.AudioManager

internal enum class AudioChannelMappingMode {
    MOBILE_COMPATIBLE,
    AUTOMOTIVE_BUS,
}

internal enum class AudioChannel {
    MEDIA,
    PHONE,
    ASSISTANT,
    NAVIGATION,

    /**
     * B561: 系统提示音（UI 咔哒声、按键声、提示 beep）。
     *
     * ## 为什么必须与 [NAVIGATION] 分开
     *
     * iOS 把**界面提示音与导航语音放在同一条 `default` 流**上，所以原来
     * 「`default`/`alert`/`compatibility` → NAVIGATION(usage 12)」这条映射
     * 在 B561 上表现为：**手机上打字、点击、锁屏的咔哒声从主驾导航喇叭出来**。
     *
     * 而 usage 12 是车机厂商自定义的「只主驾发声的导航声区」，本来是用户
     * 特意要的效果（导航语音只主驾听见），不该被 UI 提示音蹭上。
     *
     * 系统提示音用 [android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION]：
     * 语义正确（sonification = 界面反馈音），且不抢音频焦点（见
     * [AndroidMediaSink] 里的 `requestAudioFocus`），打字时音乐不会被顶掉。
     */
    SYSTEM,
}

internal enum class AudioContentType {
    MUSIC,
    SPEECH,
    SONIFICATION,
}

/**
 * B561 音频路由的**唯一常量源**。
 *
 * 之前这三处各有一份副本（`AndroidMediaSink` / `AudioChannelPreview` / 文档），
 * 改一处忘另两处就会让"设置页预览"与"实际播放"不一致 ——
 * 那类 bug 在实车上极难定位（声音对了但预览显示另一套）。
 */
object B561AudioConstants {
    /**
     * 让车机自行决定输出声区的 usage 值。
     *
     * **12 = AOSP `frameworks/base/.../AudioAttributes.java` 里的 `USAGE_UNDEFINED`**，
     * 语义是"usage 未定义，由厂商决定路由"。
     *
     * ## 为什么必须用字面量
     *
     * `USAGE_UNDEFINED` **不是** `android.jar` 的公开常量（只有 framework
     * 内部使用），编译期引用不到，所以只能写字面量 —— 用
     * `AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`(11) 是**无效**的：
     * 11 进 AOSP 标准 audio policy，APM 只会归到 primary output（全车响）。
     *
     * ## 三方一致的证据（2026-10-10 补齐第三份）
     *
     * 1. **v59 实测**：`setUsage(12)` → 只主驾发声（11 不行）
     * 2. **甘风 CarPlay**（`com.ganfandw.carplay` 1.3.2，商业接收端）
     *    `AudioRenderer.usageFor(NAVIGATION)` 返回 12
     * 3. **原厂 `B561_Launcher.apk`** 的 `SettingManager`
     *    （`com.huawei.hicar.demoapp.vehicle`）里`const/16 v0, 0xc`
     *    → `STREAM_NAVI = 12`
     *
     * 三方独立来源都用 12 标记导航声区 —— 这说明它是 MTK/厂商的**约定**，
     * 不是偏门技巧。厂商在它自己的音频流 ID 维度上也用 12。
     */
    const val NAVIGATION_USAGE_VENDOR_ROUTED = 12

    /**
     * 原厂 launcher 的 `STREAM_NAVI` 值，**仅作记录**，当前不参与路由。
     *
     * ★ 留着它是因为它与 [NAVIGATION_USAGE_VENDOR_ROUTED] 同为 12。
     *   但**这不代表 `setLegacyStreamType(12)` 有效** ——
     *   `STREAM_NAVI` 是 MTK 私有音频流 ID，不是 Android 的 streamType。
     *   实测 stream 2/3/4/9 都无效（usage 才是决定路由的那个），
     *   12 没测过。要试必须实车 A/B，不能照抄常量名就当成已验证。
     */
    const val VENDOR_STREAM_NAVI_OBSERVED = 12
}

/**
 * B561: `default` 流（导航语音 + UI 提示音共流）是否走「只主驾发声」的导航声区。
 *
 * - [GUIDANCE_ONLY]：只在**真的有导航路线**时把 `default` 送进导航声区。
 *   这是默认 —— 没在导航时 `default` 上只有 UI 提示音，必须走系统声道。
 * - [ALWAYS]：无条件走导航声区（DiPlay 0.2.14 原始行为，导航声始终只主驾）。
 * - [OFF]：从不走导航声区，全部并入普通媒体声道（导航语音全车可听）。
 *
 * ## 原厂怎么做的（2026-10-10 从 `B561_Launcher.apk` 挖到）
 *
 * 原厂 launcher 的 HiCar 集成用 `APP_FOCUS_TYPE_NAVIGATION` /
 * `APP_FOCUS_TYPE_VOICE_COMMAND` 划分**应用焦点**，而不是逐流判断 ——
 * 也就是说厂商在**更高一层**决定"导航进行中"，再由框架决定各流去向。
 *
 * 我们用"当前有没有导航路线"（`setNavigationActive`）逼近同一语义，
 * 差别是粒度更细、且不依赖厂商框架。方向一致。
 *
 * 公开可见是因为 [AndroidMediaSink] 是 public 类，其 setter 的参数就是本类型。
 */
enum class NavigationVoiceScope {
    GUIDANCE_ONLY,
    ALWAYS,
    OFF;

    companion object {
        fun fromKey(key: String?): NavigationVoiceScope =
            entries.firstOrNull { it.name == key } ?: GUIDANCE_ONLY
    }
}

internal data class AudioChannelSelection(
    val channel: AudioChannel,
    val contentType: AudioContentType,
    val streamType: Int = AudioManager.STREAM_MUSIC,
)

/**
 * Maps CarPlay stream metadata to Android audio routing values.
 *
 * Each selection carries the [AudioChannel] / [AudioContentType] pair used for usage-based
 * AudioAttributes, plus the stream type used when the stream-type AudioTrack path is active.
 * Guidance resolves to [AudioChannel.NAVIGATION]. Automotive routing keeps compatibility
 * audio on the media bus, so it receives the configured media buffer and output route.
 *
 * @param navigationVoiceScope B561: `default` 流是否允许进导航专声区。
 * @param navigationActive B561: 当前是否有导航路线（[NavigationVoiceScope.GUIDANCE_ONLY] 下才生效）。
 * @param systemAudioRouting B561: 是否启用「UI 提示音走系统声道」。
 *   关闭时 `alert`/`compatibility` 回到上游行为（并入导航/媒体），
 *   便于在车机上做 A/B 对照 —— 分不清是哪个流在响时先关掉它验证。
 */
internal object AudioChannelMapper {
    const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
    const val DEFAULT_NAVIGATION_STREAM_TYPE = 14

    fun map(
        audioType: String,
        payloadType: Int,
        mode: AudioChannelMappingMode,
        navigationStreamType: Int = DEFAULT_NAVIGATION_STREAM_TYPE,
        navigationVoiceScope: NavigationVoiceScope = NavigationVoiceScope.ALWAYS,
        navigationActive: Boolean = false,
        systemAudioRouting: Boolean = true,
    ): AudioChannelSelection {
        val normalized = audioType.lowercase()
        return when (mode) {
            AudioChannelMappingMode.MOBILE_COMPATIBLE -> mapMobileCompatible(
                normalized, payloadType, navigationStreamType,
                navigationVoiceScope, navigationActive, systemAudioRouting,
            )
            AudioChannelMappingMode.AUTOMOTIVE_BUS -> mapAutomotiveBus(
                normalized, payloadType, navigationStreamType,
                navigationVoiceScope, navigationActive, systemAudioRouting,
            )
        }
    }

    /**
     * 这条流是否会走 legacy stream（而非 usage 路由）。
     *
     * B561: 与 [map] 一样要看 [navigationVoiceScope] / [navigationActive] / [systemAudioRouting] ——
     * 「无导航时的 `default`」现在落在 SYSTEM，不吃 legacy stream，所以这里必须透传同一组参数，
     * 否则调用方会以为它还在导航专声区。
     */
    fun usesNavigationStream(
        audioType: String,
        payloadType: Int,
        advanced: Boolean,
        navigationVoiceScope: NavigationVoiceScope = NavigationVoiceScope.ALWAYS,
        navigationActive: Boolean = false,
        systemAudioRouting: Boolean = true,
    ): Boolean =
        !advanced && map(
            audioType, payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE,
            navigationVoiceScope = navigationVoiceScope,
            navigationActive = navigationActive,
            systemAudioRouting = systemAudioRouting,
        ).channel == AudioChannel.NAVIGATION

    private fun mapMobileCompatible(
        normalized: String,
        payloadType: Int,
        navigationStreamType: Int,
        scope: NavigationVoiceScope,
        navigationActive: Boolean,
        systemRouting: Boolean,
    ): AudioChannelSelection = when (normalized) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "media" -> AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        // B561: alert/compatibility 是**提示音**，不是导航语音。
        //   原来并进 NAVIGATION(usage 12)，实测手机打字/点击的咔哒声
        //   从主驾导航喇叭出来。SYSTEM 走 USAGE_ASSISTANCE_SONIFICATION，
        //   全车可听且不抢焦点。
        "alert", "compatibility" ->
            if (systemRouting) AudioChannelSelection(AudioChannel.SYSTEM, AudioContentType.SONIFICATION)
            else AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        // B561: `default` 是导航语音与 UI 提示音的**共流**，必须看是否有导航路线。
        "default" -> defaultStream(payloadType, navigationStreamType, scope, navigationActive, systemRouting)
        else -> mainHighAudioOrNavigation(
            payloadType, navigationStreamType, scope, navigationActive, systemRouting,
        )
    }

    private fun mapAutomotiveBus(
        normalized: String,
        payloadType: Int,
        navigationStreamType: Int,
        scope: NavigationVoiceScope,
        navigationActive: Boolean,
        systemRouting: Boolean,
    ): AudioChannelSelection = when (normalized) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "media" -> AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "compatibility" ->
            if (systemRouting) AudioChannelSelection(AudioChannel.SYSTEM, AudioContentType.SONIFICATION)
            else AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "alert" ->
            if (systemRouting) AudioChannelSelection(AudioChannel.SYSTEM, AudioContentType.SONIFICATION)
            else AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        "default" -> defaultStream(payloadType, navigationStreamType, scope, navigationActive, systemRouting)
        else -> mainHighAudioOrNavigation(
            payloadType, navigationStreamType, scope, navigationActive, systemRouting,
        )
    }

    /**
     * B561: `default`（及主高音频流）的去向。
     *
     * 只有「有导航路线」或「用户强制 ALWAYS」时才进导航专声区；否则按 UI
     * 提示音处理 —— 这正是「打字声不该从导航喇叭出来」的分界。
     */
    private fun defaultStream(
        payloadType: Int,
        navigationStreamType: Int,
        scope: NavigationVoiceScope,
        navigationActive: Boolean,
        systemRouting: Boolean,
    ): AudioChannelSelection {
        if (payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO) {
            return AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        }
        return when (scope) {
            NavigationVoiceScope.ALWAYS ->
                AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
            NavigationVoiceScope.GUIDANCE_ONLY ->
                if (navigationActive) {
                    AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
                } else if (systemRouting) {
                    AudioChannelSelection(AudioChannel.SYSTEM, AudioContentType.SONIFICATION)
                } else {
                    AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
                }
            NavigationVoiceScope.OFF ->
                if (systemRouting) AudioChannelSelection(AudioChannel.SYSTEM, AudioContentType.SONIFICATION)
                else AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        }
    }

    private fun mainHighAudioOrNavigation(
        payloadType: Int,
        navigationStreamType: Int,
        scope: NavigationVoiceScope,
        navigationActive: Boolean,
        systemRouting: Boolean,
    ): AudioChannelSelection =
        if (payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        } else {
            defaultStream(payloadType, navigationStreamType, scope, navigationActive, systemRouting)
        }
}
