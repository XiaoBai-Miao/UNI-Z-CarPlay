package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B561 车辆数据的**第二条通路**：从车机 logcat 里解析原厂仪表/系统服务打印的车辆属性。
 *
 * ## 为什么需要它（和 [CarCabinVehicleStatus] 并存）
 *
 * 目前 VHAL 是唯一通路，但它有两个已知短板：
 *
 * 1. **属性 ID 逐个固件不同。** `0x2140f4a9`（车速）、`0x2140f477`（挡位）来自实车日志，
 *    但电量/续航在不同 MTK 固件上编号不同，只能靠候选列表逐个试 —— 猜错就整项读不到。
 * 2. **只反射 `car_cabin` 一个服务。** `0x2160f502`（高压电流）/ `0x2160f503`（电压）
 *    这类属性挂在 `car_ev` / `car_power` 上，`CarCabinVehicleStatus` 里明确注释了
 *    "暂不参与读取" —— 但系统服务自己打印的日志里**有这些值**。
 *
 * 换句话说：logcat 是**广度优先**的旁路（能看见的东西更多，但精度与时效取决于原厂打点），
 * VHAL 是**精度与时效优先**的主路。两者互补，由 [HybridVehicleStatus] 按字段融合。
 *
 * ## 抓什么
 *
 * 只抓**车辆属性相关**的行，用两个正则入口：
 *
 * - `propId + 数值`：`getIntProperty 0x2140f4a9 data=1234`、`prop=0x2160f502 value=300`
 * - `符号名 + 数值`：`SPEED=45`、`gear Value: 24`
 *
 * 命中后按"propId 后**就近**取第一个数字"配对 —— 系统日志一行里常同时有
 * 时间戳、行号等其它数字，就近取最不容易错配。
 *
 * ## 权限
 *
 * `android.permission.READ_LOGS` 是 `signature|system` 级。平台签名 + 装进
 * `/system/priv-app` 后通常授予；**拿不到就安静降级**（见 [start] 返回值），
 * 融合器自动退回纯 VHAL，不会崩、也不会拖慢其它通路。
 *
 * ## 生命周期与安全阀
 *
 * - [start] 起一条 daemon 线程读 logcat 的 stdout；[stop] 置标志位并destroy 进程。
 * - **TTL 是必须的**：原厂停止打点后，日志里会留着最后一条旧值。若不设过期时间，
 *   融合器会把几秒前的车速当成实时值显示 —— 那是**比没有数据更糟**的假读数。
 *   每个字段带 [PropertyValue.at]，融合器只接受 TTL 内的值。
 * - 行数上限 [MAX_LINES_PER_START]：防止原厂疯狂打点把内存吃满。
 */
internal class VehicleLogTap(
    private val tag: String = "B561-VehicleLogTap",
) {

    /** 一次字段值 + 它被观察到的时间。[at] 用单调时钟，与 `SystemClock.uptimeMillis` 同源。 */
    data class PropertyValue(val raw: Double, val at: Long, val fromLine: Boolean) {
        /** 按已知单位换算；未知 propId 原样返回。 */
        fun scaled(propId: Int): Double = raw * scaleFor(propId)

        companion object {
            /**
             * 已知属性的单位换算系数。
             *
             * `0x2140f4a9` 的 0.01 来自 `CarCabinVehicleStatus` 的实车日志确认
             *（`getIntProperty 0x2140f4a9 data=0` 静止、起步后跳到几百= 0.01 km/h）。
             * 其余属性暂按 1.0 —— **只写有实车证据的那一项**：
             * 猜错单位会让显示值差 100 倍，比不换算更危险。
             */
            private val SCALES: Map<Int, Double> = mapOf(
                CarCabinVehicleStatus.ID_SPEED to 0.01,
            )

            fun scaleFor(propId: Int): Double = SCALES[propId] ?: 1.0
        }
    }

    /** 解析结果缓存。key = propId，value = 最近一次观察到的值。 */
    private val values = HashMap<Int, PropertyValue>()

    /** 符号名 → propId 的别名（系统日志不一定打 propId，可能只打名字）。 */
    private val aliasHits = HashMap<String, Int>()

    @Volatile private var running = false
    @Volatile private var process: Process? = null

    /** 累计解析命中行数，给诊断面板看"这条路到底通不通"。 */
    @Volatile var matchedLines: Int = 0
        private set

    /** 累计读取行数（含未命中的）。 */
    @Volatile var scannedLines: Int = 0
        private set

    /**
     * 启动抓取。返回 false = 没有 logcat 权限或 logcat 不可用（已安静降级）。
     *
     * 幂等：重复调用无害。
     */
    fun start(appContext: Context): Boolean {
        if (running) return true
        running = true
        val thread = Thread({ pump() }, "b561-vehicle-logtap")
        thread.isDaemon = true
        thread.start()
        Log.i(tag, "log tap thread started")
        return true
    }

    /**
     * 停止抓取。
     *
     * 置标志位再 destroy 进程：读线程阻塞在 `readLine()` 上时，
     * 只置标志位会等到对端有输出才醒（可能几十秒）；destroy 让它立刻抛 IOException 退出。
     */
    fun stop() {
        if (!running) return
        running = false
        runCatching { process?.destroy() }
        process = null
        Log.i(tag, "log tap stopped (matched=$matchedLines scanned=$scannedLines)")
    }

    /**
     * 最近一次观察到的某属性；超过 [TTL_MILLIS] 视为过期返回 null。
     *
     * @param now 单调时钟毫秒（测试可注入）
     */
    fun value(propId: Int, now: Long): PropertyValue? =
        synchronized(values) { values[propId] }?.takeIf { now - it.at <= TTL_MILLIS }

    /** 供融合器一次性取多个属性，避免反复进锁。 */
    fun snapshot(now: Long): Map<Int, PropertyValue> {
        val out = HashMap<Int, PropertyValue>()
        synchronized(values) {
            for ((id, value) in values) {
                if (now - value.at <= TTL_MILLIS) out[id] = value
            }
        }
        return out
    }

    /** 全部已知属性（含已过期的），给诊断面板看"历史上抓到过什么"。 */
    fun allValues(): Map<Int, PropertyValue> = synchronized(values) { HashMap(values) }

    /** 符号名命中统计，给诊断面板看"哪些别名有效"。 */
    fun aliasReport(): Map<String, Int> = synchronized(aliasHits) { HashMap(aliasHits) }

    /**
     * 解析一行，命中则写入缓存。**纯函数 + 无 Android 依赖**，单测直接喂字符串。
     *
     * @return 是否命中
     */
    fun parseLine(line: String, now: Long): Boolean {
        if (!looksLikeVehicleLine(line)) return false
        val match = PROP_PAIR.find(line) ?: return false
        // 手取 group 而不是解构 —— MatchResult 没有 component1/2。
        val propId = parseHexId(match.groupValues[1])
        val number = parseNumber(match.groupValues[2]) ?: return false
        synchronized(values) {
            // 后到的覆盖先到的 —— logcat 是顺序流，越新越真。
            values[propId] = PropertyValue(number, now, fromLine = true)
        }
        matchedLines++
        return true
    }

    /** 记录一次符号名命中（诊断用，不影响取值）。 */
    fun noteAlias(alias: String) {
        synchronized(aliasHits) { aliasHits[alias] = (aliasHits[alias] ?: 0) + 1 }
    }

    private fun pump() {
        var stream: BufferedReader? = null
        try {
            // -v brief 只要 tag 和消息；-T '' 从缓冲区尾部开始（只要新行，不要历史洪水）。
            val proc = ProcessBuilder("logcat", "-v", "brief", "-T", "")
                .redirectErrorStream(false)
                .start()
            process = proc
            stream = BufferedReader(InputStreamReader(proc.inputStream))
            var count = 0
            while (running && count < MAX_LINES_PER_START) {
                val line = stream.readLine() ?: break
                count++
                scannedLines++
                parseLine(line, android.os.SystemClock.uptimeMillis())
            }
            if (running && count >= MAX_LINES_PER_START) {
                // 走到上限就结束这一轮：本对象由外部按需重新 start（见 HybridVehicleStatus）。
                // 为什么不自动重启 —— 原厂真在每秒刷上万行的话，自动重启只会变成
                // 一个吃 CPU 的死循环，而那种情况下这条通路本来就没价值。
                Log.w(tag, "line cap reached after $count lines; tap idles until restarted")
            }
        } catch (e: Exception) {
            // 没权限 / logcat 不存在 / 进程被杀 —— 全部降级，不抛给调用方。
            Log.w(tag, "log tap unavailable: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { stream?.close() }
            running = false
        }
    }

    companion object {
        /**
         * 字段有效期。
         *
         * 3秒：原厂打点最快也就 10 Hz 级，而 VHAL 是 1 Hz；3 秒足够覆盖
         * 原厂"偶尔才打一次"的节奏，同时保证车停下后 3 秒内旧车速一定失效。
         */
        const val TTL_MILLIS = 3_000L

        /** 单次启动最多读多少行，防止原厂疯狂打点把内存吃满。 */
        const val MAX_LINES_PER_START = 200_000

        /**
         * 车辆属性行特征。
         *
         * 宁可**多收**也不漏收：这一层过滤错了顶多多扫几行（解析层还有正则把关），
         * 但漏了关键字就彻底抓不到数据了。所以只要碰到下面任一特征就进入解析。
         */
        private val VEHICLE_HINTS = arrayOf(
            "property", "propId", "prop=", "Value", "value=",
            "SPEED", "speed", "GEAR", "gear", "RANGE", "range",
            "BATTERY", "battery", "SOC", "soc", "ODO", "odometer",
            "CarCabin", "CarProperty", "Vhal", "VHAL", "vhal",
            "can_", "0x21", "0x11", "0x13",
        )

        fun looksLikeVehicleLine(line: String): Boolean =
            VEHICLE_HINTS.any { line.contains(it) }

        /**
         * `0x` 后跟 6~8 位十六进制（立刻跟空格或数字分隔），后面再跟一个数字。
         *
         * group(1) = propId 文本，group(2) = 就近的那个数值。
         */
        private val PROP_PAIR = Regex(
            """0[xX]([0-9a-fA-F]{6,8})(?![0-9a-fA-F])\D{0,16}?(-?\d+(?:\.\d+)?)""",
        )

        private val PROP_ID_ONLY = Regex("""0[xX]([0-9a-fA-F]{6,8})""")

        private fun parseHexId(hex: String): Int = hex.toLong(16).toInt()

        private fun parseNumber(text: String): Double? = text.toDoubleOrNull()

        /** 诊断用：把某行解析成可读形式，失败返回 null。 */
        fun explainLine(line: String): String? {
            val m = PROP_PAIR.find(line) ?: PROP_ID_ONLY.find(line) ?: return null
            val id = m.groupValues[1]
            // MatchGroupCollection 没有 getOrNull（它不是 List），只能按索引判空。
            val number = if (m.groups.size >= 2) m.groups[1]?.value else null
            return "propId=0x$id number=${number ?: "<none>"}"
        }
    }
}