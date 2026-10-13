package com.shilapi.xcertplay.hud

/**
 * B561（长安 UNI-Z）氛围灯的纯校验与恢复规则。
 *
 * 移植自上游 DiPlay 的 `BydAmbientLightPolicy`，但取值范围按 B561 重定：
 *
 * - **颜色是 ARGB int**，不是 BYD 的 1..31 OEM 编号。证据：原厂
 *   `B561_SmartLights` 的 `res/values/arrays.xml` 里 `RecommendedColors`
 *   全是 `#AARRGGBB`（如 `#FFFF0006`），而 `LightStatusProcessManager.setColorValue()`
 *   直接把这个 int 写进 `int[6]`。所以这里**不限制颜色范围**，只要非 0。
 * - **亮度**走属性 557971185，`setBrightNess()` 只把 `<1` 的夹到 1，本身无上限。
 *   原厂 UI 的上限未实测，这里保守取 [MAX_BRIGHTNESS]，实测后可调。
 * - **分区**：原厂一次性把同一颜色/亮度写进 `int[6]`（6 路），没有 BYD 那种
 *   front/back/area 独立概念。因此 [Snapshot] 的 front/back 存同一值、
 *   area 恒为 1，`AmbientLampSession` 的恢复逻辑可原样复用。
 *
 * 属性 ID（`CarCabinManager.setIntArrayProperty`）：
 * - 颜色 557971184
 * - 亮度 557971185
 */
internal object ChinatspAmbientLightPolicy {
    const val LEASE_MILLIS = 15_000L
    const val MIN_APPLY_INTERVAL_MILLIS = 200L
    const val READBACK_POLL_MILLIS = 50L

    /** 保守亮度上限；原厂 UI 实际上限未实测，确认后可放大。 */
    const val MAX_BRIGHTNESS = 10

    const val PROP_COLOR = 557971184
    const val PROP_BRIGHTNESS = 557971185

    data class Snapshot(
        val frontColor: Int,
        val backColor: Int,
        val frontBrightness: Int,
        val backBrightness: Int,
        val area: Int,
    )
    data class Values(val color: Int, val rawBrightness: Int, val area: Int)
    data class RestoreStep(val values: Values? = null, val areaOnly: Int? = null)

    fun validSnapshot(s: Snapshot): Boolean =
        s.frontColor != 0 && s.backColor != 0 &&
            s.frontBrightness in 1..MAX_BRIGHTNESS && s.backBrightness in 1..MAX_BRIGHTNESS &&
            s.area == 1

    fun validApply(area: Int, color: Int, rawBrightness: Int): Boolean =
        area == 1 && color != 0 && rawBrightness in 1..MAX_BRIGHTNESS

    /** SDK display brightness 0 is raw 1: minimum output, not verified physical power-off. */
    fun minimumTarget(current: Snapshot): Snapshot {
        require(validSnapshot(current))
        return current.copy(frontBrightness = 1, backBrightness = 1)
    }

    /** Preserve the current zone colors, then restore the current area selection. */
    fun minimumSteps(current: Snapshot): List<RestoreStep> {
        val target = minimumTarget(current)
        return listOf(
            RestoreStep(values = Values(target.frontColor, 1, 1)),
            RestoreStep(values = Values(target.backColor, 1, 1)),
            RestoreStep(areaOnly = target.area),
        )
    }

    /** Equal original zones can be restored atomically with the known-good ALL-area batch. */
    fun restoreSteps(s: Snapshot): List<RestoreStep> {
        if (s.frontColor == s.backColor && s.frontBrightness == s.backBrightness) {
            val steps = mutableListOf(RestoreStep(values = Values(s.frontColor, s.frontBrightness, 1)))
            if (s.area != 1) steps += RestoreStep(areaOnly = s.area)
            return steps
        }
        val steps = mutableListOf<RestoreStep>()
        steps += RestoreStep(values = Values(s.frontColor, s.frontBrightness, 1))
        steps += RestoreStep(values = Values(s.backColor, s.backBrightness, 1))
        steps += RestoreStep(areaOnly = s.area)
        return steps
    }
}
