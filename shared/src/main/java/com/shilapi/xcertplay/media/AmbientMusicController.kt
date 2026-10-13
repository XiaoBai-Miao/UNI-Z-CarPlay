package com.shilapi.xcertplay.media

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.shilapi.xcertplay.hud.ChinatspAmbientLightClient
import com.shilapi.xcertplay.hud.ChinatspAmbientLightPolicy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sqrt

/**
 * B561 氛围灯「音乐律动」控制器。
 *
 * 移植自上游 DiPlay 的 `AmbientMusicController`（`docs/BYD_AMBIENT_LIGHTING.md`），
 * 保留了它的骨架与全部安全设计：
 *
 * - 单线程调度器拥有所有灯的 I/O，音频写线程只追加**能量元数据**（不存音频）。
 * - 能量来自媒体声道**成功写入的 PCM**（[AmbientMusicEnvelope.pcmRms]），
 *   并用 `AudioTrack` 播放位置对齐，只统计真正播出去的那部分。
 * - 导航/通话声道**不供能量**（语音不该让灯乱闪）。
 * - 关闭/暂停/断开时把灯恢复到接管前的快照；恢复是 best-effort，
 *   失败按 5s/10s/20s/30s 退避重试，未确认的恢复会保留更早的快照。
 *
 * 与上游的差异只在**灯通道与颜色模型**：
 * - 通道：上游走 ADB + BYD lamp worker，这里走 `com.chinatsp.smartlights` 的 AIDL
 *   （见 [ChinatspAmbientLightClient]）。
 * - 颜色：上游是 BYD 的 1..31 OEM 编号，B561 收 **ARGB int**，所以这里用 HSV 直接算。
 */
object AmbientMusicController {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carplay-ambient-music").apply { isDaemon = true }
    }
    private val sequence = AtomicLong()
    private val activeSink = AtomicLong()
    private val activeRenderer = AtomicLong()
    private val phonePlayback = AmbientPhonePlaybackState()
    @Volatile private var settings = AmbientMusicSettings.Values()
    private var context: Context? = null
    private var envelope: AmbientMusicEnvelope? = null
    private var playback: (() -> Pair<Int, Boolean>)? = null
    private var allowed = false
    private var appliedCount = 0L
    private var lastLogMillis = 0L

    // 亮度平滑状态（一阶低通 + 峰值归一化）
    private var smoothedLevel = 1.0
    private var peakEnergy = 0.05
    private var lastLevelMillis = 0L

    private val lampSession = AmbientLampSession(
        factory = {
            val real = ChinatspAmbientLightClient(requireNotNull(context))
            object : AmbientLampSession.Client {
                override fun unhealthy(): Boolean {
                    val phase = real.status().phase
                    return phase == ChinatspAmbientLightClient.Phase.FAILED ||
                        phase == ChinatspAmbientLightClient.Phase.CLOSED
                }
                override fun begin(seed: ChinatspAmbientLightPolicy.Snapshot?) =
                    real.begin(seed?.let {
                        ChinatspAmbientLightClient.State(
                            it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area,
                        )
                    }).thenApply {
                        ChinatspAmbientLightPolicy.Snapshot(
                            it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area,
                        )
                    }
                override fun apply(desired: Triple<Int, Int, Int>) =
                    real.apply(desired.first, desired.second, desired.third).thenApply { Unit }
                override fun stop() = real.stop().thenApply { Unit }
                override fun close() = real.close()
            }
        },
        dispatch = { executor.execute(it) },
        now = { android.os.SystemClock.uptimeMillis() },
        log = { Log.i("DiPlay-Ambient", it) },
        applied = { desired ->
            appliedCount++
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLogMillis >= 5_000) {
                lastLogMillis = now
                Log.i("DiPlay-Ambient", "applied=$appliedCount color=${desired.second} brightness=${desired.third} hueCycle=${settings.hueCycle}")
            }
        },
    )

    /** 20 Hz 灯更新，仅在功能开启时调度；关掉会自行恢复原灯。 */
    private var ticking: ScheduledFuture<*>? = null

    private fun updateTicking() {
        if (!settings.enabled) {
            ticking?.cancel(false)
            ticking = null
        } else if (ticking == null) {
            ticking = executor.scheduleWithFixedDelay({ runCatching { tick() }.onFailure {
                Log.w("DiPlay-Ambient", "lamp update failed", it)
                lampSession.recover()
            } }, 50, 50, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized fun openSink(context: Context?): Long {
        if (context == null) return 0L
        val token = sequence.incrementAndGet()
        activeSink.set(token)
        activeRenderer.set(0L)
        executor.execute {
            if (activeSink.get() != token) return@execute
            this.context = context.applicationContext
            settings = AmbientMusicSettings.load(context)
            allowed = settings.enabled; envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
            updateTicking()
        }
        return token
    }

    @Synchronized internal fun openRenderer(
        sink: Long,
        envelope: AmbientMusicEnvelope,
        playback: () -> Pair<Int, Boolean>,
    ): Long {
        if (sink == 0L || sink != activeSink.get()) return 0L
        val token = sequence.incrementAndGet()
        activeRenderer.set(token)
        executor.execute {
            if (activeSink.get() != sink || activeRenderer.get() != token) return@execute
            allowed = true
            this.envelope = envelope
            this.playback = playback
        }
        return token
    }

    internal fun wantsPcm(token: Long): Boolean =
        token != 0L && token == activeRenderer.get() && settings.enabled && settings.music

    /** 把手机播放回调绑到当前 CarPlay 媒体属主，避免被替换掉的旧会话回调重启灯光。 */
    @Synchronized fun claimPlaybackOwner(owner: Any) = phonePlayback.claim(owner)

    fun phonePlaybackChanged(owner: Any, playing: Boolean): Boolean = phonePlayback.update(owner, playing)

    @Synchronized fun releasePlaybackOwner(owner: Any): Boolean = phonePlayback.release(owner)

    /** 只读校验：能读到有效灯状态才算支持。绝不写灯、不申请 ADB。 */
    fun checkSupport(context: Context): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        executor.execute {
            if (lampSession.isReady()) {
                result.complete(true)
                return@execute
            }
            val client = ChinatspAmbientLightClient(context.applicationContext)
            try {
                client.read().handle { supported, error ->
                    error == null && supported == true
                }.whenComplete { supported, error ->
                    result.complete(error == null && supported == true)
                }
            } finally {
                // read 结束就解绑，把进程锁留给后续真正接管的会话。
                executor.execute { client.close() }
            }
        }
        return result
    }

    fun settingsChanged(context: Context) {
        executor.execute {
            this.context = context.applicationContext
            settings = AmbientMusicSettings.load(context)
            smoothedLevel = 1.0; peakEnergy = 0.05; lastLevelMillis = 0L
            allowed = settings.enabled
            if (!settings.enabled) restoreOriginal()
            updateTicking()
        }
    }

    @Synchronized internal fun closeRenderer(token: Long) {
        if (token == 0L || !activeRenderer.compareAndSet(token, 0L)) return
        executor.execute {
            if (activeRenderer.get() != 0L) return@execute
            allowed = settings.enabled
            envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
        }
    }

    @Synchronized fun closeSink(token: Long) {
        if (token == 0L || !activeSink.compareAndSet(token, 0L)) return
        activeRenderer.set(0L)
        executor.execute {
            if (activeSink.get() != 0L) return@execute
            allowed = settings.enabled
            envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
        }
    }

    private fun tick() {
        val values = settings
        if (!allowed) return
        if (!values.enabled) { restoreOriginal(); return }
        if (context == null) return
        lampSession.setEnabled(true)
        val rendererActive = activeRenderer.get() != 0L && playback != null
        val playbackState = if (rendererActive && values.brightness > 0) playback?.invoke() else null
        val target = AmbientMusicActivityPolicy.target(
            enabled = values.enabled,
            music = values.music,
            brightness = values.brightness,
            rendererActive = rendererActive,
            playing = playbackState?.second == true && phonePlayback.current() != false,
        )
        if (target == AmbientMusicTarget.RESTORE_OEM) { restoreOriginal(); return }
        val energy = if (target == AmbientMusicTarget.FOLLOW_PLAYBACK) {
            val (head, playing) = playbackState ?: return
            envelope?.playedWindow(head, playing) ?: AmbientMusicEnvelope.Energy(0.0, 0.0)
        } else AmbientMusicEnvelope.Energy(0.0, 0.0)
        val rms = energy.rms
        val nowMillis = android.os.SystemClock.uptimeMillis()
        val brightness = when (target) {
            AmbientMusicTarget.LOWEST -> 1
            AmbientMusicTarget.FOLLOW_PLAYBACK -> brightnessLevel(rms, nowMillis, values.brightness, values.speed)
            else -> values.brightness.coerceIn(1, com.shilapi.xcertplay.hud.ChinatspAmbientLightPolicy.MAX_BRIGHTNESS)
        }
        val color = when {
            target != AmbientMusicTarget.FOLLOW_PLAYBACK || !values.hueCycle ->
                hueColor(values.baseHue, values.saturation)
            else -> hueColor(hueFor(rms, values), values.saturation)
        }
        // B561 无分区概念，area 恒 1。
        lampSession.update(Triple(1, color, brightness))
    }

    /** 峰值归一化 + 一阶低通；speed 只改 attack/release 时间常数。 */
    private fun brightnessLevel(rms: Double, nowMillis: Long, ceiling: Int, speed: Int): Int {
        val energy = if (rms.isFinite()) rms.coerceIn(0.0, 1.0) else 0.0
        val elapsed = if (lastLevelMillis == 0L) 0 else (nowMillis - lastLevelMillis).coerceAtLeast(0)
        lastLevelMillis = nowMillis
        peakEnergy = maxOf(0.05, energy, peakEnergy * exp(-elapsed / 3_000.0))
        val normalized = (energy / peakEnergy).coerceIn(0.0, 1.0)
        val target = if (energy < 0.008) 1.0 else 1.0 + sqrt(normalized) * (ceiling - 1)
        val (attack, release) = when (speed) {
            0 -> 120.0 to 450.0
            2 -> 0.0 to 70.0
            else -> 45.0 to 220.0
        }
        val tau = if (target > smoothedLevel) attack else release
        smoothedLevel = if (elapsed <= 0 || tau == 0.0) target
        else smoothedLevel + (target - smoothedLevel) * (1 - exp(-elapsed / tau))
        smoothedLevel = smoothedLevel.coerceIn(1.0, ceiling.toDouble())
        return round(smoothedLevel).toInt().coerceIn(1, ceiling)
    }

    /** 能量越高，色相在 [baseHue, baseHue + hueSpan] 内越靠后。 */
    private fun hueFor(rms: Double, values: AmbientMusicSettings.Values): Int {
        val normalized = if (rms.isFinite()) rms.coerceIn(0.0, 1.0) else 0.0
        return (values.baseHue + normalized * values.hueSpan).toInt() % 360
    }

    private fun hueColor(hue: Int, saturation: Int): Int =
        Color.HSVToColor(floatArrayOf(((hue % 360) + 360) % 360f, saturation / 100f, 1f))

    private fun restoreOriginal() { lampSession.setEnabled(false) }
}
