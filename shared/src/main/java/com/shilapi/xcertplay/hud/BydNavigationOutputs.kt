package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Nonblocking boundary between phone control messages and vendor services. */
object BydNavigationOutputs {
    /** Recover a journaled interrupted output when the app opens, even before a phone reconnects. */
    fun onAppOpened(context: Context) {
        BydOemClusterNavi.restoreIfNeeded(context)
        BydDiLink3ClusterOutput.restoreIfNeeded(context)
        com.shilapi.xcertplay.network.WifiScanPause.restoreIfNeeded(context)
        if (BydStandaloneHudOutput.available(context)) start(context)
        // Read the battery early, so a reading is ready when CarPlay identifies (see batteryStatus).
        if (BydOutputSettings.batteryToIphoneActive(context)) BydBatteryStatus.start(context)
        // DiLink 3 creates its cluster map display only once the cluster has projected.
        if (BydOutputSettings.enabled(context)) BydClusterBridge.prepareProjectionDisplay(context.applicationContext)
    }
    fun setDiagnosticHold(hold: Boolean) { BydStandaloneHudOutput.syntheticHold = hold }
    @Volatile private var useStandalone = false
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedOverlay: ClusterTurnGuidance? = null

    /**
     * B561: 转向卡滞回器，状态由 [overlayLock] 保护（它内部不做同步）。
     *
     * 见 [ClusterGuidanceStabilizer] —— iOS 每米发一次 0x5201，
     * 没有滞回时仪表箭头会持续微抖。
     */
    private val overlayStabilizer = ClusterGuidanceStabilizer()

    /**
     * B561: 车道指引的独立通知。
     *
     * 与 [setTurnOverlayListener] 分开是因为**刷新节奏不同**：车道组在一次
     * 转向里会换好几次，而转向本身不变 —— 合成一个对象每次都重发，
     * 会让转向卡白白重画。
     */
    @Volatile private var laneListener: ((ClusterLaneGuidance?) -> Unit)? = null
    private val laneLock = Any()
    private var publishedLanes: ClusterLaneGuidance? = null
    private val overlayRoute = BydHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
        keepAcrossNoRoute = true,
    )
    private val standalone = NavigationOutputWorker("diplay-standalone-output", BydStandaloneNavigationBridge::clear)
    private val hud = NavigationOutputWorker("diplay-hud-output", BydHudBridge::clear)
    private val cluster = NavigationOutputWorker("diplay-cluster-output", BydClusterBridge::clear)

    /** The host reports whether its CarPlay map window is on the cluster (see [BydClusterMapPause]). */
    fun setClusterMapShown(shown: Boolean) {
        BydClusterMapPause.clusterMapShown = shown
        BydClusterBridge.setMapShown(shown)
    }

    /** The running CarPlay session: told every second whether the cluster currently shows the map. */
    fun setClusterStreamControl(control: (Boolean) -> Unit) { BydClusterMapPause.streamControl = control }

    fun clearClusterStreamControl(control: (Boolean) -> Unit) {
        if (BydClusterMapPause.streamControl == control) BydClusterMapPause.streamControl = null
    }

    /**
     * The car's battery for the iPhone's vehicle status; starts reading it over adb. The electric
     * vehicle is declared only once a reading is there (see withVehicleStatusFrom).
     */
    fun batteryStatus(context: Context): com.shilapi.xcertplay.transport.VehicleStatusProvider =
        BydBatteryStatus.also { it.start(context) }

    /** The car's wheel speed and gear for the iPhone's dead reckoning; read over adb while asked for. */
    fun wheelSpeed(context: Context): com.shilapi.xcertplay.transport.VehicleSpeedSource =
        BydWheelSpeedSource.attach(context)
    /** Whether the car is in P (read over adb), or null when it cannot tell. Blocking. */
    fun parked(context: Context): Boolean? = BydParkedState.parked(context.applicationContext)

    fun start(context: Context) {
        val app = context.applicationContext

        // ★ B561: 非比亚迪车机根本不该启动 BYD HUD / 仪表桥。
        //
        // 症状：实车日志里每 300ms 一条
        //   `DiPlay-BYD-HUD: bindService=false`
        // B561 没有比亚迪 HUD 服务，所以永远 false，但桥一直在重试、一直打日志。
        // 不影响功能（bindService 失败是静默的），但会**持续污染日志** ——
        // 实测 92579 行里有 412 行是它，而找仪表问题时这些噪音很碍事。
        //
        // 判据用 [BydOutputSettings.available]：按"车机上是否真有比亚迪的东西"
        // （BYD 应用包 / Build 指纹含 BYD）判断，而不是硬编码车型。
        //
        // ★ **只拦桥、不拦协议解析** —— 下面 `attach()` 的几个是 iAP2 帧解析器，
        //   与车机无关：B561 的通话卡要靠 [BydCarPlayCall] 拿 CallStateUpdate(0x4155)、
        //   媒体卡靠 [BydClusterSong] 拿 NowPlayingUpdate(0x5001)。
        //   早先写成 `if (!available) return` 会把这两个也砍掉，卡片永远没数据。
        //   （这个坑踩过一次：先写了整段 return，改接线时才发现通话卡不亮。）
        attachOverlaySources(app)

        if (!BydOutputSettings.available(app)) return

        useStandalone = BydStandaloneHudOutput.available(app)
        if (useStandalone) standalone.start { BydStandaloneNavigationBridge.initialize(app) }
        else {
            hud.start { BydHudBridge.initialize(app) }
            cluster.start { BydClusterBridge.initialize(app) }
        }
        BydClusterMapPause.initialize(app)
    }

    /**
     * 挂上**与车机无关**的 iAP2 帧解析器。
     *
     * 通话卡与媒体卡的数据都来自 iPhone 经 iAP2 推来的帧，跟车机是不是比亚迪
     * 毫无关系 —— 不能跟着 HUD 桥一起被"非比亚迪就不启动"优化掉。
     */
    private fun attachOverlaySources(app: Context) {
        BydClusterSong.attach(app)
        BydCarPlayCall.attach(app)
    }

    internal fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == ClusterSongState.NOW_PLAYING_UPDATE) {
            BydClusterSong.onFrame(frame)
            return
        }
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
            BydCarPlayCall.onFrame(frame)
            return
        }
        // B561: 0x5204 车道指引也要进 overlay 状态机 —— 它是画"路口放大图"的数据源。
        //   它不属于比亚迪 HUD/仪表桥，但 iAP2 帧解析与车机无关，所以在
        //   `available()` 判断**之前**处理（与 0x5001/0x4155 同理）。
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE &&
            frame.messageId != ClusterLaneGuidance.ROUTE_GUIDANCE_LANE_INFO) return
        val owned = frame // Iap2Frame is immutable and defensively copies its payload.
        updateOverlay(owned)
        // 下面的 worker 在没 start() 时 submit 是 no-op（见 NavigationOutputWorker），
        // 所以非比亚迪车机上这几行不产生任何日志噪音 —— 与原逻辑一致，无需额外判据。
        if (useStandalone) standalone.submit { BydStandaloneNavigationBridge.onFrame(owned) }
        else {
            hud.submit { BydHudBridge.onFrame(owned) }
            cluster.submit { BydClusterBridge.onFrame(owned) }
        }
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) {
        overlayListener = listener
        val next = synchronized(overlayLock) {
            // ★ B561: 换监听者时重置稳定器，并让新监听者**立即**拿到当前状态。
            //   presentation 重建时走的就是这条路径 —— 不重置的话，
            //   新卡片会拿滞回器的历史值去比对，箭头要等两次 tick 才出现。
            //   （refreshTurnOverlay 里死区内会返回 null，所以这里直接取原始值。）
            overlayStabilizer.reset()
            val current = rawOverlay()
            if (current != null) {
                overlayStabilizer.accept(current)
                publishedOverlay = current
            } else {
                publishedOverlay = null
            }
            current
        }
        listener?.invoke(next)
    }

    private fun updateOverlay(frame: Iap2Frame) {
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        if (change != BydHudRouteChange.NONE) refreshTurnOverlay()
    }

    /** B561: 车道指引有变就通知 —— 与转向分开，见 [laneListener]。 */
    private fun refreshLaneOverlay() {
        val next = synchronized(laneLock) {
            val current = overlayRoute.currentLaneGuidance()
            if (current == publishedLanes) return
            publishedLanes = current
            current
        }
        laneListener?.invoke(next)
    }

    /**
     * B561: 注册车道指引监听（画"路口放大图"用）。
     *
     * 调用时立即推一次当前值 —— 与 [setTurnOverlayListener] 同样的理由：
     * 注册通常发生在有路线之后，漏掉首帧就等于没数据。
     */
    fun setLaneGuidanceListener(listener: ((ClusterLaneGuidance?) -> Unit)?) {
        laneListener = listener
        val next = overlayRoute.currentLaneGuidance()
        synchronized(laneLock) { publishedLanes = next }
        listener?.invoke(next)
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        val next = synchronized(overlayLock) {
            val raw = rawOverlay()
            if (raw == null) {
                // ★ 收卡路径必须**绕过**滞回。
                //   滞回的返回 `null` 表示"这一帧不用动"，而这里 `raw == null`
                //   表示"要把卡收掉" —— 两者都null，含义完全相反。
                //   若混在一起，导航结束/stale 超时时卡片会永远留在仪表上
                //   （实测：explicitStop 之后卡片不消失）。
                if (publishedOverlay == null) return
                overlayStabilizer.reset()
                publishedOverlay = null
                null
            } else {
                // B561: 滞回在这里生效 —— iOS 每米发一次 0x5201，
                //   原本的 data class 相等比较会让仪表箭头每米抖一次。
                //   转向变了立即换、只有距离变则走相对死区 + 两段式提交。
                val stable = overlayStabilizer.accept(raw) ?: return
                if (stable == publishedOverlay) return
                publishedOverlay = stable
                stable
            }
        }
        overlayListener?.invoke(next)
        // 车道数据的过期与转向同一个 stale 窗口，所以每 tick 一起收 ——
        // 只靠 0x5201/0x5204 新帧的话，"导航结束但没发新帧"时会留着旧箭头。
        refreshLaneOverlay()
    }

    private fun rawOverlay(): ClusterTurnGuidance? = overlayRoute.currentApple()?.let { apple ->
        ClusterTurnGuidance.from(BydClusterFrame.from(apple)).copy(
            arrivalEpochSeconds = apple.arrivalEpochSeconds,
            remainingSeconds = apple.remainingSeconds,
            remainingMeters = apple.remainingMeters,
        )
    }

    /** The dashboard song setting changed; applies at once. */
    fun clusterSongChanged(enabled: Boolean) = BydClusterSong.settingChanged(enabled)

    /** The CarPlay call setting changed; applies at once. */
    fun carPlayCallsChanged(enabled: Boolean) = BydCarPlayCall.settingChanged(enabled)

    /** A CarPlay session became active; ready the call card so calls show without the watcher's start delay. */
    fun carPlaySessionStarted() = BydCarPlayCall.sessionStarted()

    /** The iPhone's current call, for the steering wheel's call keys. */
    fun carPlayCall(): CarPlayCallCard? = BydCarPlayCall.current()

    /** The dashboard song's "only when it changes" setting changed; applies at once. */
    fun clusterSongOnChangeChanged() = BydClusterSong.onChangeSettingChanged()

    /** A short note where the song shows on the dashboard; needs the same ADB access as the song. */
    fun dashboardNote(text: String, source: Int? = null) = BydClusterSong.note(text, source)

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow(preserveTurnOverlay: Boolean = false) {
        standalone.clear(); hud.clear(); cluster.clear(); BydClusterSong.end(); BydCarPlayCall.end()
        // Only a wireless session replacement retains the card. Explicit controller close
        // and wired disconnect still clear it immediately.
        if (!preserveTurnOverlay) {
            // ★ B561: 稳定器必须一起重置。保留卡片的场景（preserveTurnOverlay）下
            //   它要继续按旧状态判定；清除卡片的场景下若留着旧 accepted，
            //   下一条新转向的第一帧会先走"距离死区"判定而被吃掉 ——
            //   表现为"重连后要等两秒才出现箭头"。
            synchronized(overlayLock) {
                overlayRoute.clear()
                overlayStabilizer.reset()
            }
            refreshTurnOverlay()
        }
        // 车道数据一定清：它是"下一个路口长什么样"的快照，
        // 跨会话保留一定会画到已经走过的路口上。
        synchronized(laneLock) { publishedLanes = null }
        laneListener?.invoke(null)
    }
}
