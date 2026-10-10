package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import java.lang.reflect.Method

/**
 * 长安欧尚 B561 车机能力探测。
 *
 * 目的:在不猜测的前提下,实测本车机
 *  1. 显示拓扑 —— 仪表屏是否为可用的第二块屏(HDMI 外接)
 *  2. CarCabinManager 车辆属性 —— 与原厂仪表同源的读数通道
 *  3. 音频输出能力 —— 声道/采样率/设备路由
 *
 * 全部通过反射调用,避免编译期依赖车机私有/厂商类,保证普通 AOSP 环境也能编译通过。
 * 探测结果通过 [report] 输出为纯文本,由 DiPlayActivity 的诊断导出功能收集。
 */
object VehicleProbe {

    // ---------------------------------------------------------------- 显示

    fun report(context: Context): String = buildString {
        appendLine("### Display topology (仪表屏是否作为第二屏存在)")
        appendLine("api=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        val wm = context.getSystemService(WindowManager::class.java)
        if (wm == null) {
            appendLine("WindowManager unavailable")
            return@buildString
        }
        // 全部走反射: getDisplays()/isEnabled()/getRealInfo() 在不同 API 级别签名不同,
        // 反射可同时兼容 Android 9 车机与编译期 SDK 37。
        val displays: List<Any> = runCatching {
            val m = wm.javaClass.methods.firstOrNull { it.name == "getDisplays" && it.parameterCount == 0 }
            @Suppress("UNCHECKED_CAST")
            (m?.invoke(wm) as? Array<Display>)?.toList() ?: emptyList()
        }.getOrElse { e -> listOf(apply { Log.w(TAG, "getDisplays failed", e) }).let { emptyList() } }
        appendLine("displayCount=${displays.size}")
        displays.forEach { d ->
            val display = d as Display
            val enabled = runCatching {
                Display::class.java.getMethod("isEnabled").invoke(display) as? Boolean
            }.getOrNull()
            appendLine("  display id=${display.displayId} name=${display.name} " +
                "state=${display.state} valid=${display.isValid} enabled=$enabled")
            // getRealInfo(DisplayInfo) —— Android 9 上可用;View 类 API 为 API 17+
            runCatching {
                val infoClass = Class.forName("android.view.DisplayInfo")
                val info = infoClass.getDeclaredConstructor().newInstance()
                runCatching { display.javaClass.getMethod("getRealInfo", infoClass).invoke(display, info) }
                appendLine("    realInfo=$info")
            }.onFailure { appendLine("    realInfo unavailable: ${it.javaClass.simpleName}") }
        }
        // DisplayManager 的 HDMI 物理状态
        runCatching {
            val dm = context.getSystemService(DisplayManager::class.java)
            val displays2 = dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            appendLine("presentationDisplays=${displays2?.size ?: -1}")
            displays2?.forEach { appendLine("  presentation: id=${it.displayId} name=${it.name} type=${it.displayId}") }
        }.onFailure { appendLine("DisplayManager probe failed: ${it.javaClass.simpleName}: ${it.message}") }
        appendLine("NOTE: 若 displayCount=1,说明抓日志时仪表屏未点亮/未识别,需点亮后重测。")
        appendLine()

        appendLine("### CarCabinManager (车辆属性, 与原厂仪表同源)")
        appendLine(cabinReport(context))
        appendLine()

        appendLine("### Audio output capability (声道/采样率)")
        appendLine(audioReport(context))
        appendLine()

        appendLine("### CarAudioService (多音区/导航专声区 —— 决定 usage=12 走哪条路)")
        appendLine(audioZoneReport(context))
        appendLine()

        // ★ B561 v67: 双路车辆数据（VHAL + logcat）的实际融合情况。
        //   没有它就只能看到"读到/读不到"，看不到"是谁给的"——
        //   而双路的全部价值就在于某一路能补另一路的缺。
        appendLine("### Dual-source vehicle status (VHAL + logcat)")
        appendLine(dualSourceReport())
    }

    /**
     * B561 v67: 双路车辆数据诊断。
     *
     * ★ 必须读**正在运行的那个 provider**，不能新建一个 ——
     *   [HybridVehicleStatus] 的读数只在自己的轮询线程跑过之后才有值，
     *   新建的实例没启动过，会永远报"not started"，等于没接。
     *   所以由 [CarPlayHostActivity] 在创建时注册进来；没注册时如实说明。
     */
    private fun dualSourceReport(): String {
        val live = activeStatusProvider ?: return "(no live status provider registered)"
        return runCatching { live.diagnosticReport() }.getOrElse {
            "dual-source probe failed: ${it.javaClass.simpleName}: ${it.message}"
        }
    }

    /**
     * 当前活跃的 provider。由 [CarPlayHostActivity] 在 `onCreate` 时注册。
     *
     * 为什么要注册而不是现new：[HybridVehicleStatus] 的 VHAL 轮询与 logcat 读流
     * 都是有代价的（1 Hz 反射 + 一个常驻读流），诊断不该为了拿一份报告再开一套。
     */
    @Volatile
    private var activeStatusProvider: B561VehicleStatusProvider? = null

    /** 注册活跃 provider；传 null 解除（Activity 销毁时）。 */
    internal fun bindStatusProvider(provider: B561VehicleStatusProvider?) {
        activeStatusProvider = provider
    }

    /**
     * 当前活跃的 provider。给设置页监测面板用。
     *
     * 设置页与 [CarPlayHostActivity] 是**两个不同的 Activity**，
     * 所以它们各自 new 一个 provider 是拿不到读数的 —— 必须共用这一个实例。
     */
    fun activeStatusProvider(): B561VehicleStatusProvider? = activeStatusProvider

    // ------------------------------------------------- CarAudioService 音区

    /**
     * B561: 探测 `android.car.media.CarAudioService` —— 这是 Android Automotive 的
     * **官方多音区 API**，也是我们 v64/v65 那个`usage = 12` 判断的**判决点**。
     *
     * ## 为什么这个探测决定性
     *
     * v59 实测确认：把导航语音的 `AudioAttributes.usage` 设成 **12**
     * （`USAGE_UNDEFINED`，厂商自定义值）就会走"只主驾发声"的导航专声区，
     * 而 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`（11）**不行**。
     * 甘风 CarPlay（商业接收端）也独立用 12 —— 但我们至今**不知道车机侧的规则**：
     *
     * - 如果车机有 `CarAudioService`，那usage=12 很可能只是它某个音区的映射键，
     *   我们就能**读出**每个音区对应哪些usage，并可能找到更"官方"的入口。
     * - 如果没有，说明 MTK 在 framework 层做了私有扩展（我离线核对过：
     *   `boot-framework.vdex` 与所有 jar 里都搜不到 `CarAudioService`，
     *   所以它**不在 boot-classpath**，只能运行时确认），usage=12 就是唯一入口。
     *
     * 两种结果都有用：前者能让我们改用官方 API，后者能**证实**当前做法是对的。
     * 所以这是"不知道就测"，不是猜测。
     *
     * ## 已知的厂商音区名（来自原厂 launcher 的 HiCar 集成）
     *
     * launcher 里出现了 `APP_FOCUS_TYPE_NAVIGATION` / `APP_FOCUS_TYPE_VOICE_COMMAND`
     * 与 `STREAM_NAVI` / `STREAM_CAST_MUSIC` / `STREAM_AI_ASSISTANT` / `STREAM_TTS`
     * 等厂商自定义流 —— 说明 B561 的音频分类比AOSP 丰富。这些也一并探测。
     */
    private fun audioZoneReport(context: Context): String = buildString {
        // 1) 官方/厂商的 CarAudioService 类是否存在
        val candidates = listOf(
            "android.car.media.CarAudioService",
            "android.car.media.CarAudioManager",
            "android.car.audio.CarAudioService",
        )
        var serviceClass: Class<*>? = null
        candidates.forEach { name ->
            val cls = runCatching { Class.forName(name) }.getOrNull()
            if (cls != null) {
                appendLine("FOUND class=$name")
                serviceClass = cls
            } else {
                appendLine("missing $name")
            }
        }
        if (serviceClass == null) {
            appendLine()
            appendLine("结论: 本车机**没有** CarAudioService —— 说明 MTK 在 framework 层做了私有扩展。")
            appendLine("      推论: usage=12 是当前唯一可用的导航专声区入口（v59 实测有效，保持现状）。")
            appendLine("      下一步: 用下面的 usage 探测确认 12 到底落在哪个输出设备上。")
            appendLine()
            appendLine(usageRoutingProbe(context))
            return@buildString
        }

        // 2) 服务实例怎么拿 —— 不同版本签名不同，逐个试
        val service = sequenceOf("car_audio", "audio", "car")
            .mapNotNull { name -> runCatching { context.getSystemService(name) }.getOrNull() }
            .firstOrNull { runCatching { it.javaClass.name.contains("CarAudio") }.getOrDefault(false) }
        appendLine("serviceInstance=${service?.javaClass?.name ?: "null"}")
        if (service == null) {
            appendLine("服务名未命中（试过 car_audio / audio / car）")
            appendLine()
            appendLine(usageRoutingProbe(context))
            return@buildString
        }

        // 3) 列出全部方法 —— 重点找 zone / focus / patch / mix / volume
        val methods = runCatching { service.javaClass.methods.toList() }.getOrElse { emptyList() }
        appendLine("methods=${methods.size}")
        val interesting = methods
            .map { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})" }
            .filter { sig ->
                sig.startsWith("get") || sig.startsWith("list") || sig.contains("Zone", true) ||
                    sig.contains("focus", true) || sig.contains("Patch", true) ||
                    sig.contains("mix", true) || sig.contains("Volume", true) ||
                    sig.contains("Occupant", true)
            }
            .distinct()
            .sorted()
        appendLine("--- zone/focus/patch 相关方法 (${interesting.size}) ---")
        interesting.take(80).forEach { appendLine("  $it") }

        // 4) ★ 核心：读出实际音区配置
        runCatching {
            for (m in service.javaClass.methods) {
                val n = m.name
                if (n.startsWith("get") && n.contains("Zone", true) && m.parameterCount == 0) {
                    val zones = m.invoke(service)
                    appendLine("--- $n() ---")
                    appendLine("  value=$zones")
                    appendLine("  class=${zones?.javaClass?.name}")
                    // 音区通常是数组，展开看看每个 zone 的 config
                    if (zones is Array<*>) {
                        zones.filterNotNull().forEachIndexed { i, z ->
                            appendLine("  [$i] $z")
                            runCatching {
                                z.javaClass.methods
                                    .filter { it.parameterCount == 0 && it.name.startsWith("get") }
                                    .sortedBy { it.name }
                                    .forEach { gm ->
                                        runCatching { gm.invoke(z) }
                                            .getOrNull()
                                            ?.let { v -> appendLine("      ${gm.name} = $v") }
                                    }
                            }.onFailure { appendLine("  [$i] detail failed: ${it.javaClass.simpleName}") }
                        }
                    }
                }
            }
        }.onFailure { appendLine("zone read failed: ${it.javaClass.simpleName}: ${it.message}") }

        appendLine()
        appendLine(usageRoutingProbe(context))
    }

    /**
     * 读 `audio_policy.conf` 风格的配置，确认 usage 与输出设备的映射。
     *
     * `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 给出的是**当前**设备，
     * 看不到 usage 路由表。但有两件事可以确定：
     *  1. 车上到底挂了几个输出设备（喇叭 / HDMI / 导航专声区是不是独立设备）
     *  2. 有没有厂商自定义的设备类型（TYPE_* 里 AOSP 只定义到 15）
     */
    private fun usageRoutingProbe(context: Context): String = buildString {
        val am = context.getSystemService(AudioManager::class.java)
        if (am == null) {
            appendLine("AudioManager unavailable")
            return@buildString
        }
        val outs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull().orEmpty()
        appendLine("--- 输出设备 (${outs.size}) ---")
        val types = outs.mapNotNull { dev ->
            runCatching { dev.javaClass.getMethod("getType").invoke(dev) as? Int }.getOrNull()
        }.distinct().sorted()
        // AOSP 的 TYPE_ 常量（AudioDeviceInfo）
        val known = mapOf(
            1 to "BUILTIN_EARPIECE", 2 to "BUILTIN_SPEAKER", 3 to "WIRED_HEADSET",
            4 to "WIRED_HEADPHONES", 8 to "BLUETOOTH_SCO", 7 to "BLUETOOTH_A2DP",
            9 to "HDMI", 10 to "HDMI_ARC", 11 to "USB_DEVICE", 12 to "USB_ACCESSORY",
            13 to "DOCK", 14 to "FM", 15 to "FM_TUNER", 16 to "TV_TUNER",
            17 to "TV_TUNER", 18 to "TV", 19 to "HDMI_EARC",
        )
        types.forEach { t ->
            val name = known[t] ?: "VENDOR_CUSTOM($t)"
            appendLine("  type=$t $name")
        }
        if (types.any { it > 19 }) {
            appendLine()
            appendLine("★ 发现 >TYPE_AUX_LINE(19) 的厂商自定义输出设备 —— 这很可能就是")
            appendLine("  导航专声区。若usage=12 的流在这些设备上播放，就坐实了我们的判断。")
            appendLine("  请在导航时抓 logcat 观察 AudioFlinger / AudioTrack 的输出设备切换。")
        }
    }

    // ------------------------------------------------------------ CarCabin

    private fun cabinReport(context: Context): String = buildString {
        val cls = runCatching { Class.forName("android.car.hardware.cabin.CarCabinManager") }
            .getOrNull()
        if (cls == null) {
            appendLine("CarCabinManager NOT available on this framework (车机未提供该 HAL)")
            return@buildString
        }
        appendLine("class=android.car.hardware.cabin.CarCabinManager (available)")
        val service = runCatching {
            context.getSystemService("car_cabin")
        }.getOrNull()
        appendLine("getSystemService(\"car_cabin\")=${service?.javaClass?.name ?: "null"}")
        if (service == null) {
            // 某些实现走 Car service 而非独立 car_cabin
            runCatching { context.getSystemService("car") }.getOrNull()?.let {
                appendLine("getSystemService(\"car\")=${it.javaClass.name}")
            }
        }

        // 反射列出方法，确认可用的读取入口
        runCatching {
            val names = service?.javaClass?.methods
                ?.map { it.name }
                ?.filter { it.startsWith("get", true) || it.startsWith("set", true) || it.contains("Callback") }
                ?.distinct()
                ?.sorted()
                ?: emptyList()
            appendLine("carCabin methods (${names.size}):")
            names.take(60).forEach { appendLine("  $it") }
        }.onFailure { appendLine("method scan failed: ${it.message}") }

        // 尝试对日志中观测到的属性做定点读取
        val observed = intArrayOf(
            0x2140f05e, 0x2140f477, 0x2140f4a9, 0x2140f636, 0x2140eeb4,
            0x2140f4b5, 0x2140f4b3, 0x2140f431, 0x2141f22a, 0x2141f6f0,
            0x2141f6f1, 0x2140f6d2, 0x2140f6d3, 0x2140f602, 0x2140f603,
            0x11600207, 0x11400400, 0x11400409, 0x13400bc0, 0x2140f6c4,
            0x2160f479, 0x2160f381,
        )
        appendLine("--- observed property reads (来自实车日志中的 propId) ---")
        service?.let { svc ->
            val getInt = findMethod(svc.javaClass, "getCarCabinIntValue")
            val getFloat = findMethod(svc.javaClass, "getCarCabinFloatValue")
            val getBool = findMethod(svc.javaClass, "getCarCabinBooleanValue")
            observed.forEach { pid ->
                val i = invokeInt(svc, getInt, pid)
                val f = invokeFloat(svc, getFloat, pid)
                val b = invokeBool(svc, getBool, pid)
                if (i != null || f != null || b != null) {
                    appendLine("  0x%08x int=%s float=%s bool=%s".format(pid, i, f, b))
                } else {
                    appendLine("  0x%08x <unreadable by this app>".format(pid))
                }
            }
        } ?: appendLine("service null, skipped property reads")
    }

    private fun findMethod(cls: Class<*>, name: String): Method? = runCatching {
        cls.methods.firstOrNull { it.name == name }
    }.getOrNull()

    private fun invokeInt(target: Any, method: Method?, propId: Int): Int? = runCatching {
        method?.invoke(target, propId, 0) as? Int
    }.getOrNull()

    private fun invokeFloat(target: Any, method: Method?, propId: Int): Float? = runCatching {
        method?.invoke(target, propId, 0) as? Float
    }.getOrNull()

    private fun invokeBool(target: Any, method: Method?, propId: Int): Boolean? = runCatching {
        method?.invoke(target, propId, 0) as? Boolean
    }.getOrNull()

    // --------------------------------------------------------------- Audio

    private fun audioReport(context: Context): String = buildString {
        val am = context.getSystemService(AudioManager::class.java)
        if (am == null) {
            appendLine("AudioManager unavailable")
            return@buildString
        }
        appendLine("sampleRate(default out)=${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)}")
        appendLine("framesPerBuffer=${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}")

        // 音频设备: getType()/getChannelCount() 在 Android 9 存在,
        // getTypeName()/getProductName() 是 API 29+ —— 统一走反射。
        val outputDevices: List<Any> = runCatching {
            @Suppress("UNCHECKED_CAST")
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() ?: emptyList()
        }.getOrElse { e -> listOf<Any>().also { Log.w(TAG, "getDevices failed", e) } }
        appendLine("outputDevices=${outputDevices.size}")
        var maxChannels = 0
        outputDevices.forEach { dev ->
            val type = runCatching { dev.javaClass.getMethod("getType").invoke(dev) }.getOrNull()
            val channels = runCatching { dev.javaClass.getMethod("getChannelCount").invoke(dev) as? Int }
                .getOrNull() ?: 0
            val rates = runCatching { dev.javaClass.getMethod("getSampleRates").invoke(dev) }
                .getOrNull()
            val typeName = runCatching { dev.javaClass.getMethod("getTypeName").invoke(dev) }
                .getOrNull()
            if (channels > maxChannels) maxChannels = channels
            appendLine("  type=$type typeName=$typeName channels=$channels sampleRates=$rates")
        }

        // 声道能力:这决定能否做多声道输出
        appendLine("--- 声道判定 ---")
        appendLine("maxOutputChannels=$maxChannels")
        appendLine(if (maxChannels >= 6) "结论: 支持多声道(>=5.1)" else "结论: 仅立体声,无法输出 5.1/7.1")
        appendLine("注: audio_policy.conf 中 hdmi_multi_channel(5.1/7.1) 仅对 AUX_DIGITAL 设备开放,")
        appendLine("    喇叭(SPEAKER)主输出固定为 AUDIO_CHANNEL_OUT_STEREO。")
    }

    private const val TAG = "B561-VehicleProbe"
}
