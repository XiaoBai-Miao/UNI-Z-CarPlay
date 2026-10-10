package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.hud.ClusterVehicleStatus
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot

/**
 * B561 车辆数据的**双路融合器**：VHAL（[CarCabinVehicleStatus]） + logcat（[VehicleLogTap]）。
 *
 * ## 为什么两条路要合起来看
 *
 * 两者的强弱项正好互补，不是冗余：
 *
 * | | VHAL | logcat |
 * |---|---|---|
 * | 时效 | 1 Hz 主动轮询，最准 | 取决于原厂打点节奏，可能稀疏 |
 * | 属性 ID | 逐固件不同，猜错就整项读不到 | 同上，但**能看到别的服务打的属性** |
 * | 覆盖面 | 只覆盖 `car_cabin` 一个服务 | `car_ev`/`car_power` 等系统服务打印的都算 |
 * | 可用性 | 有 `car_cabin` 服务就有 | **需要 READ_LOGS**（签名级），可能没有 |
 *
 * 典型场景：电量/续航在某个 MTK 固件上 VHAL 读不到（ID 猜错），
 * 但系统服务正在 logcat 里打这两个值 —— 这时只有双路才能显示出来。
 *
 * ## 融合规则（逐字段，不是整体二选一）
 *
 * 1. **VHAL 有值就用 VHAL** —— 主动轮询、时效可控，是更可信的源。
 * 2. **VHAL 缺项才用 logcat** —— 且必须 TTL 内（[VehicleLogTap.TTL_MILLIS]），
 *    过期值一律丢弃：那会变成"车已经停了仪表还显示 60"的假读数。
 * 3. **两路都没有 → 该字段为 null** —— 不编默认值。
 *
 * ## 来源标记
 *
 * 融合结果带 [sources]，明确每个字段是谁给的。设置页诊断面板直接显示它，
 * 这样"为什么这个值是空的"一眼能看出是**两路都没读到**，还是**只有一路读到了**。
 *
 * ## 生命周期
 *
 * [start] 同时起两条路（logcat 失败不影响 VHAL）；[stop] 两条都停。
 * 与 [CarCabinVehicleStatus] 一样：**每次 start 都要能重建**，因为 Activity
 * 的 onStart/onStop 会多轮调用。
 */
internal class HybridVehicleStatus(
    private val tag: String = "B561-VehicleStatus",
    private val lowBatteryPercent: Int = DEFAULT_LOW_BATTERY_PERCENT,
) : VehicleStatusProvider {

    private val cabin = CarCabinVehicleStatus(tag, lowBatteryPercent)
    private val logTap = VehicleLogTap("B561-VehicleLogTap")

    @Volatile private var started = false

    /**
     * 每个字段的来源，取值 [SOURCE_VHAL] / [SOURCE_LOG] / [SOURCE_NONE]。
     *
     * 只保留**最近一次融合**的来源，不做历史 —— 诊断要看的是"此刻是谁给的"，
     * 存历史反而会让人去翻一个三分钟前的记录。
     */
    @Volatile
    var sources: Map<String, String> = emptyMap()
        private set

    /** logcat 这条路是否真的通了（有无权限 / 有无命中）。给设置页显示。 */
    @Volatile
    var logTapActive: Boolean = false
        private set

    /** 上一轮融合里两路都缺的字段，诊断面板用来解释"为什么空着"。 */
    @Volatile
    var missing: List<String> = emptyList()
        private set

    fun start(appContext: Context) {
        if (started) return
        started = true
        cabin.start(appContext)
        logTapActive = logTap.start(appContext)
        Log.i(tag, "dual-source vehicle status started (logTap=$logTapActive)")
    }

    fun stop() {
        if (!started) return
        started = false
        cabin.stop()
        logTap.stop()
    }

    /**
     * 融合后的车况。任一路可用即返回。
     *
     * 与 [snapshot] 的差别：这里**不要求电量续航齐全**，
     * 仪表左栏只要有车速就该显示；而 iAP2 上报要求电量续航都齐（见 [toSnapshot]）。
     */
    fun currentVehicleStatus(): ClusterVehicleStatus? {
        val cabinReading = cabin.currentReading()
        // ★ snapshot() 内部已按 TTL 过滤 —— 过期值不会进这里，
        //   所以下面拿到的一定是"最近发生过"的读数。
        val log = logTap.snapshot(android.os.SystemClock.uptimeMillis())
        if (cabinReading == null && log.isEmpty()) return null

        // ---- 车速：没有它整块不显示（见 ClusterVehicleStatus 的说明）
        val speed = fuse(
            cabinReading?.speedKph,
            scaled(CarCabinVehicleStatus.ID_SPEED, log[CarCabinVehicleStatus.ID_SPEED]),
        )
        if (speed.value == null) return null

        // ---- 挡位
        val gear = fuse(
            cabinReading?.gear,
            log[CarCabinVehicleStatus.ID_GEAR]?.scaled(CarCabinVehicleStatus.ID_GEAR)?.toInt(),
        )

        // ---- 电量/续航：属性 ID 逐固件不同，两路都要按候选列表逐个试
        val percent = fuseCandidates(
            vhal = cabinReading?.batteryPercent?.let { listOf(it.toInt()) }.orEmpty(),
            ids = CarCabinVehicleStatus.PERCENT_IDS,
            log = log,
        )
        val range = fuseCandidates(
            vhal = cabinReading?.rangeKm?.let { listOf(it) }.orEmpty(),
            ids = CarCabinVehicleStatus.RANGE_IDS,
            log = log,
        )

        sources = mapOf(
            "speed" to speed.source,
            "gear" to gear.source,
            "battery" to percent.source,
            "range" to range.source,
        )
        missing = buildList {
            if (!gear.present) add("gear")
            if (!percent.present) add("battery")
            if (!range.present) add("range")
        }

        return ClusterVehicleStatus(
            speedKph = speed.value,
            gearRaw = gear.value,
            rangeKm = range.value,
            batteryPercent = percent.value,
        )
    }

    /**
     * 按候选属性 ID 列表取值 —— 电量与续航共用的那套逻辑。
     *
     * ## 为什么是"两路都按列表试"而不是"先 VHAL 走完列表、再 logcat 走完列表"
     *
     * 后者会选中**VHAL 的第 3 个候选**，而前者可能选中 **logcat 的第 1 个候选**
     * —— 只要第一路有值就用第一路。
     *
     * 哪个更好？**第一行那种**：属性 ID 越靠前证据越强（都是按实车日志从先到后加的），
     * 所以"某一路的第一个候选"比"另一路的第三个候选"更可信。
     *
     * @param vhal VHAL 已读到的值（为空表示这一路没读到）
     * @param ids  候选属性 ID，按证据强度排序
     * @param log  logcat 读数（已过滤 TTL）
     */
private fun fuseCandidates(
            vhal: List<Int>,
            ids: IntArray,
            log: Map<Int, VehicleLogTap.PropertyValue>,
        ): Fused<Int> {
            // VHAL 只有一个值（CabinAccess 已按自己的候选列表选出），
            // 所以只要它非空，第一轮就必然返回 —— 后面几轮只可能在logcat 里找。
            val fromVhal = vhal.firstOrNull()
            for (id in ids) {
                if (fromVhal != null) return Fused(fromVhal, SOURCE_VHAL)
                val fromLog = log[id]?.scaled(id)?.toInt()
                if (fromLog != null) return Fused(fromLog, SOURCE_LOG)
            }
            return Fused(null, SOURCE_NONE)
        }

    /**
     * iAP2 上报用。
     *
     * 电量或续航缺任一项就返回 null —— 手机上显示"0% 电量"比不显示更糟
     * （见 [CarCabinVehicleStatus.CabinReading.toSnapshot] 的同款理由）。
     * 差别只在于：这里的 null 可能由 logcat 补齐了任一项，这是双路的价值所在。
     */
    override fun snapshot(): VehicleStatusSnapshot? {
        val merged = currentVehicleStatus() ?: return null
        val percent = merged.batteryPercent?.toDouble() ?: return null
        if (percent !in 0.0..100.0) return null
        val range = merged.rangeKm ?: return null
        return VehicleStatusSnapshot(
            rangeKm = range.coerceIn(0, 0xffff),
            rangeWarning = percent <= lowBatteryPercent,
            batteryPercent = percent,
            currentChargeWh = null,
            maxChargeWh = null,
            maxRangeKm = range.coerceIn(0, 0xffff),
            charging = false,
        )
    }

    /** 原厂日志里抓到的全部属性（含过期），给诊断面板。 */
    fun logTapSnapshot(): Map<Int, VehicleLogTap.PropertyValue> = logTap.allValues()

    /** logcat 扫描/命中计数，给诊断面板判断这条路有没有在干活。 */
    fun logTapStats(): Triple<Int, Int, Boolean> =
        Triple(logTap.scannedLines, logTap.matchedLines, logTapActive)

    /** 车况诊断报告，接进 [VehicleProbe.report] 的诊断导出。 */
    fun diagnosticReport(): String = buildString {
        appendLine("=== 双路车辆数据 (VHAL + logcat) ===")
        appendLine("VHAL: cabin=${if (started) "started" else "stopped"}")
        appendLine("logTap: active=$logTapActive")
        val (scanned, matched, _) = logTapStats()
        appendLine("logTap 扫描行数=$scanned 命中行数=$matched")
        appendLine("读数变化时的来源:")
        if (sources.isEmpty()) appendLine("  (尚未融合)")
        for ((field, src) in sources) appendLine("  $field <- $src")
        appendLine("两路都缺的字段: ${if (missing.isEmpty()) "无" else missing.joinToString(",")}")
        appendLine("--- logcat 抓到的属性（含过期，便于回溯历史）---")
        val values = logTap.allValues()
        if (values.isEmpty()) {
            appendLine("  (无 —— 检查 READ_LOGS 权限是否授予)")
        } else {
            val now = android.os.SystemClock.uptimeMillis()
            for ((id, value) in values.entries.sortedBy { it.key }) {
                val age = now - value.at
                appendLine(
                    "  0x%08x = %s (age=%dms%s)".format(
                        id,
                        value.scaled(id).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.2f".format(it) },
                        age,
                        if (age > VehicleLogTap.TTL_MILLIS) " STALE" else "",
                    ),
                )
            }
        }
    }

    /**
     * 把日志里的一个属性值取成可用读数。
     *
     * @param id 属性 ID —— **必须传真实 ID**：单位换算表以 ID 为 key
     *   （车速 `0x2140f4a9` 是 0.01 km/h）。传 0 会跳过换算，
     *   车速就会大 100 倍（0.01 的单位当成 km/h）。
 */
    private fun scaled(id: Int, value: VehicleLogTap.PropertyValue?): Float? =
        value?.let {
            val raw = it.scaled(id).toFloat()
            raw.takeIf { v -> v.isFinite() && v >= 0f }
        }

    companion object {
        private const val TAG = "B561-VehicleStatus"
        private const val DEFAULT_LOW_BATTERY_PERCENT = 20

        const val SOURCE_VHAL = "VHAL"
        const val SOURCE_LOG = "logcat"
        const val SOURCE_NONE = "none"

        /**
         * 单字段的取值结果 + 来源。
         *
         * 抽成 data class 而不是裸 `Pair<Float?, String>` 是为了让单测能直接断言
         * "值与来源同时正确" —— 融合最容易出的错就是值对了但来源标错
         * （诊断面板会误导人以为 VHAL 通了）。
         */
        data class Fused<T>(val value: T?, val source: String) {
            val present: Boolean get() = value != null
        }

        /**
         * **融合规则本体**（纯函数，可单测）。
         *
         * 规则只有一条，但它是整个双路设计的核心：
         * > VHAL 有值用 VHAL（主动轮询、时效可控）；VHAL 缺项才用 logcat。
         *
         * @param fromVhal VHAL 读数，null = 未读到
         * @param fromLog  logcat 读数，**调用方必须已按 TTL 过滤**
         */
        fun <T> fuse(fromVhal: T?, fromLog: T?): Fused<T> = when {
            fromVhal != null -> Fused(fromVhal, SOURCE_VHAL)
            fromLog != null -> Fused(fromLog, SOURCE_LOG)
            else -> Fused(null, SOURCE_NONE)
        }
    }
}