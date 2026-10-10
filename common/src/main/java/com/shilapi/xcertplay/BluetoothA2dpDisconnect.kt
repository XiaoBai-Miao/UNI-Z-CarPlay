package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ★ B561 v70: 断开**车机侧**的 A2DP 音频会话，用来缓解"手机蓝牙音乐 + CarPlay 音频"双焦点。
 *
 * ## 上游没有这个功能（已核查）
 *
 * 上游 DiPlay 全仓对 A2DP 只有两处引用：[android.bluetooth.BluetoothA2dp] 的 import，
 * 加上 `CarPlayController.connectedBluetoothDevices()` 里的一行读取 ——
 * 用途是**给候选 iPhone 排序**（优先选正连着 A2DP/HEADSET 的那台），不是断开。
 * `adapter.disable()` / `A2dp.disconnect()` 在上游**零出现**。
 *
 * ## 为什么只断 A2DP，不关蓝牙
 *
 * 车机蓝牙同时承担三件事，断任何一件都会坏：
 * - **A2DP**（立体声音乐）← 唯一要断的
 * - **HEADSET / HFP**（通话、通话音频）← 必须留
 * - **iAP2 配对与重连**（`CarPlayController.connectWirelessBluetoothDevices`）← 必须留
 *
 * 所以走 `BluetoothA2dp.disconnect(device)` 而不是 `BluetoothAdapter.disable()`。
 * 关蓝牙会让 iPhone 断开后车机**拉不起会话**，只能用户手动重连。
 *
 * ## 与 iAP2 选机逻辑的关系（不是 bug，是已知的副作用）
 *
 * `CarPlayController.selectWirelessBluetoothDevices()` 会优先挑当前连着 A2DP 的 iPhone。
 * 断开 A2DP 之后这个排序会退化到按地址排。首次配对（此时还没建会话）不受影响，
 * 但如果用户有多台 iPhone 且已配对过，重连时可能挑错 —— 所以这是个**开关**，
 * 默认关闭，让用户自己判断。
 *
 * ## 权限
 *
 * 上游 manifest 已声明 `BLUETOOTH_CONNECT` 与 `BLUETOOTH_ADMIN`（`maxSdkVersion=30`），
 * B561 是 API 28，两者都生效；`disconnect()` 属于 `BLUETOOTH_CONNECT` 运行时权限，
 * 平台签名 + `android.uid.system` 下可直接授予。
 *
 * ## 线程
 *
 * `getProfileProxy` 是异步的且回调在 binder 线程，这里用 `CountDownLatch` 等它回来
 * （与 `CarPlayController.connectedBluetoothDevices` 同一套写法）。
 * **必须在后台线程调用** —— 主线程调`await` 会 ANR。
 */
internal object BluetoothA2dpDisconnect {
    private const val TAG = "DiPlay-A2dp"
    private const val PROFILE_TIMEOUT_SECONDS = 3L

    /**
     * ★ v76: 本对象要断的 profile 集合。
     *
     * ## 为什么是「A2DP + HEADSET」而不是只 A2DP
     *
     * 用户要的是「断开手机与车的蓝牙连接」—— 手机连着车机时，A2DP（音乐）与
     * HEADSET/HFP（通话）是**两条独立链路**，只断 A2DP 时手机仍与车机保持
     * 蓝牙连接、铃声与通话音频还在，听起来「没断开」。
     *
     * ## 为什么**不能**用 `BluetoothAdapter.disable()`
     *
     * 关适配器会一并断掉 iAP2 配对与重连通道
     * （`CarPlayController.connectWirelessBluetoothDevices` 靠它），
     * 手机断开后车机**拉不起会话**，只能用户手动重连 ——
     * 这正是"断蓝牙"最容易踩的坑。逐 profile 断开只动音频链路。
     *
     * ## v77→v78: 聚合视图在这台车机上**不可用**，回退成 profile 名单
     *
     * 「彻底断开」本来想拿「当前实际连着哪些设备」的聚合视图再对每台调
     * `BluetoothDevice.disconnect()`，由系统决定断哪些 profile。
     *
     * ★ 实车打脸：`BluetoothAdapter.getConnectedDevices()` 在 B561 上**根本不存在**
     *   （NoSuchMethodException，见 disconnectAllLinks 注释），不是 hidden 可见性问题。
     *   所以第四档现在退化为「按名单挨个断」，名单要**覆盖车机实测会建的三条链路**。
     *
     * 实车日志（手动断开时蓝牙栈自己打的）证实这台车机建的是三条独立 profile：
     * ```
     * BluetoothHeadsetClient: disconnect(0C:6F:8B:1C:CC:CD)   ← HFP 通话
     * BluetoothA2dpSink:     disconnect(0C:6F:8B:1C:CC:CD)   ← A2DP Sink（车机当音箱）
     * BluetoothA2dp:         disconnect(0C:6F:8B:1C:CC:CD)   ← A2DP Source
     * ```
     * 注意前两条是**同一个 A2DP profile 的两端**（Sink/Source），断一次
     * `BluetoothA2dp.disconnect()` 蓝牙栈自己会走两侧，不需要单列 AVRCP ——
     * 而且 public SDK 的 `BluetoothProfile` 里**只有 `A2DP` / `HEADSET`**
     * 两个 profile 常量，AVRCP 连编译期常量都没有。
     */
    enum class Profile(val id: Int, val label: String) {
        A2DP(BluetoothProfile.A2DP, "A2DP"),
        HEADSET(BluetoothProfile.HEADSET, "HEADSET"),
    }

    /**
     * ★ v78: 「彻底断开」用的 profile 名单。
     *
     * A2DP 在最前（音乐双焦点是首要目标），HEADSET 垫后。
     * 这两个已是 public SDK 能枚举的全部 profile，覆盖这台车机的三条实测链路。
     */
    val ALL_PROFILES: List<Profile> = listOf(Profile.A2DP, Profile.HEADSET)

    /** 单次操作的结果，供调用方写进诊断日志。 */
    enum class Outcome {
        /** 没有连着 A2DP 的设备，本来就没什么可断的。 */
        NOTHING_CONNECTED,

        /** 至少断开了一台。 */
        DISCONNECTED,

        /** 拿不到 A2DP profile（适配器关闭、权限缺失、profile 不支持）。 */
        PROFILE_UNAVAILABLE,

        /** 连上了 profile 但 `disconnect()` 抛异常 —— 多半是权限被运行时撤销。 */
        FAILED,
    }

    /**
     * ★ v77: 「彻底断开」—— 断开**当前实际连着的所有蓝牙链路**。
     *
     * ## 与 [disconnect] 的区别
     *
     * [disconnect] 按 profile 名单断（`Profile.A2DP` / `HEADSET`），
     * 漏了名单外的 profile；本方法不问 profile，只问
     * **「现在有哪些设备连着」**（[BluetoothAdapter.getConnectedDevices]），
     * 然后对每台调 [BluetoothDevice.disconnect] —— 让系统自己决定断哪些链路。
     * 这样不受profile id 的 ROM 差异影响，也不会漏掉厂商私有 profile。
     *
     * ## 仍然**不关适配器**
     *
     * 关掉适配器会连 iAP2 配对与重连一起断，车机拉不起会话。
     * 本方法只断「已建立的连接」，配对关系（bond）保留 ——
     * 所以下次 CarPlay 仍能自动重连，只是不会有多余的音频链路抢车机喇叭。
     *
     * 副作用（预期内）：手机可能自动重连这些链路。
     * 部分车机在 A2DP 被断后会立刻尝试重连 —— 那属于手机侧行为，
     * 车机这边只能断一次；日志里能看到两次请求。
     *
     * @param onResult 每台设备 (地址, 是否成功) 的回调（binder 线程）。
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT 已由 manifest 声明
    fun disconnectAllLinks(
        context: Context,
        onResult: (address: String, ok: Boolean) -> Unit = { _, _ -> },
    ) {
        val manager = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
        if (manager == null) {
            Log.w(TAG, "Bluetooth manager unavailable")
            return
        }
        // 局部名不叫 adapter：这个文件里 companion 里已有同名的隐式引用，
        // 沿用adapter 会让Kotlin 解析到别的东西（曾推断成 String）。
        val btAdapter: BluetoothAdapter = manager.adapter ?: run {
            Log.w(TAG, "Bluetooth adapter unavailable")
            return
        }
        val names = runCatching { bondedNames(btAdapter) }.getOrDefault(emptyMap<String, String>())
        // ★★ v78: `BluetoothAdapter.getConnectedDevices()` 在这台 B561 上**根本不存在**。
        //
        // 实车日志证据（19:02:01）：
        //   W DiPlay-A2dp: BluetoothAdapter.getConnectedDevices() unavailable
        //   W DiPlay-A2dp: java.lang.NoSuchMethodException: getConnectedDevices []
        //
        // 不是 hidden API 的可见性问题，是厂商蓝牙栈**实现里就没有这个方法**（public
        // android.jar 里也没有该符号，可交叉验证）。所以反射必然失败。
        //
        // 因此这里只把聚合视图当作「有就用」的优化，拿不到**必须走回退**——
        // v77 在这里直接 return，把回退路径整个跳过了，导致「彻底断开」形同虚设。
        val devices: Set<BluetoothDevice> = connectedDevicesReflectively(btAdapter)
        if (devices.isEmpty()) {
            Log.i(TAG, "disconnectAllLinks: no aggregate view on this ROM; using per-profile fallback")
            // 车机蓝牙栈实测会各自断这三条链路（见实车日志）：
            //   BluetoothHeadsetClient: disconnect / BluetoothA2dpSink: disconnect /
            //   BluetoothA2dp: disconnect
            // AVRCP 是遥控链路，跟着 A2DP 一起断最干净。
            disconnect(context, ALL_PROFILES) { address, profile, r -> onResult(address, r) }
            return
        }
        var ok = 0
        var needFallback = false
        for (device in devices) {
            // BluetoothDevice.disconnect() 是 **@hide API**，SDK 里没有编译期符号。
            // 它断的是该设备**全部已建立链路** —— 正是「彻底」要的语义。
            // 不同 ROM 对 hidden API 的可见性/实现不一致，逐个兜住失败。
            val succeeded = runCatching {
                device.javaClass.getMethod("disconnect").invoke(device)
                true
            }.getOrElse { error ->
                Log.w(TAG, "BluetoothDevice.disconnect() unavailable on this ROM", error)
                false
            }
            if (succeeded) ok++ else needFallback = true
            onResult(device.address, succeeded)
            Log.i(
                TAG,
                "disconnectAllLinks: ${names[device.address] ?: device.address} " +
                    if (succeeded) "requested" else "deferred to profile fallback",
            )
        }

        // 回退路径：该ROM 不给 BluetoothDevice.disconnect 时，改走按 profile 断 ——
        // 至少保证 A2DP + HEADSET 断得掉（覆盖绝大多数「双焦点」场景）。
        if (needFallback) {
            Log.i(TAG, "disconnectAllLinks: falling back to per-profile disconnect")
            disconnect(context, ALL_PROFILES) { address, profile, r ->
                onResult(address, r)
            }
        }
        Log.i(TAG, "disconnectAllLinks: ${devices.size} device(s), $ok via hidden API")
    }

    /**
     * ★ v76: 按 profile 列表断开所有连着的设备。**阻塞**，只能在后台线程调用。
     *
     * 逐 profile 独立获取代理 —— 一个 profile 拿不到不影响另一个。
     * 少一个「因为 HEADSET 不支持就整条放弃」的失败模式：
     * 部分车机的通话profile 是 `HEADSET_WIFI`，id 对不上，
     * 那时 A2DP 仍应被断开。
     *
     * @param profiles 要断的 profile。传空列表则什么都不做。
     * @param onResult 每个 (设备地址, profile 名, 是否成功) 的回调（binder 线程）。
     */
    fun disconnect(
        context: Context,
        profiles: List<Profile>,
        onResult: (address: String, profile: String, ok: Boolean) -> Unit = { _, _, _ -> },
    ) {
        if (profiles.isEmpty()) return
        val manager = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
        val adapter = runCatching { manager?.adapter }.getOrNull()
        if (manager == null || adapter == null) {
            Log.w(TAG, "Bluetooth adapter unavailable")
            return
        }

        // 设备名只解析一次：getName() 也要 binder 往返，逐设备逐 profile 调太慢。
        val names = runCatching { bondedNames(adapter) }.getOrDefault(emptyMap())

        var anyDisconnected = false
        for (profile in profiles) {
            val proxy = awaitProfile(context, adapter, profile.id) ?: run {
                Log.w(TAG, "${profile.label} profile proxy unavailable")
                continue
            }
            try {
                val devices = runCatching { proxy.connectedDevices.orEmpty() }.getOrDefault(emptySet())
                for (device in devices) {
                    val ok = runCatching {
                        proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                            .invoke(proxy, device)
                        true
                    }.getOrElse { error ->
                        Log.w(TAG, "${profile.label} disconnect refused for ${device.address}", error)
                        false
                    }
                    anyDisconnected = anyDisconnected || ok
                    onResult(device.address, profile.label, ok)
                    Log.i(
                        TAG,
                        "${profile.label} ${if (ok) "disconnect requested" else "refused"} " +
                            "for ${names[device.address] ?: device.address}" +
                            if (ok) "" else "",
                    )
                }
            } finally {
                runCatching { adapter.closeProfileProxy(profile.id, proxy) }
            }
        }
        Log.i(TAG, "disconnect outcome=${if (anyDisconnected) "DISCONNECTED" else "NOTHING_CONNECTED"}")
    }

    /**
     * 已配对设备的地址 → 名称映射。
     *
     * 用 [BluetoothAdapter.getBondedDevices] 而不是 A2DP 的 connectedDevices：
     * 断开动作要报的是「哪台手机」，而 HEADSET 没连上时A2DP 那边可能已有结果，
     * 两者不一定同时存在。
     */
    /**
     * ★ v77:反射调 `BluetoothAdapter.getConnectedDevices()`。
     *
     * ## 为什么必须反射（已核查 android-37.0 的 android.jar）
     *
     * public SDK 的 [BluetoothAdapter] 只暴露 `getBondedDevices()` 与
     * `getMaxConnectedAudioDevices()` —— `getConnectedDevices()` 带
     * `@SystemApi`，**编译期没有这个符号**（写了直接报 unresolved reference）。
     *
     * 它的语义正是我们要的：所有 profile（A2DP / HEADSET / AVRCP / 厂商私有）
     * 的**已建立链路**聚合视图，不含「已配对但没连」的设备。
     *
     * 拿不到就返回空集 —— 上层会回退到按 profile 断A2DP + HEADSET。
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT 已由 manifest 声明
    private fun connectedDevicesReflectively(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        runCatching {
            val method = adapter.javaClass.getMethod("getConnectedDevices")
            @Suppress("UNCHECKED_CAST")
            method.invoke(adapter) as? Set<BluetoothDevice> ?: emptySet()
        }.getOrElse { error ->
            Log.w(TAG, "BluetoothAdapter.getConnectedDevices() unavailable", error)
            emptySet()
        }

    @SuppressLint("MissingPermission")
    private fun bondedNames(adapter: BluetoothAdapter): Map<String, String> =
        runCatching {
            adapter.bondedDevices.orEmpty().mapNotNull { device ->
                val name = runCatching { device.name }.getOrNull()
                if (name.isNullOrEmpty()) null else device.address to name
            }.toMap()
        }.getOrDefault(emptyMap())

    /**
     * v76: 泛化的 profile 代理获取（A2DP / HEADSET 都要用）。
     *
     * 返回 [BluetoothProfile] 而不是 [BluetoothA2dp]，因为调用方要对
     * 两种 profile 走同一套反射 `disconnect()`，而反射只需要 profile 接口。
     *
     * 用 latch 而非回调 —— `getProfileProxy` 的回调在 binder 线程，
     * 主线程等它会 ANR。沿用 `CarPlayController.connectedBluetoothDevices` 的写法。
     *
     * @param context 必须传：Android 9（API 28）上**没有**两参数的
     *   `getProfileProxy(listener, profile)` 重载，只有带 Context 的那个。
     */
    @SuppressLint("MissingPermission")
    private fun awaitProfile(context: Context, adapter: BluetoothAdapter, profileId: Int): BluetoothProfile? {
        val latch = CountDownLatch(1)
        var proxy: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, service: BluetoothProfile) {
                try {
                    proxy = service
                } finally {
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        val granted = runCatching { adapter.getProfileProxy(context, listener, profileId) }
            .getOrElse { error ->
                Log.w(TAG, "getProfileProxy($profileId) failed", error)
                false
            }
        if (!granted) return null
        if (!latch.await(PROFILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out connecting profile $profileId")
            return null
        }
        return proxy
    }

    /**
     * v76: 某个 profile 上当前连着的设备（地址 → 设备名）。**阻塞**。
     *
     * 菜单与日志里要显示「当前连着哪几台」时用这个，比裸地址对用户友好。
     */
    @SuppressLint("MissingPermission")
    fun connectedDevices(context: Context, profile: Profile): List<Pair<String, String?>> {
        val manager = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
        val adapter = runCatching { manager?.adapter }.getOrNull() ?: return emptyList()
        val names = runCatching { bondedNames(adapter) }.getOrDefault(emptyMap())
        val proxy = awaitProfile(context, adapter, profile.id) ?: return emptyList()
        return try {
            runCatching {
                proxy.connectedDevices.orEmpty().map { it.address to names[it.address] }
            }.getOrDefault(emptyList())
        } finally {
            runCatching { adapter.closeProfileProxy(profile.id, proxy) }
        }
    }
}
