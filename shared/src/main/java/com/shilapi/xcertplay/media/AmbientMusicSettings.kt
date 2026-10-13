package com.shilapi.xcertplay.media

import android.content.Context

/**
 * B561 氛围灯音乐跟随的设置。
 *
 * 与上游 BYD 版的区别：颜色是 **ARGB**，所以这里存**色相/饱和度**而不是
 * BYD 的 1..31 OEM 颜色编号。亮度上限由 [ChinatspAmbientLightPolicy.MAX_BRIGHTNESS] 决定。
 */
object AmbientMusicSettings {
    private const val PREFS = "ambient_music"

    data class Values(
        val enabled: Boolean = false,
        /** false 时只按固定颜色/亮度输出，不跟随音乐。 */
        val music: Boolean = true,
        /** 色相是否随能量摆动；false 时用 [baseHue] 固定色。 */
        val hueCycle: Boolean = true,
        /** 基础色相 0..360。 */
        val baseHue: Int = 210,
        /** 色相随能量摆动的幅度 0..360。 */
        val hueSpan: Int = 120,
        /** 饱和度 0..100。 */
        val saturation: Int = 100,
        /** 亮度上限 0..[ChinatspAmbientLightPolicy.MAX_BRIGHTNESS]；0 = 仅最低档。 */
        val brightness: Int = 6,
        /** 响应速度：0 慢 / 1 标准 / 2 快。 */
        val speed: Int = 1,
    )

    /**
     * 亮度上限。UI 层（common 模块）用它分档，避免直接依赖 shared 的 internal 类型。
     * 实际值见 [com.shilapi.xcertplay.hud.ChinatspAmbientLightPolicy.MAX_BRIGHTNESS]。
     */
    fun maxBrightness(): Int = com.shilapi.xcertplay.hud.ChinatspAmbientLightPolicy.MAX_BRIGHTNESS

    fun load(context: Context): Values {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun boolean(key: String, fallback: Boolean) =
            runCatching { p.getBoolean(key, fallback) }.getOrDefault(fallback)
        fun integer(key: String, fallback: Int) =
            runCatching { p.getInt(key, fallback) }.getOrDefault(fallback)
        return Values(
            enabled = boolean("enabled", false),
            music = boolean("music", true),
            hueCycle = boolean("hueCycle", true),
            baseHue = integer("baseHue", 210).coerceIn(0, 360),
            hueSpan = integer("hueSpan", 120).coerceIn(0, 360),
            saturation = integer("saturation", 100).coerceIn(0, 100),
            brightness = integer("brightness", 6)
                .coerceIn(0, com.shilapi.xcertplay.hud.ChinatspAmbientLightPolicy.MAX_BRIGHTNESS),
            speed = integer("speed", 1).coerceIn(0, 2),
        )
    }

    fun save(context: Context, values: Values) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", values.enabled)
            .putBoolean("music", values.music)
            .putBoolean("hueCycle", values.hueCycle)
            .putInt("baseHue", values.baseHue.coerceIn(0, 360))
            .putInt("hueSpan", values.hueSpan.coerceIn(0, 360))
            .putInt("saturation", values.saturation.coerceIn(0, 100))
            .putInt("brightness", values.brightness.coerceIn(0, com.shilapi.xcertplay.hud.ChinatspAmbientLightPolicy.MAX_BRIGHTNESS))
            .putInt("speed", values.speed.coerceIn(0, 2))
            .apply()
        AmbientMusicController.settingsChanged(context)
    }
}
