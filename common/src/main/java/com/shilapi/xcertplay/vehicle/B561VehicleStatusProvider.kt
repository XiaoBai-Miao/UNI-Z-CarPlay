package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.hud.ClusterVehicleStatus
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot

/**
 * B561 的车况数据源 —— 喂给 CarPlay 的 `0xA100/0xA101/0xA102` VehicleStatus 通道。
 *
 * ## 双路数据源
 *
 * v67 起本类只做**门面**，实际取值交给 [HybridVehicleStatus]：
 *
 * - **VHAL**（[CarCabinVehicleStatus]）：1 Hz 主动轮询，时效准、属性 ID 需逐固件试。
 * - **logcat**（[VehicleLogTap]）：解析系统服务打印的车辆属性，广度大但需 READ_LOGS。
 *
 * 融合规则见 [HybridVehicleStatus.currentVehicleStatus]：逐字段 VHAL 优先、
 * logcat 补缺，且日志值必须 TTL 内（过期值丢弃，否则会把旧车速当实时值显示）。
 *
 * ## 谁在消费
 *
 * 手机上 CarPlay 地图（高德/百度）在**电量与续航**那一栏显示的就是这里回传的值。
 * 协议流程：手机发 `0xA100`(start) → 车机周期发 `0xA101`(update，30s 一帧) →
 * 手机发 `0xA102`(stop)。发包节奏由 `Iap2VehicleStatus` 管，本类只回答
 * "此刻 snapshot 是什么"。
 *
 * ## 为什么不发车速 / 挡位
 *
 * [VehicleStatusSnapshot] 只有续航与电池，**没有车速/挡位字段** —— CarPlay
 * 协议这一段就是"车机→手机车况"，不含车速。车速要显示在**仪表**上，那是画到
 * 副屏的另一条路，不走 iAP2。
 *
 * ## 读不到就返回 null
 *
 * 电量或续航缺任一项就返回 null（融合器里两路都没拿到才算缺）。
 * **宁可整个不发，也不发一个假的 0** —— 手机上显示"0% 电量"比不显示更糟。
 *
 * ## 生命周期
 *
 * [start] / [stop] 要成对调（在 Activity 的 onStart/onStop）。
 * 两条通路的轮询线程都是 daemon 的，漏调 stop 不会挂住进程，但会白跑轮询。
 */
class B561VehicleStatusProvider(
    /** 电量低于此百分比就置rangeWarning，手机会显示低电量提示。 */
    private val lowBatteryPercent: Int = DEFAULT_LOW_BATTERY_PERCENT,
) : VehicleStatusProvider {
    private val hybrid = HybridVehicleStatus(LOW_PERCENT_TAG, lowBatteryPercent)
    @Volatile private var started = false

    /** 开启双路采集（VHAL + logcat）。重复调用无害。 */
    fun start(context: Context) {
        if (started) return
        started = true
        hybrid.start(context)
        Log.i(TAG, "B561 vehicle status polling started (dual source)")
    }

    /** 停采集。 */
    fun stop() {
        if (!started) return
        started = false
        hybrid.stop()
        Log.i(TAG, "B561 vehicle status polling stopped")
    }

    /**
     * 当前车况。null = 两路都没有可用读数。
     *
     * 直接读融合器的缓存，不额外触发反射 —— VHAL 自己每秒轮询一次，
     * iAP2 侧每 30 s 才要一帧；logTap 则是常驻读流。
     */
    override fun snapshot(): VehicleStatusSnapshot? =
        if (started) hybrid.snapshot() else null

    /**
     * 最近一次读到的完整车况（含车速/挡位），给设置页的监测面板显示。
     *
     * 与 [snapshot] 不同：这里**不要求电量续航齐全**，只要任一路读到就能显示 ——
     * 监测面板的用途就是"告诉我两条路各通不通、读到了什么"。
     */
    fun lastReading(): ClusterVehicleStatus? =
        if (started) hybrid.currentVehicleStatus() else null

    /** 双路诊断报告，接进 [VehicleProbe.report] 的诊断导出。 */
    fun diagnosticReport(): String =
        if (started) hybrid.diagnosticReport() else "(dual-source vehicle status not started)"

    /** 每字段来源（"VHAL" / "logcat" / "none"），给设置页显示。 */
    fun fieldSources(): Map<String, String> = hybrid.sources

    /** logcat 通路是否真的通了（有无 READ_LOGS）。 */
    fun logTapActive(): Boolean = hybrid.logTapActive

    companion object {
        private const val TAG = "DiPlay-B561-Vehicle"
        private const val DEFAULT_LOW_BATTERY_PERCENT = 20
        /** 双路轮询的日志 tag，与设置页监测面板里显示的来源一致。 */
        private const val LOW_PERCENT_TAG = "B561-VehicleStatus"
    }
}