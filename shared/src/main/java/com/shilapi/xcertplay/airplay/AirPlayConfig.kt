package com.shilapi.xcertplay.airplay

/** Display insets in pixels, used for CarPlay viewArea and safeArea declarations. */
data class AirPlayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/** One display advertised to the phone in /info. */
data class AirPlayDisplayConfig(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val fps: Int = 60,
    val primaryInputDevice: Int = 1,
    val viewArea: AirPlayInsets? = null,
    val safeArea: AirPlayInsets? = null,
    val safeAreaDrawOutside: Boolean? = null,
    val initialUrl: String? = null,
    /** Display feature bits; null keeps the main-screen default (high-fidelity touch and knobs). */
    val features: Int? = null,
    /**
     * The view areas CarPlay may use on this display, or null for the whole display as one area. With
     * several, the car moves CarPlay between them with updateViewArea, without reconnecting.
     */
    val viewAreas: List<AirPlayViewArea>? = null,
    /** The area CarPlay starts in, an index into [viewAreas]. */
    val initialViewArea: Int = 0,
)

/**
 * A rectangle of the display's stream that CarPlay can draw in, and optionally the edge for its dock
 * ([AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE] or [AirPlayInfoPlist.DOCK_EDGE_BOTTOM]; null leaves it to
 * the iPhone).
 */
data class AirPlayViewArea(
    val width: Int,
    val height: Int,
    val originX: Int = 0,
    val originY: Int = 0,
    val dockEdge: Int? = null,
)

/** One OEM homescreen icon. */
data class AirPlayIcon(
    val widthPixels: Int,
    val heightPixels: Int,
    val data: ByteArray,
)

/** Immutable accessory configuration consumed by the AirPlay session server. */
data class AirPlayConfig(
    val deviceName: String,
    val deviceId: String,
    val btMac: String,
    val sourceVersion: String,
    val main: AirPlayDisplayConfig,
    val cluster: AirPlayDisplayConfig? = null,
    val rightHandDrive: Boolean = false,
    val port: Int = 7000,
    val entertainmentSampleRate: Int = 48000,
    val hevc: Boolean = false,
    val disableAudioOutput: Boolean = false,
    val microphone: Boolean = false,
    val manufacturer: String = "xcertplay",
    val model: String = "xcertplay",
    val oemLabel: String = "xcertplay",
    val icons: List<AirPlayIcon> = emptyList(),
    /** iOS 27 video in car (see [VideoInCar]); video plays only while [VideoInCar.allowed]. */
    val videoInCar: Boolean = false,
    /**
     * CarPlay's main buffered audio (see [BufferedAudioStream]): apps that support it send music
     * ahead of time over TCP, so short Wi-Fi gaps do not interrupt it.
     */
    val mainBufferedAudio: Boolean = false,
    /**
     * ★ v80: 在 `/info` 顶层下发 `disableBluetooth`（**实验性，默认关**）。
     *
     * 逆向商业接收端 `com.autochips.carplayapp`（含 Apple AirPlay Receiver SDK）
     * 得到：那个APK 连接成功后**会断开手机蓝牙**，其 plist 键表里
     * `disableBluetooth` 与 `telephony` / `speechRecognition` / `media` 同层级，
     * 配套日志为 `AirPlay Disable Bluetooth session control request`。
     *
     * 推测机制：告诉 iPhone「蓝牙会话控制由接收端接管」，让**iPhone 主动断**，
     * 而不是在车机侧强行 `disconnect()` profile —— 后者会与 iAP2 抢链路控制权，
     * 且我们的 v76~v78 就是因为断错 profile 而静默无效。
     *
     * ⚠️ **未能确认它是否真是发给 iPhone 的协议键**，也可能是 SDK 内部 RIMP
     *   配置项名。加错的后果：iPhone 忽略未知键（无害），
     *   或形状校验失败导致**连不上车**（所以必须可一键关）。
     *
     * 与 [disableAudioOutput] 的区别：后者删掉整个 `audioFormats` 与音频 feature 位，
     * CarPlay 会完全无声（MG4 就这么放弃了音频）；本键粒度更细。
     */
    val disableBluetooth: Boolean = false,
    /**
     * ★ v80: 在主流（type 110）的 viewArea 里宣告 `viewAreaSupportsFocusTransfer`。
     *
     * ⚠️ 注意它管的是「焦点在 CarPlay 界面与**原生界面**间移动」（导航播报时高亮
     *   CarPlay 图标那种 UI 层焦点），**与音频焦点无关，更不是断开手机蓝牙**。
     *   逆向那个商业接收端时确认它用的是 Apple SDK 的
     *   `AirPlayReceiverSessionAccessoryAcquireFocus` + `HasFeatureFocusTransfer`，
     *   那个接口我们**抄不到**（没有那个二进制库）。
     *
     *   真机抓包还显示 `focusTransfer` 在协商中常被协商掉
     *   （2026-08-02 实测 14 个 key 只 6 个存活）—— 宣告 ≠ 生效。
     *
     * 顶层 features 里的 focus transfer 位**查不到权威数值**（`0x615653aee2`
     * 是口口相传的魔数，GitHub 33+ 处引用零处带位注释，各家宣告的位集都不一样），
     * 所以只填 viewArea 层这个协议字段，不去猜 features 位。
     */
    val viewAreaFocusTransfer: Boolean = false,
)

/** The offer, SETUP and controls must all honor the user's audio-output setting. */
internal val AirPlayConfig.bufferedAudioOutputEnabled: Boolean
    get() = mainBufferedAudio && !disableAudioOutput
