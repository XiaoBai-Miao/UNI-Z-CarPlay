package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.CompletableFuture

/**
 * B561 氛围灯客户端 —— 绑定 `com.chinatsp.smartlights.SmartLightsServer` 并驱动灯。
 *
 * 移植自上游 DiPlay 的 `BydAmbientLightClient`（那个走 ADB + stdin worker），
 * 这里换成 B561 原厂 app 暴露的 AIDL service，所以**不需要 ADB、不需要任何额外权限**。
 *
 * ## 为什么用裸 `transact` 而不是 AIDL 生成类
 *
 * `shared` 模块的 `src/main/aidl` 在当前 AGP 配置下不会生成 Java 桩，
 * 而且 AIDL 要求**按声明顺序全量声明 80 个方法**才能保证事务 ID 不错位
 * （漏一个就会整体偏移，把 `setAmbientColor` 调到别人的方法上，很危险）。
 *
 * 所以这里直接用 `IBinder.transact()` 手工构造 Parcel，只声明**用到的 4 个事务**。
 * 事务 ID 与参数顺序均从原厂 `ILightsService` 反编译产物坐实：
 *
 * | 事务 | 方法 |
 * |---|---|
 * | 61 | `setAmbientColor(int)` |
 * | 62 | `setAmbientBraight(int)` |
 * | 17 | `getAmbientColor()` |
 * | 18 | `getAmbientbraight()` |
 *
 * 交叉验证：原厂 PROXY 里就是 `transact(61, …)` + `writeInt(color)` + `readException()`。
 *
 * ## 绑定方式
 *
 * `SmartLightsServer` 是 `android:exported="true"` 但**没有 intent-filter、没有 permission**。
 * 没有 intent-filter 的 exported 组件无法隐式启动（Android 12+ 直接拒绝），
 * 只能 `setClassName()` 显式绑定。
 *
 * ## 前置门槛（实测待确认）
 *
 * 服务端 `setColorValue()/setBrightNess()` 开头都有 `if (mCarType < 2) return;`，
 * 车型值小于 2 时**所有写操作静默无效**。因此 [read] 的只读校验很重要：
 * 读不到有效状态就认为不支持，上层不会启用。
 */
internal class ChinatspAmbientLightClient(private val context: Context) {
    enum class Phase { IDLE, BINDING, READY, FAILED, CLOSED }

    data class Status(val phase: Phase)

    /** 与 [ChinatspAmbientLightPolicy.Snapshot] 同构，供上层无缝替换 BYD 的 State。 */
    data class State(
        val frontColor: Int,
        val backColor: Int,
        val frontBrightness: Int,
        val backBrightness: Int,
        val area: Int,
    )

    private val appContext = context.applicationContext
    private var binder: IBinder? = null
    private var bound = false
    @Volatile private var phase = Phase.IDLE
    private var original: State? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            phase = if (service != null) Phase.READY else Phase.FAILED
            Log.i(TAG, "onServiceConnected ok=${service != null}")
            synchronized(bindLock) { bindLock.notifyAll() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            phase = Phase.FAILED
            Log.w(TAG, "onServiceDisconnected")
            synchronized(bindLock) { bindLock.notifyAll() }
        }
    }

    private val bindLock = Object()

    fun status(): Status = Status(phase)

    private fun bindBlocking(timeoutMillis: Long): IBinder? {
        binder?.let { return it }
        if (bound) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            synchronized(bindLock) {
                while (binder == null && phase != Phase.FAILED &&
                    System.currentTimeMillis() < deadline) {
                    runCatching { bindLock.wait(200) }
                }
            }
            return binder
        }
        val intent = Intent().setClassName(PACKAGE, SERVICE)
        phase = Phase.BINDING
        bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (error: Throwable) {
            Log.w(TAG, "bindService failed (target app missing?)", error)
            phase = Phase.FAILED
            return null
        }
        if (!bound) {
            phase = Phase.FAILED
            Log.w(TAG, "bindService returned false")
            return null
        }
        val deadline = System.currentTimeMillis() + timeoutMillis
        synchronized(bindLock) {
            while (binder == null && phase != Phase.FAILED &&
                System.currentTimeMillis() < deadline) {
                runCatching { bindLock.wait(200) }
            }
        }
        if (binder == null) {
            Log.w(TAG, "bind timeout after ${timeoutMillis}ms")
            phase = Phase.FAILED
            return null
        }
        return binder
    }

    /** 只读检查：能拿到有效灯状态才算支持。绝不写灯、不申请 ADB。 */
    fun read(): CompletableFuture<Boolean> = CompletableFuture.supplyAsync {
        try {
            val target = bindBlocking(BIND_TIMEOUT_MILLIS) ?: return@supplyAsync false
            val snapshot = readSnapshot(target)
            Log.i(TAG, "read snapshot=$snapshot")
            snapshot != null && ChinatspAmbientLightPolicy.validSnapshot(snapshot)
        } catch (error: Throwable) {
            Log.w(TAG, "read failed", error)
            false
        }
    }

    private fun readSnapshot(target: IBinder): ChinatspAmbientLightPolicy.Snapshot? = try {
        val color = transactInt(target, TRANSACTION_GET_COLOR)
        val brightness = transactInt(target, TRANSACTION_GET_BRIGHTNESS)
            .coerceIn(1, ChinatspAmbientLightPolicy.MAX_BRIGHTNESS)
        // 前后分区在 B561 上是同一组值（原厂一次性写 int[6]）。
        ChinatspAmbientLightPolicy.Snapshot(color, color, brightness, brightness, 1)
    } catch (error: Throwable) {
        Log.w(TAG, "readSnapshot failed", error)
        null
    }

    private fun ChinatspAmbientLightPolicy.Snapshot.toState() =
        State(frontColor, backColor, frontBrightness, backBrightness, area)

    private fun State.toSnapshot() =
        ChinatspAmbientLightPolicy.Snapshot(frontColor, backColor, frontBrightness, backBrightness, area)

    /**
     * 开始接管：绑定 + 记录原始颜色/亮度作为恢复快照。
     * 已有 seed（上次未确认的恢复）时优先沿用，避免覆盖更早的真实状态。
     */
    fun begin(seed: State?): CompletableFuture<State> = CompletableFuture.supplyAsync {
        if (seed != null) {
            original = seed
            if (bindBlocking(BIND_TIMEOUT_MILLIS) == null) throw IllegalStateException("bind failed")
            return@supplyAsync seed
        }
        val target = bindBlocking(BIND_TIMEOUT_MILLIS) ?: throw IllegalStateException("bind failed")
        val snapshot = readSnapshot(target) ?: throw IllegalStateException("unreadable lamp state")
        original = snapshot.toState()
        snapshot.toState()
    }

    /** 写亮度与颜色。area 恒 1（B561 无分区概念），保留参数为对齐上层接口。 */
    fun apply(area: Int, color: Int, brightness: Int): CompletableFuture<Void> =
        CompletableFuture.supplyAsync {
            val target = binder ?: throw IllegalStateException("no service")
            if (!ChinatspAmbientLightPolicy.validApply(area, color, brightness)) {
                throw IllegalArgumentException("invalid apply area=$area color=$color brightness=$brightness")
            }
            transactUnit(target, TRANSACTION_SET_BRIGHTNESS) { it.writeInt(brightness) }
            transactUnit(target, TRANSACTION_SET_COLOR) { it.writeInt(color) }
            null
        }

    /** 恢复原始颜色/亮度。best-effort：失败由上层安排重试。 */
    fun stop(): CompletableFuture<Void> = CompletableFuture.supplyAsync {
        val snapshot = original
        val target = binder
        if (snapshot != null && target != null) {
            runCatching {
                transactUnit(target, TRANSACTION_SET_BRIGHTNESS) { it.writeInt(snapshot.frontBrightness) }
                transactUnit(target, TRANSACTION_SET_COLOR) { it.writeInt(snapshot.frontColor) }
                Log.i(TAG, "restored color=${snapshot.frontColor} brightness=${snapshot.frontBrightness}")
            }.getOrThrow()
        }
        null
    }

    fun close() {
        original = null
        phase = Phase.CLOSED
        binder = null
        if (bound) {
            runCatching { appContext.unbindService(connection) }
                .onFailure { Log.w(TAG, "unbindService failed", it) }
            bound = false
        }
        Log.i(TAG, "closed")
    }

    private fun transactInt(target: IBinder, code: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            target.transact(code, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactUnit(target: IBinder, code: Int, payload: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            payload(data)
            target.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val TAG = "DiPlay-AmbientLamp"
        const val PACKAGE = "com.chinatsp.smartlights"
        const val SERVICE = "com.chinatsp.smartlights.SmartLightsServer"
        const val DESCRIPTOR = "com.chinatsp.smartlights.aidl.ILightsService"
        const val BIND_TIMEOUT_MILLIS = 3_000L

        // 事务 ID 从原厂 ILightsService 反编译产物坐实（PROXY 里就是这些值）。
        const val TRANSACTION_GET_COLOR = 17
        const val TRANSACTION_GET_BRIGHTNESS = 18
        const val TRANSACTION_SET_COLOR = 61
        const val TRANSACTION_SET_BRIGHTNESS = 62
    }
}
