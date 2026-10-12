package com.shilapi.xcertplay.cluster

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 第三方仪表投屏通道接口封装（com.thunder.tbxt CAST_GATE，规范 v1）。
 *
 * 控制车机仪表 display[1] 的部分(partial)/全屏(full) 投屏通道。第三方 App 负责在返回的
 * displayId 上自行绘制内容；部分通道由 SuperUni 维护 1 Hz 合成导航心跳，全屏通道不需要。
 *
 * 这是与 B561 原生「飞屏」监听 ([ClusterFlyScreenMonitor]) 完全独立的**另一套车机平台(第三方)**后端，
 * 不依赖修改版三屏互动，也不依赖 B561 原生广播。在 B561 上该接口的目标包不存在，调用为静默无操作。
 *
 * 用法（partial 通道投影导航地图）：
 *   val gate = ClusterCastGate(context); gate.start()
 *   gate.open(ClusterCastGate.PARTIAL, sessionId) { result ->
 *       if (result.status == "active") drawOnDisplay(result.displayId)   // 自行绘制
 *   }
 *   // 结束：
 *   gate.close(ClusterCastGate.PARTIAL, sessionId) { _ -> gate.stop() }
 *
 * 权限（normal 级，免签名匹配、无运行时弹窗，需在 manifest 声明）：
 *   <uses-permission android:name="com.thunder.tbxt.permission.CONTROL_CAST_GATE" />
 */
internal class ClusterCastGate(private val context: Context) {
    fun interface Callback {
        fun onResult(result: Result)
    }

    data class Result(
        val requestId: String?,
        val sessionId: String?,
        val operation: String?,
        val channel: String?,
        val status: String?,
        val errorCode: String?,
        val message: String?,
        val displayId: Int,
        val heartbeatRunning: Boolean,
        val ownedByYou: Boolean
    )

    private val appContext = context.applicationContext
    private val callbacks = ConcurrentHashMap<String, Callback>()
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val reqId = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return
            callbacks[reqId]?.onResult(parse(intent))
        }
    }
    @Volatile private var registered = false

    fun start() {
        if (registered) return
        appContext.registerReceiver(resultReceiver, IntentFilter(RESULT_ACTION))
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(resultReceiver) }
        registered = false
        callbacks.clear()
    }

    fun open(channel: String, sessionId: String, cb: Callback) = send(OPERATION_OPEN, channel, sessionId, cb)
    fun close(channel: String, sessionId: String, cb: Callback) = send(OPERATION_CLOSE, channel, sessionId, cb)
    fun status(channel: String, sessionId: String, cb: Callback) = send(OPERATION_STATUS, channel, sessionId, cb)

    private fun send(operation: String, channel: String, sessionId: String, cb: Callback) {
        val requestId = UUID.randomUUID().toString()
        callbacks[requestId] = cb
        val replyTo = PendingIntent.getBroadcast(
            appContext,
            requestId.hashCode(),
            Intent(RESULT_ACTION).setPackage(appContext.packageName),
            pendingFlag()
        )
        val intent = Intent(ACTION).setPackage(PACKAGE).apply {
            putExtra(EXTRA_API_VERSION, 1)
            putExtra(EXTRA_OPERATION, operation)
            putExtra(EXTRA_CHANNEL, channel)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_REPLY_TO, replyTo)
        }
        appContext.sendBroadcast(intent)
        // 超时清理：若 5s 内无回调，移除挂起的回调（接口为异步，无 ACK 时不阻塞调用方）。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            callbacks.remove(requestId)
        }, 5_000L)
    }

    private fun pendingFlag(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) f = f or PendingIntent.FLAG_MUTABLE
        return f
    }

    private fun parse(intent: Intent): Result = Result(
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID),
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID),
        operation = intent.getStringExtra(EXTRA_OPERATION),
        channel = intent.getStringExtra(EXTRA_CHANNEL),
        status = intent.getStringExtra(EXTRA_STATUS),
        errorCode = intent.getStringExtra(EXTRA_ERROR_CODE),
        message = intent.getStringExtra(EXTRA_MESSAGE),
        displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1),
        heartbeatRunning = intent.getBooleanExtra(EXTRA_HEARTBEAT, false),
        ownedByYou = intent.getBooleanExtra(EXTRA_OWNED, false)
    )

    companion object {
        const val PACKAGE = "com.thunder.tbxt"
        const val ACTION = "com.thunder.tbxt.action.CAST_GATE"
        const val PERMISSION = "com.thunder.tbxt.permission.CONTROL_CAST_GATE"
        const val RESULT_ACTION = "com.shilapi.xcertplay.cluster.CAST_GATE_RESULT"

        const val PARTIAL = "partial"
        const val FULL = "full"

        const val OPERATION_OPEN = "open"
        const val OPERATION_CLOSE = "close"
        const val OPERATION_STATUS = "status"

        const val EXTRA_API_VERSION = "apiVersion"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_REPLY_TO = "replyTo"

        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR_CODE = "errorCode"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DISPLAY_ID = "displayId"
        const val EXTRA_HEARTBEAT = "heartbeatRunning"
        const val EXTRA_OWNED = "ownedByYou"
    }
}
