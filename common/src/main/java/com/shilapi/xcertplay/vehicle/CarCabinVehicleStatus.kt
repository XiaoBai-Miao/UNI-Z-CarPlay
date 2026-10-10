package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.hud.ClusterVehicleStatus
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 长安欧尚 B561 车辆数据源 —— 通过 AOSP 标准 VHAL 把车辆信息传给 CarPlay。
 *
 * ## 通道
 *
 * CarPlay 侧(已在代码库中就绪,无需改动协议栈):
 * ```
 * iPhone --0xA100 StartVehicleStatusUpdates--> 车机
 * 车机    --0xA101 VehicleStatusUpdate-->    iPhone      ← 本类提供数据
 * ```
 * 参数 id 遵循 Apple iAP2 消息规范,见 [com.shilapi.xcertplay.transport.Iap2VehicleStatus]。
 *
 * ## 数据来源
 *
 * `android.car.hardware.cabin.CarCabinManager`(API 29 起为正式 API,
 * MTK 在 Android 9 上把它编进了 framework,与原厂仪表 `B561_Dashboard.apk`
 * 走的是**同一个 HAL**)。全部反射调用,编译期不依赖 car-* 类库。
 *
 * ## 为什么读不到就返回 null
 *
 * `VehicleStatusProvider.snapshot()` 返回 null 时,识别信息里
 * `vehicleStatusEnabled` 会被置false —— 这是**故意**的:
 * 宁可告诉 iPhone"本机没有车辆数据",也不能报一个假的续航/电量,
 * 否则 Apple 地图会给出错误的低电量提醒和充电站推荐。
 */
class CarCabinVehicleStatus(
    private val tag: String = "B561-VehicleStatus",
    /** 低电量告警阈值,可由设置覆盖(默认与原厂一致)。 */
    private val lowChargePercent: Int = 15,
) : VehicleStatusProvider {

    @Volatile
    private var context: Context? = null

    @Volatile
    private var started = false

    /** 最近一次读数,后台定时刷新,[snapshot] 只读缓存,绝不阻塞 iAP2循环。 */
    @Volatile
    private var latest: VehicleStatusSnapshot? = null

    /**
     * 最近一次**原始**读数,保留挡位/车速等[snapshot] 里丢掉的字段。
     *
     * 为什么不能只留 [latest]:`VehicleStatusSnapshot` 是 iAP2 上报用的形态,
     * 只含电量/续航,**不含挡位和车速**。而仪表左栏在没有导航时要显示
     * "车速大字 + 挡位",那正是从这份原始读数来的。
     */
    @Volatile
    private var latestReading: CabinReading? = null

    /**
     * 轮询线程池。**必须延迟到 [start] 时再建**。
     *
     * 原来它在构造时创建一次，而 [stop] 调的是 `shutdownNow()` —— 线程池一旦终止就**永久**
     * 不能接受新任务。`CarPlayHostActivity` 里这对调用是 `onStart` → [start]、
     * `onStop` → [stop]，所以第二次进入 CarPlay 时 `onStart` 会往一个已终止的池子里提交
     * `scheduleWithFixedDelay`，抛 `RejectedExecutionException` 直接崩在主线程：
     * ```
     * RejectedExecutionException: Task ... rejected from ScheduledThreadPoolExecutor@...[Terminated, pool size = 0, ...]
     *     at CarCabinVehicleStatus.start(CarCabinVehicleStatus.kt:64)
     *     at CarPlayHostActivity.onStart(CarPlayHostActivity.kt:716)
     * ```
     * 所以这里每次 [start] 都重建，[stop] 只 shutdown 不置空。
     */
    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** CarCabinManager 的反射句柄,首次使用时解析。 */
    private val cabin: CabinAccess? by lazy { CabinAccess() }

    /**
     * 定时读取间隔。
     *
     * ★ 为什么从 30 s 改成 1 s:这个周期原先是**跟着 iAP2 的上报周期**走的,
     * 但 iAP2 侧自己就有节流 —— [com.shilapi.xcertplay.transport.Iap2VehicleStatus]
     * 的 `UPDATE_INTERVAL_MILLIS` 决定真正发包的频率,`snapshot()` 只是读缓存。
     * 也就是说 30 s 轮询对上报毫无影响,却让仪表车速 30 s 才更新一次 ——
     * 车已经开出去半公里了,左栏数字还是停车时的 0。
     *
     * 1 s 是权衡:再快对仪表没有感知收益,而每次 [CabinAccess.read] 要打 4 组
     * 反射调用,1 Hz 完全在预算内。
     */
    fun start(appContext: Context) {
        context = appContext.applicationContext
        if (started) return
        started = true
        val pool = executor?.takeUnless { it.isShutdown } ?: Executors
            .newSingleThreadScheduledExecutor { r ->
                Thread(r, "b561-vehicle-status").apply { isDaemon = true }
            }
            .also { executor = it }
        try {
            pool.scheduleWithFixedDelay(::poll, 0, READ_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // 与 onStop 抢跑时不该把 Activity 一起带崩。下次 start 会重建线程池。
            started = false
            Log.w(tag, "vehicle status poll rejected by the executor; retrying on the next start()")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        // 只 shutdown,不清空引用 —— [start] 会用 takeUnless { isShutdown } 判断是否重建。
        executor?.shutdownNow()
    }

    override fun snapshot(): VehicleStatusSnapshot? = latest

    /** 最近一次读到的车速(km/h),供 CarPlay 的位置上报使用;读不到时返回 null。 */
    fun currentSpeedKph(): Float? = latestSpeed

    @Volatile
    private var latestSpeed: Float? = null

    /**
     * 仪表 HUD 用的车辆状态快照;**读不到车速时返回 null**。
     *
     * 返回 null 而不是 [ClusterVehicleStatus.EMPTY]:调用方靠null 判断
     * "该不该显示",EMPTY 的 `isDisplayable` 也是 false,两者等价,但 null
     * 语义更直白—— 没有数据就是没有数据。
     */
    fun currentVehicleStatus(): ClusterVehicleStatus? {
        val reading = latestReading ?: return null
        val speed = reading.speedKph?.takeIf { it >= 0f } ?: return null
        return ClusterVehicleStatus(
            speedKph = speed,
            gearRaw = reading.gear,
            rangeKm = reading.rangeKm,
            batteryPercent = reading.batteryPercent?.toInt(),
        )
    }

    /**
     * 上一轮的 [currentVehicleStatus],只用于[poll] 里判断"变了没有"。
     * 单独存一份是因为 [currentVehicleStatus] 每次都会 new 对象。
     */
    @Volatile
    private var latestVehicleStatus: ClusterVehicleStatus? = null

    /**
     * 读数变化回调,供仪表 HUD 增量刷新。
     *
     * **在轮询线程上回调**,不是主线程 —— 订阅方必须自己切线程。
     * 只在值真的变了时才回调,静止时不会重复通知。
     */
    @Volatile
    var onReadingChanged: ((ClusterVehicleStatus?) -> Unit)? = null

    private fun poll() {
        val app = context ?: return
        val read = runCatching { cabin?.read(app) }.getOrNull()
        if (read == null) {
            if (latestReading == null) {
                // 只在从未成功过时提示一次,避免每个周期刷一次日志。
                Log.w(tag, "CarCabinManager unavailable on this firmware")
            }
            return
        }
        val previous = latest
        val previousStatus = latestVehicleStatus
        val snapshot = read.toSnapshot(lowChargePercent)
        if (snapshot != previous) {
            Log.i(tag, "cabin battery=${read.batteryPercent}% range=${read.rangeKm}km gear=${read.gear}")
        }
        latest = snapshot
        latestReading = read
        latestSpeed = read.speedKph?.takeIf { it >= 0f }

        // ★ 车速每时每刻都在变,必须单独比较一次。
        //   只比snapshot(电量/续航)的话,车从静止起步那一帧左栏还是空白 ——
        //   而那恰恰是驾驶员最需要看到数字的时刻。
        val status = currentVehicleStatus()
        if (status != previousStatus) {
            latestVehicleStatus = status
            runCatching { onReadingChanged?.invoke(status) }
                .onFailure { Log.w(tag, "onReadingChanged failed: ${it.message}") }
        }
    }

    /** CarCabinManager 上一次读到的原始值；null = 从未成功读到。 */
    fun currentReading(): CabinReading? = latestReading

    /** CarCabinManager 上一次读到的原始值。 */
    data class CabinReading(
        val batteryPercent: Double?,
        val rangeKm: Int?,
        val speedKph: Float?,
        val gear: Int?,
        val odometerKm: Float?,
    ) {
        /**
         * 转成 iAP2 VehicleStatusUpdate 需要的形态。
         *
         * 缺少电量或续航时返回 null —— 见类注释:不能报假数据。
         */
        fun toSnapshot(lowPercent: Int): VehicleStatusSnapshot? {
            val percent = batteryPercent ?: return null
            if (percent !in 0.0..100.0) return null
            val range = rangeKm ?: return null
            return VehicleStatusSnapshot(
                rangeKm = range.coerceIn(0, 0xffff),
                rangeWarning = percent <= lowPercent,
                batteryPercent = percent,
                // 能量值本机拿不到就不填,协议允许省略:
                // "Unknown energy is omitted, never represented as an empty battery."
                currentChargeWh = null,
                maxChargeWh = null,
                maxRangeKm = range.coerceIn(0, 0xffff),
                charging = false,
            )
        }
    }

    /**
     * CarCabinManager 的纯反射封装。
     *
     * 三个方法都可能是编译期不存在的(不同 MTK 固件裁剪不同),因此全部
     * 逐个 try,缺哪个就少报哪个字段,不整体放弃。
     */
    private class CabinAccess {
        private val managerClass: Class<*>? = runCatching {
            Class.forName("android.car.hardware.cabin.CarCabinManager")
        }.getOrNull()

        private val getInt: Method? = findMethod("getCarCabinIntValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val getFloat: Method? = findMethod("getCarCabinFloatValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val getBoolean: Method? = findMethod("getCarCabinBooleanValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

        private fun findMethod(name: String, vararg params: Class<*>?): Method? {
            val cls = managerClass ?: return null
            return runCatching {
                cls.getMethod(name, *params)
            }.getOrNull()
        }

        fun read(context: Context): CabinReading? {
            val manager = manager(context) ?: return null
            val intFn = getInt
            val floatFn = getFloat
            if (intFn == null && floatFn == null) return null
            return CabinReading(
                batteryPercent = readPercent(manager, intFn, floatFn),
                rangeKm = readRange(manager, intFn, floatFn),
                speedKph = readSpeed(manager, floatFn, intFn),
                gear = readGear(manager, intFn),
                odometerKm = readOdometer(manager, floatFn, intFn),
            )
        }

        private fun manager(context: Context): Any? {
            val cls = managerClass ?: return null
            // AOSP 服务名是 "car_cabin";部分 MTK 固件也接受 "cabin"。
            for (name in SERVICE_NAMES) {
                val svc = runCatching { context.getSystemService(name) }.getOrNull()
                if (svc != null && cls.isInstance(svc)) return svc
            }
            return null
        }

        private fun intOf(manager: Any, fn: Method?, id: Int): Int? {
            fn ?: return null
            return runCatching { fn.invoke(manager, id, 0) as? Int }.getOrNull()
        }

        private fun floatOf(manager: Any, fn: Method?, id: Int): Float? {
            fn ?: return null
            return runCatching { fn.invoke(manager, id, 0f) as? Float }.getOrNull()
        }

        // VHAL 属性 ID 来自实车日志(见 B561深度适配说明.md 第一节):
        //   0x2140f4a9 车速,单位 0.01 km/h,131 次采样
        //   0x2140f477 挡位,取值 21..27
        // 电量与续航的属性 ID 在不同 MTK 固件上不一致,因此按候选列表逐个试,
        // 取第一个有值的 —— 宁可用候选值,也不要因为猜错 ID 就完全不报。
        private fun readPercent(manager: Any, intFn: Method?, floatFn: Method?): Double? {
            for (id in PERCENT_IDS) {
                floatOf(manager, floatFn, id)?.let { if (it > 0f) return it.toDouble() }
                intOf(manager, intFn, id)?.let { if (it > 0) return it.toDouble() }
            }
            return null
        }

        private fun readRange(manager: Any, intFn: Method?, floatFn: Method?): Int? {
            for (id in RANGE_IDS) {
                intOf(manager, intFn, id)?.let { if (it > 0) return it }
                floatOf(manager, floatFn, id)?.let { if (it > 0f) return it.toInt() }
            }
            return null
        }

        private fun readSpeed(manager: Any, floatFn: Method?, intFn: Method?): Float? {
            // 车速:0x2140f4a9,单位 0.01 km/h
            val raw = floatOf(manager, floatFn, ID_SPEED) ?: intOf(manager, intFn, ID_SPEED)?.toFloat()
            return raw?.let { it / 100f }
        }

        private fun readGear(manager: Any, intFn: Method?): Int? = intOf(manager, intFn, ID_GEAR)

        private fun readOdometer(manager: Any, floatFn: Method?, intFn: Method?): Float? =
            floatOf(manager, floatFn, ID_ODOMETER) ?: intOf(manager, intFn, ID_ODOMETER)?.toFloat()

        private companion object {
            val SERVICE_NAMES = arrayOf("car_cabin", "cabin", "car")

            // ★ B561 v67: 属性 ID 常量上移到外层 companion（见文件末尾），
            //   这里用引用而不是各自定义一份 ——
            //   两处各写一个同值常量的话，改了一处忘了另一处，
            //   就会变成"VHAL 读 A 属性、日志按B 属性换算"这种极难查的错。
            val ID_SPEED = CarCabinVehicleStatus.ID_SPEED
            val ID_GEAR = CarCabinVehicleStatus.ID_GEAR
            val ID_ODOMETER = CarCabinVehicleStatus.ID_ODOMETER
            val PERCENT_IDS = CarCabinVehicleStatus.PERCENT_IDS
            val RANGE_IDS = CarCabinVehicleStatus.RANGE_IDS
        }
    }

    companion object {
        /** VHAL 轮询周期,理由见 [start] 的注释。 */
        const val READ_INTERVAL_MILLIS = 1_000L

        /**
         * 车速属性 ID，单位 0.01 km/h。
         *
         * B561 v67 改为 public：[VehicleLogTap] 要用它做单位换算，
         * 两处引用同一个常量才能保证"VHAL 读的和日志读的是同一个属性"。
         */
        const val ID_SPEED = 0x2140f4a9

        /** 挡位，取值 21..27。public 理由同 [ID_SPEED]。 */
        const val ID_GEAR = 0x2140f477

        /** 里程。public 理由同 [ID_SPEED]。 */
        const val ID_ODOMETER = 0x2140f478

        /** 电量百分比候选 ID（第一个有值的即命中）。public 理由同 [ID_SPEED]。 */
        val PERCENT_IDS = intArrayOf(
            0x2140f47b, 0x2140f480, 0x2140f4a0, 0x2140f486,
            0x2140f636,
        )

        /** 续航（km）候选 ID。public 理由同 [ID_SPEED]。 */
        val RANGE_IDS = intArrayOf(0x2140f47c, 0x2140f481, 0x2140f4a1, 0x2140f487)

        /**
         * 高压电池电流/电压（miao-uni-z 在同车机 logcat 里抓到的
         * `prop=0x2160f502` / `prop=0x2160f503`）。
         *
         * ★ 注意 `0x2160f5xx` 与我们的 `0x2140f4xx` **不是同一个属性组**——
         *   这两个更可能挂在 `car_ev` / `car_power` 服务上，而只反射
         *   `car_cabin` 的 [CabinAccess] **不**参与这两个 ID 的读取：
         *   服务名确定后再接线，避免读一个永远为 null 的 ID 造成"以为没电"的误判。
         *
         * ★★ B561 v67：它们由 [VehicleLogTap] 这条 logcat 通路覆盖 ——
         *   系统服务自己会打印这些属性的值，广度比反射单个服务大得多。
         *   因此这里把两个 ID 提成 public，供融合器按 ID 去日志里取值。
         *
         * 电压 + 电流可算功率，对 EV 仪表的功率显示有用。
         */
        const val ID_EV_CURRENT = 0x2160f502
        const val ID_EV_VOLTAGE = 0x2160f503
    }
}
