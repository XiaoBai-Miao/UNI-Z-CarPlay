package com.shilapi.xcertplay.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * 监听仪表「飞屏」(flying-screen / screen-cast) 状态，仅在飞屏开启时才允许输出仪表画面。
 *
 * 信号来源（逆向 B561_Dashboard.apk → com.chinatsp.dashboard.model.CarServiceModel）：
 *  - 广播 [ACTION_SCREEN_CAST]，携带 int extra [EXTRA_KEY]：
 *      key == 1 → 飞屏开启（PresentationHelper.showOneDisplayRepeatMedia 把内容投到仪表）
 *      key == 0 → 飞屏关闭（dismissPlayAim 收起）
 *  - 广播 [ACTION_ENTER_INSTRUMENT_NAVIGATION]：进入仪表导航，即飞屏开启。
 *
 * 该监听**只依赖 B561 原生广播**，不依赖 com.thunder.tbxt（CAST_GATE）也不依赖修改版三屏互动 app。
 * 广播由系统/车机发送且 B561_Dashboard 自身无权限参数注册，第三方 App 用运行时注册的 Receiver 即可接收。
 *
 * 状态机：[State.UNKNOWN] 初始（从未收到广播，调用方应保持现有行为）→
 *        收到 [ACTION_SCREEN_CAST] key=1 或 [ACTION_ENTER_INSTRUMENT_NAVIGATION] 变 [State.ACTIVE] →
 *        收到 [ACTION_SCREEN_CAST] key=0 变 [State.INACTIVE]。
 */
internal class ClusterFlyScreenMonitor(
    context: Context,
    private val onState: (State) -> Unit
) {
    enum class State { UNKNOWN, ACTIVE, INACTIVE }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var state = State.UNKNOWN

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                ACTION_SCREEN_CAST -> {
                    val key = intent.getIntExtra(EXTRA_KEY, 0)
                    update(if (key == 1) State.ACTIVE else State.INACTIVE)
                }
                ACTION_ENTER_INSTRUMENT_NAVIGATION -> update(State.ACTIVE)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_SCREEN_CAST)
            addAction(ACTION_ENTER_INSTRUMENT_NAVIGATION)
        }
        appContext.registerReceiver(receiver, filter)
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    /** null = 从未观察到（调用方应保留现有行为，不要据此拦截）。 */
    fun isActive(): Boolean? = when (state) {
        State.ACTIVE -> true
        State.INACTIVE -> false
        State.UNKNOWN -> null
    }

    /** 是否允许输出仪表画面：UNKNOWN 或 ACTIVE 为 true，仅 INACTIVE 拦截。 */
    fun allowsOutput(): Boolean = state != State.INACTIVE

    fun currentState(): State = state

    private fun update(next: State) {
        if (next == state) return
        state = next
        handler.post { onState(next) }
    }

    companion object {
        const val ACTION_SCREEN_CAST = "action_screen_cast"
        const val ACTION_ENTER_INSTRUMENT_NAVIGATION = "com.chinatsp.dashboard.ENTERS_THE_INSTRUMENT_NAVIGATION"
        const val EXTRA_KEY = "key"
    }
}
