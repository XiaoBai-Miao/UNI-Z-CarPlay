package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.SafeAreaCodec
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.File

/** SharedPreferences persistence for the accessory identity and paired controllers. */
object AirPlayPersistence {
    /** 0 uses usage-based routing; 1–20 select stream types supported by the head unit. */
    val AUDIO_CHANNELS = 0..20
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_IDENT_PRIVATE = "identity_private"
    private const val KEY_IDENT_PUBLIC = "identity_public"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PAIRING_IDS = "pairing_ids"
    private const val KEY_LOCKDOWN_HOST_ID = "lockdown_host_id"
    private const val KEY_LOCKDOWN_SYSTEM_BUID = "lockdown_system_buid"
    private const val KEY_LOCKDOWN_WIFI_MAC = "lockdown_wifi_mac"
    private const val KEY_LOCKDOWN_DEVICE_PUBLIC = "lockdown_device_public"
    private const val KEY_LOCKDOWN_DEVICE_CERT = "lockdown_device_cert"
    private const val KEY_LOCKDOWN_HOST_PRIVATE = "lockdown_host_private"
    private const val KEY_LOCKDOWN_HOST_CERT = "lockdown_host_cert"
    private const val KEY_LOCKDOWN_ROOT_PRIVATE = "lockdown_root_private"
    private const val KEY_LOCKDOWN_ROOT_CERT = "lockdown_root_cert"
    private const val KEY_DISPLAY_SCALE_TENTHS = "display_scale_tenths"
    private const val KEY_UI_SCALE_PERCENT = "ui_scale_percent"
    private const val KEY_HEVC_ENABLED = "hevc_enabled"
    private const val KEY_HEVC_SOFTWARE_DECODER = "hevc_software_decoder"
    private const val KEY_ADVANCED_AUDIO_CHANNEL_MAPPING = "advanced_audio_channel_mapping"
    private const val KEY_AUDIO_FOCUS_ENABLED = "audio_focus_enabled"
    private const val KEY_AUDIO_FOCUS_AUTO_YIELD = "audio_focus_auto_yield"
    /** ★ v80: viewArea 宣告焦点转移支持（协议字段，实验性，默认关）。 */
    private const val KEY_VIEW_AREA_FOCUS_TRANSFER = "view_area_focus_transfer"
    /** ★ v80: /info 下发 disableBluetooth（逆向商业方案得到，未验证，默认关）。 */
    private const val KEY_DISABLE_BLUETOOTH_PLIST = "disable_bluetooth_plist"
    /** ★ v80: 音频路由诊断（isSpeakerphoneOn 等只读快照，默认开）。 */
    private const val KEY_AUDIO_ROUTE_DIAGNOSTICS = "audio_route_diagnostics"
    private const val KEY_MEDIA_AUDIO_CHANNEL = "media_audio_channel"
    private const val KEY_NAVIGATION_AUDIO_CHANNEL = "navigation_audio_channel"
    private const val KEY_NAVIGATION_STREAM_TYPE = "navigation_stream_type"

    /** B561: `default` 流（导航语音＋UI 提示音共流）的去向策略。 */
    private const val KEY_NAVIGATION_VOICE_SCOPE = "navigation_voice_scope"

    /** B561: UI 提示音（打字/点击咔哒声）是否走系统声道而不是导航专声区。 */
    private const val KEY_SYSTEM_AUDIO_ROUTING = "system_audio_routing"
    private const val KEY_WIRELESS_ENABLED = "wireless_enabled"
    private const val KEY_WIRELESS_HOTSPOT_MODE = "wireless_hotspot_mode"
    private const val KEY_WIFI_P2P_PREFERRED_CHANNEL = "wifi_p2p_preferred_channel"
    private const val KEY_MANUAL_HOTSPOT_SSID = "manual_hotspot_ssid"
    private const val KEY_MANUAL_HOTSPOT_PASSPHRASE = "manual_hotspot_passphrase"
    private const val KEY_MANUAL_HOTSPOT_BAND = "manual_hotspot_band"
    private const val KEY_MANUAL_HOTSPOT_CHANNEL = "manual_hotspot_channel"
    private const val KEY_MANUAL_HOTSPOT_SECURITY = "manual_hotspot_security"
    private const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_MODEL = "model"
    private const val KEY_OEM_LABEL = "oem_label"
    private const val KEY_CARPLAY_NIGHT_MODE = "carplay_night_mode"
    private const val KEY_CARPLAY_NIGHT_START = "carplay_night_start_minute"
    private const val KEY_CARPLAY_NIGHT_END = "carplay_night_end_minute"
    private const val KEY_AMBIENT_LUX_THRESHOLD = "ambient_lux_threshold"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MEDIA_BUFFER_MS = "media_buffer_ms"
    private const val KEY_MAIN_BUFFERED_AUDIO = "main_buffered_audio"
    private const val KEY_CALL_ECHO_CANCELLATION = "call_echo_cancellation"
    private const val KEY_CALL_VOICE_FILTER = "call_voice_filter"
    private const val KEY_DISCONNECT_A2DP = "disconnect_a2dp"

    /** v73: /info 声明"无音频输出"，见 [loadDisableAudioOutput]。 */
    private const val KEY_DISABLE_AUDIO_OUTPUT = "disable_audio_output"

    /** v76: 蓝牙断开范围，见 [BluetoothScope]。 */
    private const val KEY_BLUETOOTH_SCOPE = "bluetooth_scope"

    /**
     * ★ v78: 是否**不使用**车机麦克风。
     *
     * 默认 false = 用车机麦克风（与改动前行为一致，不破坏现有用户）。
     *
     * [AirPlayConfig.microphone] 上报 false 时，`/info` 的 `audioFormats` 不带
     * `audioInputFormats`（见 `AirPlayInfoPlist.audioFormats`），iPhone 就不会把
     * 语音输入的采集请求交给车机 —— 反过来可用于现场对比验证。
     */
    private const val KEY_DISABLE_MICROPHONE = "disable_microphone"

    /**
     * ★ v76: 会话建立后断开手机蓝牙的范围。
     *
     * | 值 | 含义 | 适合|
     * |---|---|---|
     * | [OFF] | 不断（默认） | 蓝牙链路正常、只想留CarPlay 音频 |
     * | [AUDIO] | 断 A2DP（音乐） | 手机蓝牙音乐与导航双焦点 |
     * | [FULL] | 断 A2DP + HEADSET（通话） | 手机铃声/通话仍从车机出，双焦点最严重 |
     * | [ALL] | 断**所有**已建立链路 | 要「手机与车机蓝牙彻底断开」的体感 |
     *
     * ## [ALL] 与 [FULL] 的区别（用户要的「彻底断开」）
     *
     * [FULL] 只按名单断 A2DP + HEADSET，名单外的 profile（AVRCP、厂商私有等）
     * 会留着。而 [ALL] 不问 profile 名单，只问「现在有哪些设备连着」，
     * 对每台调 `BluetoothDevice.disconnect()` 断其全部链路 ——
     * 不受 profile id 的 ROM 差异影响。
     *
     * ## 三者都**不动**适配器与配对关系
     *
     * `BluetoothAdapter.disable()` 会连 iAP2 配对与重连一起断，车机拉不起会话；
     * `removeBond()` 会让手机「忘记」这台车机。所以两样都不做 ——
     * 只断已建立的连接，配对保留，下次 CarPlay 仍能自动重连。
     *
     * ## 为什么 [FULL]/[ALL] 不是推荐档
     *
     * 断 HEADSET 后手机的**来电**在车机上就没声音了（只剩 CarPlay 自己的通话）。
     * 只在确实被双焦点困扰时才用。
     */
    enum class BluetoothScope { OFF, AUDIO, FULL, ALL }

    fun loadBluetoothScope(context: Context): BluetoothScope {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BLUETOOTH_SCOPE, null) ?: return BluetoothScope.OFF
        // 存枚举名而非下标：下标顺序一变，用户的设置就静默错位了。
        return runCatching { BluetoothScope.valueOf(raw) }.getOrDefault(BluetoothScope.OFF)
    }

    fun saveBluetoothScope(context: Context, scope: BluetoothScope) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BLUETOOTH_SCOPE, scope.name).apply()
    }
    private const val KEY_SMOOTH_VIDEO = "smooth_video"
    private const val KEY_CLUSTER_MAP = "cluster_map_enabled"
    private const val KEY_ADB_CLUSTER_ACTIVITY = "adb_cluster_activity_enabled"
    private const val KEY_CENTER_MAP_OVERLAY = "center_map_overlay"
    private const val KEY_CENTER_MAP_AUTO_HIDE = "center_map_auto_hide"
    private const val KEY_LAUNCHER_MAP_SHARING = "launcher_map_sharing"
    private const val KEY_CLUSTER_MAP_SCALE = "cluster_map_scale_percent"
    private const val KEY_CLUSTER_CONTENT = "cluster_content"
    private const val KEY_CLUSTER_MARKER_X = "cluster_marker_horizontal_step"
    private const val KEY_CLUSTER_MARKER_Y = "cluster_marker_vertical_step"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION = "cluster_turn_card_overlay_position"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE = "cluster_turn_card_overlay_size"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_X = "cluster_turn_card_overlay_x_percent"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_Y = "cluster_turn_card_overlay_y_percent"
    private const val KEY_CENTER_MAP_FOLLOWS_DASHBOARD = "center_map_follows_dashboard"
    private const val KEY_SETTINGS_GESTURE_FINGERS = "settings_gesture_fingers"

    /** v71: 下滑打开独立菜单（false）还是启动页完整设置（true）。 */
    private const val KEY_SWIPE_OPENS_FULL_SETTINGS = "swipe_opens_full_settings"
    private const val KEY_WIDTH_PHYSICAL_MM = "display_width_physical_mm"
    private const val KEY_PHYSICAL_SIZE_BASIS = "display_physical_size_basis"
    private const val KEY_MAX_DETECTED_WIDTH = "display_max_detected_width"
    private const val KEY_MAX_DETECTED_HEIGHT = "display_max_detected_height"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_HIDE_TOP_BAR = "hide_top_bar"
    private const val KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar"
    private const val KEY_SAFE_AREA_DRAW_OUTSIDE = "safe_area_draw_outside"
    private const val KEY_ADAPT_PIP_RESOLUTION = "adapt_pip_resolution"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_LOCATION_REPORTING_ENABLED = "location_reporting_enabled"
    private const val KEY_MFI_TARGET = "mfi_target"
    private const val KEY_MFI_I2C_PATH = "mfi_i2c_path"
    private const val KEY_REMOTE_MFI_SERVER = "remote_mfi_server"
    private const val KEY_REMOTE_MFI_TOKEN = "remote_mfi_token"
    private const val SAFE_AREA_KEY_PREFIX = "safe_area_"
    private const val CUSTOM_ICON_FILE = "airplay-icon.png"

    const val DEFAULT_MANUFACTURER = "DiPlay"
    const val DEFAULT_MODEL = "DiPlay"
    const val DEFAULT_OEM_LABEL = "BYD"
    const val DEFAULT_MFI_I2C_PATH = "/dev/i2c-1"

    fun loadAmbientDelaySeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("ambient_delay_seconds", 2).coerceIn(0, 60)

    fun saveAmbientDelaySeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ambient_delay_seconds", seconds.coerceIn(0, 60)).apply()
    }

    fun loadDisplayScalePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("display_scale_percent", loadDisplayScaleTenths(context) * 10)
            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)

    fun saveDisplayScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                "display_scale_percent",
                percent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT),
            ).apply()
    }
    /** Applied by the CarPlay host so overlay position/size updates without reconnecting. */
    @Volatile var overlaySettingsListener: (() -> Unit)? = null

    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT = "cluster_turn_card_overlay_size_percent"
    private const val KEY_CLUSTER_TURN_CARD_OPACITY = "cluster_turn_card_opacity_percent"

    /** B561 仪表媒体卡 / 通话卡 / 转向卡开关。 */
    private const val KEY_CLUSTER_MEDIA_CARD = "cluster_media_card_enabled"
    private const val KEY_CLUSTER_CALL_CARD = "cluster_call_card_enabled"
    private const val KEY_CLUSTER_TURN_CARD = "cluster_turn_card_enabled"

    /**
     * B561: 把车辆数据（电量/续航）回传手机 CarPlay。
     *
     * 刻意**不复用** `BydOutputSettings.batteryToIphone`：那个的
     * `batteryToIphoneActive()` 里套着 `supportedInSelectedMode { it.batterySupported }`
     * 门禁，而那是比亚迪 SOME/IP 能力探测的结果 —— B561 上永远 false，
     * 复用会让开关打开也没数据。
     */
    private const val KEY_B561_VEHICLE_STATUS_TO_IPHONE = "b561_vehicle_status_to_iphone"

    // ★ B561: 每张卡**独立**的几何。原先三张卡共用转向卡那一份x/y/size，
    // 结果媒体卡与转向卡永远重叠、无法分开摆 —— 用户抱怨"重合无法单独调整"。
    // 通话卡默认落在**下方错开**，避免与转向卡/媒体卡撞一起。
    private const val KEY_CLUSTER_MEDIA_X = "cluster_media_card_x_percent"
    private const val KEY_CLUSTER_MEDIA_Y = "cluster_media_card_y_percent"
    private const val KEY_CLUSTER_MEDIA_SIZE = "cluster_media_card_size_percent"
    private const val KEY_CLUSTER_MEDIA_OPACITY = "cluster_media_card_opacity_percent"
    private const val KEY_CLUSTER_MEDIA_ARTWORK = "cluster_media_card_artwork"
    private const val KEY_CLUSTER_MEDIA_TEXT_SCALE = "cluster_media_card_text_scale"
    private const val KEY_CLUSTER_MEDIA_WIDTH = "cluster_media_card_width_percent"
    private const val KEY_CLUSTER_MEDIA_HEIGHT = "cluster_media_card_height_percent"
    private const val KEY_CLUSTER_MEDIA_ORIENTATION = "cluster_media_card_orientation"
    private const val KEY_CLUSTER_MEDIA_SCROLL_TEXT = "cluster_media_card_scroll_text"

    private const val KEY_CLUSTER_CALL_X = "cluster_call_card_x_percent"
    private const val KEY_CLUSTER_CALL_Y = "cluster_call_card_y_percent"
    private const val KEY_CLUSTER_CALL_SIZE = "cluster_call_card_size_percent"
    private const val KEY_CLUSTER_CALL_OPACITY = "cluster_call_card_opacity_percent"

    /**
     * B561: 路口放大图（车道级导航图）。
     *
     * 默认落在**左侧**（x=24）—— 转向卡默认在右侧 76，两者天然错开，
     * 不需要用户先挪开就能同时看见。
     */
    private const val KEY_CLUSTER_JUNCTION_ENABLED = "cluster_junction_card_enabled"
    private const val KEY_CLUSTER_JUNCTION_X = "cluster_junction_card_x_percent"
    private const val KEY_CLUSTER_JUNCTION_Y = "cluster_junction_card_y_percent"
    private const val KEY_CLUSTER_JUNCTION_SIZE = "cluster_junction_card_size_percent"
    private const val KEY_CLUSTER_JUNCTION_OPACITY = "cluster_junction_card_opacity_percent"

    /** B561: 路口放大图默认几何（与 KEY_CLUSTER_JUNCTION_* 的默认值一致）。 */
    private const val DEFAULT_CLUSTER_JUNCTION_X = 24
    private const val DEFAULT_CLUSTER_JUNCTION_Y = 30
    private const val DEFAULT_CLUSTER_JUNCTION_SIZE = ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT

    /**
     * B561: 车况回传手机 CarPlay 的开关。默认**开** ——
     * 手机地图上的续航/电量是常用信息，不该藏在设置里。
     */
    fun loadB561VehicleStatusToIphone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_B561_VEHICLE_STATUS_TO_IPHONE, true)

    fun saveB561VehicleStatusToIphone(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_B561_VEHICLE_STATUS_TO_IPHONE, enabled).apply()
    }

    /** 媒体卡是否显示封面（0x5001 不带封面，取自 AndroidMediaSink 的解码帧）。 */
    fun loadClusterMediaArtwork(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLUSTER_MEDIA_ARTWORK, true)

    fun saveClusterMediaArtwork(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLUSTER_MEDIA_ARTWORK, enabled).apply()
    }

    /**
     * B561: 媒体卡文字大小百分比。
     *
     * 与「卡片大小」是两件事：那个改整张卡（背景+封面一起缩放，字跟着等比变小），
     * 这个**只改字号**，所以用户想放大歌名时不必把整张卡也放大。
     * 默认 100 = 上游原始规格，保持升级前的观感不变。
     */
    fun loadClusterMediaTextScalePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_MEDIA_TEXT_SCALE, 100)

    fun saveClusterMediaTextScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MEDIA_TEXT_SCALE, percent.coerceIn(60, 180)).apply()
    }

    /**
     * B561: 媒体卡宽度百分比（可视区宽度的百分比）。
     *
     * 与「大小」滑块的区别：那个改的是**等比缩放**整张卡（宽高同时变），
     * 这个**只管宽**。配合 [KEY_CLUSTER_MEDIA_HEIGHT] 可以自由定形状。
     */
    fun loadClusterMediaWidthPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(
                KEY_CLUSTER_MEDIA_WIDTH,
                ClusterTurnCardOverlay.DEFAULT_MEDIA_WIDTH_PERCENT,
            )

    fun saveClusterMediaWidthPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MEDIA_WIDTH, percent).apply()
    }

    /** B561: 媒体卡高度百分比（可视区高度的百分比）。 */
    fun loadClusterMediaHeightPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(
                KEY_CLUSTER_MEDIA_HEIGHT,
                ClusterTurnCardOverlay.DEFAULT_MEDIA_HEIGHT_PERCENT,
            )

    fun saveClusterMediaHeightPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MEDIA_HEIGHT, percent).apply()
    }

    /**
     * B561: 媒体卡排放方向。
     *
     * 存名字而不是序号 —— 以后往枚举里插新值不会让用户的设置错位。
     */
    fun loadClusterMediaOrientation(context: Context): ClusterTurnCardOverlay.CardOrientation =
        ClusterTurnCardOverlay.CardOrientation.entries.firstOrNull {
            it.name == context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CLUSTER_MEDIA_ORIENTATION, null)
        } ?: ClusterTurnCardOverlay.CardOrientation.FREEFORM

    fun saveClusterMediaOrientation(context: Context, orientation: ClusterTurnCardOverlay.CardOrientation) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CLUSTER_MEDIA_ORIENTATION, orientation.name).apply()
    }

    /**
     * B561: 媒体卡歌名/歌手是否滚动显示。
     *
     * 默认**关闭**：多数歌名字数不多，静态显示更稳；只有长曲目名被截断成"…"时
     * 才值得开滚动。开启后宽度够时仍是静态文本，所以代价很小。
     */
    fun loadClusterMediaScrollText(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLUSTER_MEDIA_SCROLL_TEXT, false)

    fun saveClusterMediaScrollText(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLUSTER_MEDIA_SCROLL_TEXT, enabled).apply()
    }

    /** 三种卡各自的 x/y/size/opacity。默认值不同 —— 媒体卡偏左下、通话卡居中偏下。 */
    fun loadClusterMediaXPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_MEDIA_X, 24)

    fun saveClusterMediaXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MEDIA_X, percent).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterMediaYPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_MEDIA_Y, 72)

    fun saveClusterMediaYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MEDIA_Y, percent).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterMediaSizePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_MEDIA_SIZE, ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT)

    fun saveClusterMediaSizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MEDIA_SIZE, percent).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterMediaOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_MEDIA_OPACITY, ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT)

    fun saveClusterMediaOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MEDIA_OPACITY, percent.coerceIn(20, 100)).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterCallXPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_CALL_X, 50)

    fun saveClusterCallXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_CALL_X, percent).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterCallYPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_CALL_Y, 18)

    fun saveClusterCallYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_CALL_Y, percent).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterCallSizePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_CALL_SIZE, ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT)

    fun saveClusterCallSizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_CALL_SIZE, percent).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterCallOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_CALL_OPACITY, ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT)

    fun saveClusterCallOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_CALL_OPACITY, percent.coerceIn(20, 100)).apply()
        overlaySettingsListener?.invoke()
    }

    // ── B561: 路口放大图（车道级导航图）──────────────────────────────────

    /**
     * B561: 是否显示路口放大图。
     *
     * 默认**开**：这是 iPhone 唯一会下发的车道级数据，也是原厂仪表最有价值的
     * 那一块。用户不要可以关掉，与转向卡互不影响。
     */
    fun loadClusterJunctionCardEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLUSTER_JUNCTION_ENABLED, true)

    fun saveClusterJunctionCardEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLUSTER_JUNCTION_ENABLED, enabled).apply()
        overlaySettingsListener?.invoke()
    }

    /** B561: 路口放大图水平位置（面板宽的百分比）。默认 24，与转向卡（76）错开。 */
    fun loadClusterJunctionXPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_JUNCTION_X, DEFAULT_CLUSTER_JUNCTION_X)

    fun saveClusterJunctionXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_JUNCTION_X, percent).apply()
        overlaySettingsListener?.invoke()
    }

    /** B561: 路口放大图垂直位置（面板高的百分比）。 */
    fun loadClusterJunctionYPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_JUNCTION_Y, DEFAULT_CLUSTER_JUNCTION_Y)

    fun saveClusterJunctionYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_JUNCTION_Y, percent).apply()
        overlaySettingsListener?.invoke()
    }

    /** B561: 路口放大图大小（可视区宽的百分比，等比）。 */
    fun loadClusterJunctionSizePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_JUNCTION_SIZE, DEFAULT_CLUSTER_JUNCTION_SIZE)

    fun saveClusterJunctionSizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_JUNCTION_SIZE, percent).apply()
        overlaySettingsListener?.invoke()
    }

    /** B561: 路口放大图背景不透明度（20..100）。 */
    fun loadClusterJunctionOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_JUNCTION_OPACITY, ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT)

    fun saveClusterJunctionOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_JUNCTION_OPACITY, percent.coerceIn(20, 100)).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadDisplayScaleTenths(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CarPlayDisplayScale.sanitize(
            prefs.getInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.DEFAULT_TENTHS),
        )
    }

    fun saveDisplayScaleTenths(context: Context, tenths: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.sanitize(tenths))
            .apply()
    }

    fun loadHevcEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_ENABLED, false)

    fun loadUiScalePercent(context: Context): Int = CarPlayUiScale.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.DEFAULT),
    )

    fun saveUiScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.sanitize(percent)).apply()
    }

    fun saveHevcEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_ENABLED, enabled)
            .apply()
    }

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_SOFTWARE_DECODER, false)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_SOFTWARE_DECODER, enabled)
            .apply()
    }

    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, false)

    fun saveAdvancedAudioChannelMapping(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, enabled)
            .apply()
    }

    fun loadNavigationStreamType(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NAVIGATION_STREAM_TYPE, 14)

    fun saveNavigationStreamType(context: Context, streamType: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_STREAM_TYPE, streamType)
            .apply()
    }

    fun loadAudioFocusEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS_ENABLED, false)

    fun saveAudioFocusEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_FOCUS_ENABLED, enabled)
            .apply()
    }

    fun loadAudioFocusAutoYield(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS_AUTO_YIELD, true)

    fun saveAudioFocusAutoYield(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_FOCUS_AUTO_YIELD, enabled)
            .apply()
    }

    // ─── v80: 焦点转移协议宣告实验 ───────────────────────────────

    /**
     * ★ v80: 是否在 `/info` 的 viewArea 里宣告 `viewAreaSupportsFocusTransfer`。
     *
     * ## 这不是 Apple SDK 的 FocusTransfer，我们抄不到那个接口
     *
     * 逆向商业接收端 `com.autochips.carplayapp`（含 Apple AirPlay Receiver SDK）
     * 确认它用的是 `AirPlayReceiverSessionAccessoryAcquireFocus` +
     * `AirPlayReceiverSessionHasFeatureFocusTransfer`。那是 **SDK 二进制库调用**，
     * 我们没有这个库。
     *
     * 但 `viewAreaSupportsFocusTransfer` 是**协议层字段**，可以自己填：
     * CarPlaySDK 反汇编（`_AirPlayScreenDictSetViewAreas`）确认它的三条约束：
     * 1. 只在 `type == 110`（主流）上发射，alt/cluster（type 111）不带
     * 2. Apple **无条件写入**，即使关闭也写 `false`（缺键导致形状不符）
     * 3. 与顶层 features 位**正交**，不是配合关系
     *
     * ## ⚠️ 预期收益有限，不要指望它解决「断蓝牙」
     *
     * 它管的是「焦点在 CarPlay 界面与原生界面间移动」（导航播报时高亮 CarPlay 图标），
     * **不是音频焦点**，更不是断开手机蓝牙。
     * 真机抓包还显示 `focusTransfer` 在协商中**常被协商掉**
     * （2026-08-02 实测 14 个 key 只 6 个存活，focusTransfer 未存活）—— 宣告 ≠ 生效。
     *
     * 所以默认 **false**：开了若连接异常，第一件事就是关掉它。
     */
    fun loadViewAreaFocusTransfer(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIEW_AREA_FOCUS_TRANSFER, false)

    fun saveViewAreaFocusTransfer(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VIEW_AREA_FOCUS_TRANSFER, enabled)
            .apply()
    }

    /**
     * ★ v80: 是否在 `/info` 顶层下发 `disableBluetooth`。
     *
     * ## 依据与不确定性（务必先读）
     *
     * 逆向 `com.autochips.carplayapp` 的 `libcarplay_jni.so` 时，
     * `disableBluetooth` 出现在 plist 键表里，**与 `telephony` /
     * `speechRecognition` / `media` 同层级**，配套日志字符串是
     * `AirPlay Disable Bluetooth session control request`。
     * 它连接成功后**会断开手机蓝牙**，推测靠的就是这个键——
     * 让 iPhone 关闭蓝牙会话控制（而不是车机强行断 profile）。
     *
     * **但无法确认它是否真是发给 iPhone 的协议键**，也可能是 SDK 内部
     * RIMP 配置项的名字。加错了有两种后果：
     * - iPhone 忽略未知键 → 无害（本开关的乐观情形）
     * - 形状校验失败 → **连不上车**（所以必须可一键关）
     *
     * 与 [disableAudioOutput] 的区别：后者是「删掉整个 audioFormats +
     * 音频 feature 位」（代价是 CarPlay 完全无声，MG4 就这么放弃了音频）；
     * 本键更细粒度，理论上只影响蓝牙会话控制。
     */
    fun loadDisableBluetoothPlist(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLE_BLUETOOTH_PLIST, false)

    fun saveDisableBluetoothPlist(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DISABLE_BLUETOOTH_PLIST, enabled)
            .apply()
    }

    /**
     * ★ v80: 是否在音频诊断里读系统音频状态
     * （`isSpeakerphoneOn` / `isBluetoothScoOn` / `isMicrophoneMute`）。
     *
     * ## 为什么这个最该先开
     *
     * 「语音提示音从手机听筒播放」有两种完全不同的根因，处置相反：
     * - **车机侧 HFP 抢着**（`isBluetoothScoOn=true`）→ 断 profile 方向对
     * - **iPhone 在本地播放**（SCO 关闭、流根本没建）→ 接收端侧任何改动都无效
     *
     * 这三个值能一次分辨。**纯读取，不修改任何选路**，零风险。
     */
    fun loadAudioRouteDiagnostics(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_ROUTE_DIAGNOSTICS, true)

    fun saveAudioRouteDiagnostics(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_ROUTE_DIAGNOSTICS, enabled)
            .apply()
    }

    fun loadMediaAudioChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_AUDIO_CHANNEL, 0)
            .takeIf { it in AUDIO_CHANNELS } ?: 0

    fun saveMediaAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_AUDIO_CHANNEL, channel.takeIf { it in AUDIO_CHANNELS } ?: 0)
            .apply()
    }

    fun loadNavigationAudioChannel(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Inherit the legacy value only when the new key is absent; preserve fresh-install and explicit 0 defaults.
        return prefs.getInt(KEY_NAVIGATION_AUDIO_CHANNEL, prefs.getInt(KEY_NAVIGATION_STREAM_TYPE, 0))
            .takeIf { it in AUDIO_CHANNELS } ?: 0
    }

    fun saveNavigationAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_AUDIO_CHANNEL, channel.takeIf { it in AUDIO_CHANNELS } ?: 0)
            .apply()
    }

    /**
     * B561: `default` 流（导航语音与 UI 提示音共流）的去向。
     *
     * 默认 [NavigationVoiceScope.ALWAYS] —— B561 把 [android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE]
     * (usage=12) 路由到「只主驾发声」的导航声区并跟随导航音量。CarPlay 导航语音必须恒定进该声区，
     * 否则会掉到 usage=13（提示音组）导致「能听到但不跟导航音量」。
     *
     * 升级迁移：历史设备可能把该项持久化为 [NavigationVoiceScope.OFF]（表现为上述 bug），
     * 此处统一强制回到 [NavigationVoiceScope.ALWAYS]；用户若主动选择过
     * [NavigationVoiceScope.GUIDANCE_ONLY] 则予以保留。
     */
    fun loadNavigationVoiceScope(context: Context): com.shilapi.xcertplay.media.NavigationVoiceScope {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAVIGATION_VOICE_SCOPE, null)
        // ★ v87: 撤销 v86 的「OFF 一律升为 ALWAYS」。
        //   v86 实车后果：default 流恒走 usage=12 后 iPhone 上**根本不出现 CarPlay 音频输出**，
        //   声音回落到手机扬声器与蓝牙 —— 说明这台车的 usage=12 轨道我们建不起来（或建起来也不出声），
        //   而 v84 在 scope=OFF（usage=13）下是正常出声的。
        //   所以这里恢复「存什么用什么」：设备上的 OFF 重新生效，声音回来。
        return com.shilapi.xcertplay.media.NavigationVoiceScope.fromKey(stored)
    }

    fun saveNavigationVoiceScope(
        context: Context,
        scope: com.shilapi.xcertplay.media.NavigationVoiceScope,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAVIGATION_VOICE_SCOPE, scope.name)
            .apply()
    }

    /**
     * B561: `alert`/`compatibility`（提示音）是否走 [AudioChannel.SYSTEM]。
     *
     * 默认 true —— 这正是"手机打字声不该走车辆导航声道"的修复开关。
     * 关掉即回到 DiPlay 0.2.14 的原始映射（并入导航/媒体），
     * 保留它是为了在实车上做 A/B 对照，确认到底是哪条流在响。
     */
    fun loadSystemAudioRouting(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYSTEM_AUDIO_ROUTING, true)

    fun saveSystemAudioRouting(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SYSTEM_AUDIO_ROUTING, enabled)
            .apply()
    }

    fun loadWirelessEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIRELESS_ENABLED, true)

    fun saveWirelessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_ENABLED, enabled)
            .apply()
    }

    fun loadMfiTarget(context: Context): MfiTarget {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_TARGET, null)
        return MfiTarget.entries.firstOrNull { it.name == stored } ?: MfiTarget.LOCAL
    }

    fun saveMfiTarget(context: Context, target: MfiTarget) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_TARGET, target.name)
            .apply()
    }

    fun loadMfiI2cPath(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_I2C_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MFI_I2C_PATH

    fun saveMfiI2cPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_I2C_PATH, path.trim())
            .apply()
    }

    fun loadRemoteMfiServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_SERVER, null)
            .orEmpty()

    fun saveRemoteMfiServer(context: Context, server: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_SERVER, server)
            .apply()
    }

    fun loadRemoteMfiToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_TOKEN, null)
            .orEmpty()

    fun saveRemoteMfiToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_TOKEN, token)
            .apply()
    }

    fun loadWirelessHotspotMode(context: Context): WirelessHotspotMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_WIRELESS_HOTSPOT_MODE, null)
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored }
            ?: WirelessHotspotMode.MANUAL
        val supported = if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) WirelessHotspotMode.MANUAL else mode
        if (stored != supported.name) saveWirelessHotspotMode(context, supported)
        return supported
    }

    fun saveWirelessHotspotMode(context: Context, mode: WirelessHotspotMode) {
        val supported = if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) WirelessHotspotMode.MANUAL else mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WIRELESS_HOTSPOT_MODE, supported.name)
            .apply()
    }

    fun loadWifiP2pPreferredChannel(context: Context): Int = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WIFI_P2P_PREFERRED_CHANNEL, WifiP2pChannels.AUTO)
            .takeIf(WifiP2pChannels::isValid) ?: WifiP2pChannels.AUTO
    }.getOrDefault(WifiP2pChannels.AUTO)

    fun saveWifiP2pPreferredChannel(context: Context, channel: Int) {
        require(WifiP2pChannels.isValid(channel))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_WIFI_P2P_PREFERRED_CHANNEL, channel).apply()
    }

    fun loadExistingWifiSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("existing_wifi_ssid", "").orEmpty()

    fun loadExistingWifiPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("existing_wifi_passphrase", "").orEmpty()

    fun saveExistingWifiCredentials(context: Context, ssid: String, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("existing_wifi_ssid", ssid)
            .putString("existing_wifi_passphrase", passphrase).apply()
    }

    fun loadManualHotspotSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_SSID, null)
            .orEmpty()

    fun saveManualHotspotSsid(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SSID, ssid)
            .apply()
    }

    fun loadManualHotspotPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_PASSPHRASE, null)
            .orEmpty()

    fun saveManualHotspotPassphrase(context: Context, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_PASSPHRASE, passphrase)
            .apply()
    }

    fun loadManualHotspotBand(context: Context): ManualHotspotBand {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_BAND, null)
        return ManualHotspotBand.entries.firstOrNull { it.name == stored }
            ?: ManualHotspotBand.AUTO
    }

    fun saveManualHotspotBand(context: Context, band: ManualHotspotBand) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_BAND, band.name)
            .apply()
    }

    fun loadManualHotspotChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MANUAL_HOTSPOT_CHANNEL, 0)
            .coerceIn(0, 196)

    fun saveManualHotspotChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MANUAL_HOTSPOT_CHANNEL, channel.coerceIn(0, 196))
            .apply()
    }

    fun loadManualHotspotSecurity(context: Context): ManualHotspotSecurity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MANUAL_HOTSPOT_SECURITY, null)
        return ManualHotspotSecurity.entries.firstOrNull { it.name == stored }
            ?: if (loadManualHotspotPassphrase(context).isEmpty()) {
                ManualHotspotSecurity.OPEN
            } else {
                ManualHotspotSecurity.WPA2
            }
    }

    fun saveManualHotspotSecurity(context: Context, security: ManualHotspotSecurity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SECURITY, security.name)
            .apply()
    }

    fun loadDebugLogsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGS_ENABLED, false)

    fun saveDebugLogsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEBUG_LOGS_ENABLED, enabled)
            .apply()
    }

    fun loadAutoStartOnBoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_ON_BOOT, false)

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
            .apply()
    }

    fun loadLocationReportingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCATION_REPORTING_ENABLED, false)

    fun saveLocationReportingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCATION_REPORTING_ENABLED, enabled)
            .apply()
    }

    fun loadManufacturer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUFACTURER, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANUFACTURER

    fun saveManufacturer(context: Context, manufacturer: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUFACTURER, manufacturer)
            .apply()
    }

    fun loadModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

    fun saveModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    fun loadOemLabel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OEM_LABEL, DEFAULT_OEM_LABEL)
            // iOS hides the car icon without a label.
            .orEmpty().ifBlank { DEFAULT_OEM_LABEL }

    fun saveOemLabel(context: Context, oemLabel: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OEM_LABEL, oemLabel)
            .apply()
    }

    fun loadAmbientLightThreshold(context: Context): AmbientLightThreshold = AmbientLightThreshold.fromStored(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_AMBIENT_LUX_THRESHOLD, AmbientLightThreshold.DEFAULT_LUX),
    )

    fun saveAmbientLightThreshold(context: Context, threshold: AmbientLightThreshold) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AMBIENT_LUX_THRESHOLD, threshold.lux).apply()
    }

    fun loadCarPlayNightMode(context: Context): CarPlayNightMode = CarPlayNightMode.fromKey(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CARPLAY_NIGHT_MODE, null),
    )

    fun saveCarPlayNightMode(context: Context, mode: CarPlayNightMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARPLAY_NIGHT_MODE, mode.key).apply()
    }

    fun loadCarPlayNightSchedule(context: Context): CarPlayNightSchedule {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = CarPlayNightSchedule()
        val start = preferences.getInt(KEY_CARPLAY_NIGHT_START, defaults.startMinute)
        val end = preferences.getInt(KEY_CARPLAY_NIGHT_END, defaults.endMinute)
        return CarPlayNightSchedule(
            start.takeIf { it in 0 until 24 * 60 } ?: defaults.startMinute,
            end.takeIf { it in 0 until 24 * 60 } ?: defaults.endMinute,
        )
    }

    fun saveCarPlayNightSchedule(context: Context, schedule: CarPlayNightSchedule) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CARPLAY_NIGHT_START, schedule.startMinute)
            .putInt(KEY_CARPLAY_NIGHT_END, schedule.endMinute)
            .apply()
    }

    fun loadFps(context: Context): Int = AirPlayDisplaySettings.sanitizeFps(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, 30),
    )

    fun loadMediaBufferMillis(context: Context): Int = com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS),
    )

    /** CarPlay's buffered music (Apple Music sends ahead over TCP); off by default, applies at reconnect. */
    fun loadMainBufferedAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MAIN_BUFFERED_AUDIO, false)

    fun saveMainBufferedAudio(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MAIN_BUFFERED_AUDIO, enabled).apply()
    }

    fun loadSmoothVideo(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SMOOTH_VIDEO, false)

    fun saveSmoothVideo(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SMOOTH_VIDEO, enabled).apply()
    }

    fun saveMediaBufferMillis(context: Context, millis: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(millis)).apply()
    }

    /** DiPlay's own experimental echo canceller on CarPlay call audio; opt-in, applies at reconnect. */
    fun loadCallEchoCancellation(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_ECHO_CANCELLATION, false)

    fun saveCallEchoCancellation(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALL_ECHO_CANCELLATION, enabled).apply()
    }

    /** Experimental bass cut on CarPlay call audio; opt-in, applies at reconnect. */
    fun loadCallVoiceFilter(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_VOICE_FILTER, false)

    fun saveCallVoiceFilter(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALL_VOICE_FILTER, enabled).apply()
    }

    /**
     * ★ B561 v70: 会话建立后是否自动断开**车机侧**的 A2DP 音频。
     *
     * 默认**关闭** —— 上游 DiPlay 没有这个行为，而它会让
     * `CarPlayController.selectWirelessBluetoothDevices()` 的"优先选正连着 A2DP 的
     * iPhone"排序退化到按地址排。多台已配对 iPhone 的车机上可能挑错设备，
     * 所以宁可让用户自己开。
     */
    fun loadDisconnectA2dp(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISCONNECT_A2DP, false)

    fun saveDisconnectA2dp(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DISCONNECT_A2DP, enabled).apply()
    }

    /**
     * v76: 旧布尔开关 → 新枚举的一次性迁移。
     *
     * 早期版本只有一个 `disconnect_a2dp` 布尔（= 新的 [BluetoothScope.AUDIO]）。
     * 直接换成枚举的话，升级后**所有已开的用户都会被重置成 OFF** ——
     * 设置静默丢失是最难查的一类问题。这里读一次旧的写新的，之后只用枚举。
     *
     * 注意顺序：新枚举优先，只有它**从未被写过**时才回退看旧布尔，
     * 否则用户在新 UI 关掉开关后，下次启动会被旧布尔"复活"。
     */
    fun loadBluetoothScopeMigrated(context: Context): AirPlayPersistence.BluetoothScope {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_BLUETOOTH_SCOPE, null)
        if (saved != null) return runCatching { AirPlayPersistence.BluetoothScope.valueOf(saved) }
            .getOrDefault(AirPlayPersistence.BluetoothScope.OFF)
        val legacy = if (prefs.contains(KEY_DISCONNECT_A2DP)) {
            prefs.getBoolean(KEY_DISCONNECT_A2DP, false)
        } else false
        val scope = if (legacy) AirPlayPersistence.BluetoothScope.AUDIO else AirPlayPersistence.BluetoothScope.OFF
        saveBluetoothScope(context, scope)
        return scope
    }

    /**
     * ★ v73: 是否向 iPhone**声明"本接收端没有音频输出"**。
     *
     * ## 与 [loadDisconnectA2dp] 的分工（两个都叫"断蓝牙"，机制完全不同）
     *
     * | | 机制 | 时机 | 效果 |
     * |---|---|---|---|
     * | [loadDisconnectA2dp] | `BluetoothA2dp.disconnect()`反射调用 | 会话建立后 1.5s | 断掉**已经在放的手机音乐**，CarPlay 音频照旧 |
     * | 本项 | `/info` 的 features 清音频位 + 不发 audioFormats | **协商阶段，早于任何流** | iPhone 压根不建音频流 |
     *
     * 为什么需要后者：反射断 A2DP 是**事后补救** —— 音频已经起来过了，
     * 车机 HAL 已经把流接过来了，断的瞬间仍可能有爆音/焦点抖动，
     * 且有些车机会自动重连 A2DP。声明式则是"一开始就说没有"，
     * 干净一个量级（DiPlay-CN PR #432 的结论）。
     *
     * ## 代价 —— 这是整车音频架构变更，不是小开关
     *
     * 打开后 **CarPlay 完全没有音频**：导航提示音、通话、音乐全没有。
     * 上游 [com.shilapi.xcertplay.airplay.AirPlayConfig.disableAudioOutput] 与
     * [AirPlayInfoPlist] 早已把整条实现写好（features 位+ 跳过 audioFormats/
     * audioLatencies + `bufferedAudioOutputEnabled`联动），但**从未接线**，
     * 没有任何 UI 能设置它 —— 这里补上接线。
     *
     * 默认 false：绝大多数车机还是要 CarPlay 音频的。
     */
    fun loadDisableAudioOutput(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISABLE_AUDIO_OUTPUT, false)

    fun saveDisableAudioOutput(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DISABLE_AUDIO_OUTPUT, enabled).apply()
    }

    /**
     * ★ v78: 是否**不使用**车机麦克风。默认 false（用车机麦克风）。
     *
     * 注意与 [loadDisableAudioOutput] 相反的极性 —— 这个 key 存在是因为
     * `RECORD_AUDIO` 权限缺失时 `AirPlayConfig.microphone` 会自动变 false，
     * 需要一个**显式**开关才能在有权限时也强制关掉（用于现场对比验证）。
     */
    fun loadDisableMicrophone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLE_MICROPHONE, false)

    fun saveDisableMicrophone(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DISABLE_MICROPHONE, disabled).apply()
    }

    fun saveFps(context: Context, fps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FPS, AirPlayDisplaySettings.sanitizeFps(fps))
            .apply()
    }

    fun loadWidthPhysicalMm(context: Context): Int =
        AirPlayDisplaySettings.sanitizeWidthPhysicalMm(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                KEY_WIDTH_PHYSICAL_MM,
                com.shilapi.xcertplay.airplay.CarPlaySize.DEFAULT.widthMillimeters,
            ),
        )

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.sanitizeWidthPhysicalMm(widthPhysicalMm),
            )
            .apply()
    }

    fun loadPhysicalSizeBasis(context: Context): AirPlayPhysicalSizeBasis {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHYSICAL_SIZE_BASIS, null)
        return AirPlayPhysicalSizeBasis.entries.firstOrNull { it.name == stored }
            ?: AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    }

    fun savePhysicalSizeBasis(context: Context, basis: AirPlayPhysicalSizeBasis) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHYSICAL_SIZE_BASIS, basis.name)
            .apply()
    }

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_DETECTED_WIDTH, 0) to
            prefs.getInt(KEY_MAX_DETECTED_HEIGHT, 0)
    }

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_DETECTED_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_MAX_DETECTED_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    fun loadClusterMapEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLUSTER_MAP, false)

    fun loadAdbClusterEnabled(context: Context): Boolean = loadClusterMapEnabled(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ADB_CLUSTER_ACTIVITY, false)

    fun saveAdbClusterEnabled(context: Context, enabled: Boolean) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADB_CLUSTER_ACTIVITY, enabled)
        if (enabled) edit.putBoolean(KEY_CLUSTER_MAP, true)
        edit.apply()
    }

    fun saveClusterMapEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLUSTER_MAP, enabled).apply()
    }

    /** The dashboard map as a card on the centre screen while DiPlay is in the background. */
    fun loadCenterMapOverlay(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CENTER_MAP_OVERLAY, false)

    /** Automatically hide the floating card when non-launcher apps are in the foreground. */
    fun loadCenterMapAutoHide(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CENTER_MAP_AUTO_HIDE, true)

    fun saveCenterMapAutoHide(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CENTER_MAP_AUTO_HIDE, enabled).apply()
    }

    /** Other launchers may show the live dashboard map in their own screen (MapEmbedService). */
    fun loadLauncherMapSharing(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LAUNCHER_MAP_SHARING, false)

    fun saveLauncherMapSharing(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LAUNCHER_MAP_SHARING, enabled).apply()
    }

    /** Observe consent changes for already attached launcher maps; call the returned function to unregister. */
    internal fun observeLauncherMapSharing(context: Context, changed: (Boolean) -> Unit): () -> Unit {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAUNCHER_MAP_SHARING) changed(loadLauncherMapSharing(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun saveCenterMapOverlay(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CENTER_MAP_OVERLAY, enabled).apply()
    }

    /** Whether to renegotiate resolution when entering/exiting freeform floating windows or launcher PiP. */
    fun loadAdaptPipResolution(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ADAPT_PIP_RESOLUTION, false)

    fun saveAdaptPipResolution(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ADAPT_PIP_RESOLUTION, enabled).apply()
    }

    fun loadClusterContent(context: Context): CarPlayClusterDisplay.Content =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLUSTER_CONTENT, null)
            ?.let { name -> CarPlayClusterDisplay.Content.entries.firstOrNull { it.name == name } }
            ?: if (AdbClusterRouter.enabled(context)) CarPlayClusterDisplay.Content.INSTRUMENTS else CarPlayClusterDisplay.Content.MAP

    fun saveClusterContent(context: Context, content: CarPlayClusterDisplay.Content) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CLUSTER_CONTENT, content.name).apply()
        overlaySettingsListener?.invoke()
    }

    /** Fingers for the swipe-down that opens settings; some head units reserve three. */
    fun loadSettingsGestureFingers(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SETTINGS_GESTURE_FINGERS, 3)
            // ★ v71: 0 是合法值 —— "关闭下滑手势"。很多车机的多指下滑已被自家
            //   面板占用（比亚迪三指=空调面板），保留一个明确的关闭档，
            //   比让用户反复试 2/3/4 却始终不生效要好。抄 DiPlay-CN 的四态循环。
            .let { if (it == 0) 0 else it.coerceIn(2, 4) }

    fun saveSettingsGestureFingers(context: Context, fingers: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SETTINGS_GESTURE_FINGERS, if (fingers == 0) 0 else fingers.coerceIn(2, 4))
            .apply()
    }

    /**
     * ★ v71: 下滑手势打开哪一套设置。
     *
     * false = 连接后独立菜单（本菜单，项都是免重连的）
     * true  = 启动页的完整设置（改名 / 转向卡 / 小屏 / 更新…）
     *
     * 默认 false：独立菜单才是滑动手势存在的意义 —— 滑一下就改最常用的一批。
     * 要改那些**必须退出连接才生效**的项时才切到 true。
     */
    fun loadSwipeOpensFullSettings(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SWIPE_OPENS_FULL_SETTINGS, false)

    fun saveSwipeOpensFullSettings(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SWIPE_OPENS_FULL_SETTINGS, value).apply()
    }

    fun loadCenterMapFollowsDashboard(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CENTER_MAP_FOLLOWS_DASHBOARD, true)

    fun saveCenterMapFollowsDashboard(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CENTER_MAP_FOLLOWS_DASHBOARD, enabled).apply()
    }

    fun loadClusterTurnCardOverlaySizePercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT, ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT),
                ClusterTurnCardOverlay.sizePercents,
            )
        }
        return when (prefs.getString(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE, null)) {
            "SMALL" -> 40
            "LARGE" -> 70
            else -> ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
        }
    }

    fun saveClusterTurnCardOverlaySizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.sizePercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_TURN_CARD_OPACITY, ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT)
            .coerceIn(20, 100)

    fun saveClusterTurnCardOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_TURN_CARD_OPACITY, percent.coerceIn(20, 100)).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlayXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_X)) {
            prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_X, ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
        } else when (prefs.getString(KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION, null)) {
            "LEFT" -> 20
            "CENTER" -> 50
            else -> ClusterTurnCardOverlay.DEFAULT_X_PERCENT
        }
        return ClusterTurnCardOverlay.snap(raw, ClusterTurnCardOverlay.xPercents)
    }

    fun saveClusterTurnCardOverlayXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_X,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.xPercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlayYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_Y)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_Y, ClusterTurnCardOverlay.DEFAULT_Y_PERCENT),
                ClusterTurnCardOverlay.yPercents,
            )
        }
        return ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    }

    fun saveClusterTurnCardOverlayYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_Y,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.yPercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterMapScalePercent(context: Context): Int = CarPlayClusterDisplay.STREAM_SCALE_PERCENT.let { default ->
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MAP_SCALE, default)
            .takeIf { it in CarPlayClusterDisplay.scalePresets } ?: default
    }

    fun saveClusterMapScalePercent(context: Context, percent: Int) {
        if (percent !in CarPlayClusterDisplay.scalePresets) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CLUSTER_MAP_SCALE, percent).apply()
    }

    fun loadClusterMarkerHorizontalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_X, 0)
            .coerceIn(CarPlayClusterDisplay.horizontalSteps)

    fun saveClusterMarkerHorizontalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_X, step.coerceIn(CarPlayClusterDisplay.horizontalSteps)).apply()
    }

    fun loadClusterMarkerVerticalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_Y, 0)
            .coerceIn(CarPlayClusterDisplay.verticalSteps)

    fun saveClusterMarkerVerticalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_Y, step.coerceIn(CarPlayClusterDisplay.verticalSteps)).apply()
    }

    // Cluster mapping has its own key; never reuse the main display mapping at the same resolution.
    fun loadClusterSafeAreaRect(context: Context): SafeAreaRect? =
        SafeAreaCodec.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("cluster_safe_area_1920x720", null))?.clampTo(1920, 720)

    fun saveClusterSafeAreaRect(context: Context, rect: SafeAreaRect) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("cluster_safe_area_1920x720", SafeAreaCodec.encode(rect.clampTo(1920, 720))).apply()
    }

    fun clearClusterSafeAreaRect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("cluster_safe_area_1920x720").apply()
    }

    fun loadRightHandDrive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RIGHT_HAND_DRIVE, rightHandDrive)
            .apply()
    }

    fun loadHideTopBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_TOP_BAR, true)

    fun saveHideTopBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_TOP_BAR, hide)
            .apply()
    }

    fun loadHideBottomBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_BOTTOM_BAR, true)

    fun saveHideBottomBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_BOTTOM_BAR, hide)
            .apply()
    }

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, true)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, drawOutside)
            .apply()
    }

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): SafeAreaRect? {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(safeAreaKey(widthPixels, heightPixels), null),
        )
    }

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: SafeAreaRect,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                safeAreaKey(activityWidthPixels, activityHeightPixels),
                SafeAreaCodec.encode(rect.clampTo(activityWidthPixels, activityHeightPixels)),
            )
        if (commit) editor.commit() else editor.apply()
    }

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(safeAreaKey(activityWidthPixels, activityHeightPixels))
        if (commit) editor.commit() else editor.apply()
    }

    fun loadCustomAirPlayIconFile(context: Context): File? =
        File(context.filesDir, CUSTOM_ICON_FILE).takeIf { it.isFile }

    fun saveCustomAirPlayIcon(context: Context, encodedImage: ByteArray) {
        require(encodedImage.isNotEmpty()) { "AirPlay icon data must not be empty" }
        File(context.filesDir, CUSTOM_ICON_FILE).outputStream().use { output ->
            output.write(encodedImage)
        }
    }

    fun clearCustomAirPlayIcon(context: Context) {
        File(context.filesDir, CUSTOM_ICON_FILE).delete()
    }

    fun loadIdentity(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val privateKey = prefs.getString(KEY_IDENT_PRIVATE, null)
        val publicKey = prefs.getString(KEY_IDENT_PUBLIC, null)
        val pairingId = prefs.getString(KEY_PAIRING_ID, null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            return AirPlayIdentity(privateKey.decodeHex(), publicKey.decodeHex(), pairingId)
        }
        return AirPlayIdentity.generate().also { identity ->
            prefs.edit()
                .putString(KEY_IDENT_PRIVATE, identity.privateKey.toHex())
                .putString(KEY_IDENT_PUBLIC, identity.publicKey.toHex())
                .putString(KEY_PAIRING_ID, identity.pairingId)
                .apply()
        }
    }

    fun loadPairings(context: Context, onSave: (String, ByteArray) -> Unit): PairingStore {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = PairingStore(onSave)
        for (identifier in prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty()) {
            prefs.getString("pairing.$identifier", null)?.let { store.save(identifier, it.decodeHex()) }
        }
        return store
    }

    fun savePairing(context: Context, identifier: String, longTermPublicKey: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identifiers = prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toMutableSet()
        identifiers.add(identifier)
        prefs.edit()
            .putString("pairing.$identifier", longTermPublicKey.toHex())
            .putStringSet(KEY_PAIRING_IDS, identifiers)
            .apply()
    }

    fun loadLockdownRecord(context: Context): LockdownPairRecord? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostId = prefs.getString(KEY_LOCKDOWN_HOST_ID, null) ?: return null
        val systemBuid = prefs.getString(KEY_LOCKDOWN_SYSTEM_BUID, null) ?: return null
        val wifiMac = prefs.getString(KEY_LOCKDOWN_WIFI_MAC, null) ?: return null
        val devicePublic = prefs.getString(KEY_LOCKDOWN_DEVICE_PUBLIC, null) ?: return null
        val deviceCert = prefs.getString(KEY_LOCKDOWN_DEVICE_CERT, null) ?: return null
        val hostPrivate = prefs.getString(KEY_LOCKDOWN_HOST_PRIVATE, null) ?: return null
        val hostCert = prefs.getString(KEY_LOCKDOWN_HOST_CERT, null) ?: return null
        val rootPrivate = prefs.getString(KEY_LOCKDOWN_ROOT_PRIVATE, null) ?: return null
        val rootCert = prefs.getString(KEY_LOCKDOWN_ROOT_CERT, null) ?: return null
        return try {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = devicePublic.decodeHex(),
                deviceCertificatePem = deviceCert.decodeHex(),
                hostPrivateKeyPem = hostPrivate.decodeHex(),
                hostCertificatePem = hostCert.decodeHex(),
                rootPrivateKeyPem = rootPrivate.decodeHex(),
                rootCertificatePem = rootCert.decodeHex(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCKDOWN_HOST_ID, record.hostId)
            .putString(KEY_LOCKDOWN_SYSTEM_BUID, record.systemBuid)
            .putString(KEY_LOCKDOWN_WIFI_MAC, record.wifiMacAddress)
            .putString(KEY_LOCKDOWN_DEVICE_PUBLIC, record.devicePublicKeyPem.toHex())
            .putString(KEY_LOCKDOWN_DEVICE_CERT, record.deviceCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_HOST_PRIVATE, record.hostPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_HOST_CERT, record.hostCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_PRIVATE, record.rootPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_CERT, record.rootCertificatePem.toHex())
            .apply()
    }

    fun clearLockdownRecord(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .apply()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun safeAreaKey(widthPixels: Int, heightPixels: Int): String =
        "$SAFE_AREA_KEY_PREFIX${widthPixels}x$heightPixels"

    // ------------------------------------------------- B561 仪表媒体卡 / 通话卡开关

    /**
     * 仪表媒体卡开关。默认**关** —— 仪表屏上多盖一块卡是驾驶员要衡量的，
     * 不该默认替用户做主。用户想要歌名时自己打开。
     */
    fun loadClusterMediaCardEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLUSTER_MEDIA_CARD, false)

    fun saveClusterMediaCardEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLUSTER_MEDIA_CARD, enabled).apply()
        overlaySettingsListener?.invoke()
    }

    /** 仪表通话卡开关。默认**开**：来电是安全相关信息，不该被设置藏起来。 */
    fun loadClusterCallCardEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLUSTER_CALL_CARD, true)

    fun saveClusterCallCardEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLUSTER_CALL_CARD, enabled).apply()
        overlaySettingsListener?.invoke()
    }

    /**
     * 仪表转向卡（DiPlay 自绘的自定义转向卡，叠在地图上方）开关。
     *
     * 默认**开** —— 转向卡是导航核心信息，用户此前一直用着，去掉开关后它在
     * “地图 + 自定义转向卡”模式下仍由内容模式决定显隐，这里补回一个独立开关，
     * 让它能像媒体卡 / 通话卡一样单独开/关。
     */
    fun loadClusterTurnCardEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLUSTER_TURN_CARD, true)

    fun saveClusterTurnCardEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLUSTER_TURN_CARD, enabled).apply()
        overlaySettingsListener?.invoke()
    }

    // ── 仪表投屏：第三方 CAST_GATE 后端 + B561 原生飞屏门控 ──

    /**
     * ★ 仪表投屏后端开关（第三方 com.thunder.tbxt CAST_GATE）。
     *
     * 默认**关闭**。这是与 B561 原生「飞屏」完全独立的另一套车机平台后端：
     * 打开后仪表地图走 CAST_GATE 的 partial 通道
     * （[com.shilapi.xcertplay.cluster.ClusterCastGate]）。
     * 在 B561 上目标包不存在，打开也是静默无操作 —— 仅用于支持该接口的车机。
     */
    private const val KEY_CLUSTER_CAST_GATE = "cluster_cast_gate"

    fun loadClusterCastGate(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLUSTER_CAST_GATE, false)

    fun saveClusterCastGate(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLUSTER_CAST_GATE, enabled).apply()
    }

    /**
     * ★ B561：仅在仪表「飞屏」开启时才输出仪表画面。
     *
     * 默认**关闭** —— 保留升级前行为，不依赖任何新广播。
     * 打开后，[com.shilapi.xcertplay.cluster.ClusterFlyScreenMonitor] 监听 B561 原生
     * `action_screen_cast` 广播；收到 key=0（飞屏关）时本 App 不再绘制仪表画面，
     * 收到 key=1 / `ENTERS_THE_INSTRUMENT_NAVIGATION`（飞屏开）时才绘制。
     * 从未收到广播（UNKNOWN）时按现有行为放行，避免误拦截。
     */
    private const val KEY_CLUSTER_FLY_SCREEN_GATE = "cluster_fly_screen_gate"

    fun loadClusterFlyScreenGate(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLUSTER_FLY_SCREEN_GATE, false)

    fun saveClusterFlyScreenGate(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLUSTER_FLY_SCREEN_GATE, enabled).apply()
    }
}
