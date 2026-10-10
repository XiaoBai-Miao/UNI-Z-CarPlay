package com.shilapi.xcertplay.hud

/**
 * 车辆实时信息 —— 卡片模式左栏在**没有导航**时显示的内容。
 *
 * ## 为什么需要它
 *
 * 左栏原本只承载转向指引(箭头 + 距离 + 路名)。没有导航时 `maneuverType` 为 null,
 * [ClusterTurnGuidance.fromAppleManeuver] 返回 null,整张卡片 `visibility = GONE` ——
 * 于是左栏整块空着。用户要的是"没导航时也别空,显示车况"。
 *
 * ## 数据来源
 *
 * 全部来自 B561 的 CarCabin VHAL(见 `CarCabinVehicleStatus`),属性 ID:
 * - 车速 `0x2140f4a9`,单位 0.01 km/h
 * - 挡位 `0x2140f477`,取值 21..27
 * - 续航/电量 见 RANGE_IDS / PERCENT_IDS 候选列表
 *
 * 实车日志确认 HAL 是通的:静止时 `getIntProperty 0x2140f4a9 data=0`、
 * 挡位 `getIntProperty 0x2140f477 data=14`。
 *
 * ## 显示策略
 *
 * **[speedKph] 为 null 时整块不显示** —— 与车辆状态上报的既有原则一致
 * (`CarCabinVehicleStatus.toSnapshot` 宁可返回 null 也不报假数据)。报一个假的 0
 * 会让驾驶员误以为车速传感器坏了,比空着更糟。
 */
data class ClusterVehicleStatus(
    /** 车速(km/h)。null = 读不到,此时整块隐藏。 */
    val speedKph: Float?,
    /** 挡位,VHAL 原始取值(21..27);null = 读不到。 */
    val gearRaw: Int? = null,
    /** 续航(km)。 */
    val rangeKm: Int? = null,
    /** 电量百分比 0..100。 */
    val batteryPercent: Int? = null,
) {
    /**
     * 是否值得显示 —— 至少要拿到车速,否则没有主数字可画。
     *
     * 挡位/续航/电量都缺失时也仍然显示:那至少是个"0"和单位,
     * 比空白左栏 informative。
     */
    val isDisplayable: Boolean get() = speedKph != null

    /**
     * 挡位字母。VHAL 取值 21..27 对应 P/R/N/D + 手动挡,映射表按 B561 实车日志
     * 的取值范围定,超出范围返回 null(不画,而不是画一个错的字母)。
     */
    val gearLabel: Char?
        get() = when (gearRaw) {
            21 -> 'P'
            22 -> 'R'
            23 -> 'N'
            24 -> 'D'
            25 -> 'S'
            26 -> 'M'
            27 -> 'L'
            else -> null
        }

    /**
     * 车速整数部分,用于大号数字。
     *
     * 负值夹到 0:VHAL 在熄火/倒车时可能给 −1 之类的无效值,
     * 直接显示会看到 "-1 km/h"。
     */
    val speedWhole: Int get() = (speedKph ?: 0f).coerceIn(0f, 599f).toInt()

    /** 小数部分(0.1 km/h 精度),无小数时显示空串,避免 "38.0" 这种假精度。 */
    val speedTenths: String
        get() {
            val value = speedKph ?: return ""
            val tenths = ((value.coerceIn(0f, 599f) - speedWhole) * 10).toInt()
            return if (tenths > 0) ".$tenths" else ""
        }

    companion object {
        /** 什么都不显示 —— VHAL 完全读不到时用它,而不是编一个假车速。 */
        val EMPTY = ClusterVehicleStatus(speedKph = null)
    }
}