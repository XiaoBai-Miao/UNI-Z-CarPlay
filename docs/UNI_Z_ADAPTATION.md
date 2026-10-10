# UNI-Z CarPlay 适配说明

本文记录 UNI-Z CarPlay 分支相对上游 DiPlay 0.2.14 的**技术细节**。
概览与构建见 [README.UNI-Z.md](../README.UNI-Z.md)。

---

## 一、仪表屏选屏

### 上游的问题

0.2.14 把屏判断换成了 `DiLink4ClusterDisplay.matches(name, w, h)`，按**屏名 + 尺寸**硬匹配比亚迪 DiLink4：

```kotlin
DiLink4ClusterDisplay.matches(clusterDisplay.name, clusterSize.x, clusterSize.y)
// 要求 name == "DiLink4" && 1920×720
```

B561 的仪表是 **HDMI 外接副屏**，屏名和尺寸都对不上 → `diLink4 = false`、`findDisplay()` 也找不到屏 → 界面上只剩「DiLink 4 仪表视频(ADB,实验性)」这一项，**没有选屏入口**。

旧版（0.2.13）这里用的是 `ClusterStreamProfile.matches()`，而它的实现就是 `= false` —— 刻意为之：B561 不做指纹匹配，一律走「枚举副屏 + 用户选屏」。

### 本分支的做法

把 `clusterDisplayControl(card, diLink4)` 搬回来，保留枚举 + 选屏逻辑。

顺带把「DiLink 4 cluster video via ADB (experimental)」在 B561 上**隐藏**了：

```kotlin
if (!isOushangB561) {
    reconnectingToggle(card, ... AdbClusterRouter ...)
}
```

它依赖 `AdbClusterRouter`（比亚迪 DiLink4 上用 ADB 抢占导航广播的专有路径），在 B561 上永远是无效开关 —— 只会让人以为要开它。

---

## 二、卡片几何

### 基数从宽度改成屏幕高度

上游用**可见区宽度**做百分比基数：

```kotlin
val width = (window.width * size / 100)   // ← 问题在这
```

1920×720 的超宽屏上，仪表内容安全区宽约 `1920 × (100-35-36)/100 ≈ 1308px`。size=55 算出 **719px 宽**的卡，一张卡横跨半屏 —— 位置滑块怎么调都躲不开别的卡。

本分支统一改成：

```kotlin
val width = (panelHeight * size / 100)
    .coerceAtLeast(150)
    .coerceAtMost(window.width)
```

### 竖版比例 1:1.35

`VERTICAL_ASPECT = 1.35f`（高 : 宽）。

曾试过 1:1 正方，实测反馈证明不行 —— 正方卡里封面上下都留不出空间，三行文字只能挤在半张卡里。

```kotlin
CardOrientation.VERTICAL -> {
    val base = (panelHeight * hPct / 100)
        .coerceAtLeast(90)
        //可见区高 ÷1.35 得到"卡片宽度上限"（高 = 宽 × 1.35）
        .coerceAtMost((window.height / VERTICAL_ASPECT).toInt())  // ★ 必须 .toInt()
        .coerceAtMost(window.width)
    width = base
    height = (base * VERTICAL_ASPECT).toInt()
}
```

> **踩坑**：`window.height / VERTICAL_ASPECT` 是 `Int ÷ Float` → `Float`，喂给 `coerceAtMost(Int)` 编译不过，要显式 `.toInt()`。

### 测试注意

`sizeScalesWithPanelHeight` 用 size=50 时，1440 高屏算出 720px 宽会被 `coerceAtMost(557)` 截断 —— 测到的是上限不是基数。改用 size=30（720h→216px / 1440h→432px，两档都不撞上限）。

---

## 三、媒体卡

### 三行语义

第三行是**专辑名**。iAP2 `NowPlayingUpdate (0x5001)` → `MediaItemAttributes`：

| 字段 | id |
|---|---|
| `title` | 1 |
| `album` | 6 |
| `artist` | 12 |

**没有第二个 artist 字段** —— 所以三行只能是歌名/歌手/专辑。

全链路接通：

```
ClusterSong.album                                  (shared/hud/BydClusterSong.kt)
  → CarPlayGlance.Snapshot.album                   (shared/glance/CarPlayGlance.kt)
  → CarPlayHostActivity.clusterMediaAlbum          (common)
  → ClusterMapPresentation.setMediaCard(...)       (common)
  → ClusterMediaCardView.setMedia(title, artist, album, playing)
```

换歌语义 —— title 变空时同时清artist 和 album：

```kotlin
if (nextTitle.isBlank()) {
    artist = null
    // ★ v69: 换歌时专辑也一起清 —— 否则上一首的专辑名会留在新歌下面
    album = null
}
```

### 白色圆点已删

原`drawRowLayout` 在封面右侧画了一个小圆点表示"在播放"（`chipPaint` + `drawCircle`）。仪表屏上它就是一个孤零零的白点，既不传达播放/暂停（颜色变了但没人盯着看），又跟封面白底糊在一起。

**已整块删除**：`chipPaint` 字段、`drawCircle` 调用、`applyPalette` 里的配色。

> `ClusterTurnCardView.chipPaint` **保留** —— 那是转向卡的圆角图标底衬，不是白点。

### 滚动实现

静态文本用 `clipToWidth()` **硬裁**（不用 `ellipsize()` 的省略号），超宽才启用走马灯：

```kotlin
private fun drawLines(canvas, lines, onDraw): Boolean  // scrollText 走 drawScrollingText
```

滚动用 `postInvalidateOnAnimation()` 按帧累加位移，`dt` clamp 到 `[4,50]ms`。

---

## 四、设置分区搬迁

### 预览 View 从局部提升为成员

媒体卡设置搬到「音频」分区后，两边是两个独立闭包，局部 `val preview` / `fun refreshPreview()` 互相看不见。

解法三步，**都必要**：

```kotlin
// 1. 提升为类成员
private var clusterPreview: ClusterCardPreview? = null

// 2. 懒初始化 —— 不能只在仪表分区 new
private fun ensureClusterPreview(card: LinearLayout): ClusterCardPreview? {
    clusterPreview?.let { return it }
    val created = ClusterCardPreview(this)
    clusterPreview = created
    refreshClusterPreview()
    return created
}

// 3. render() 开头置 null —— 否则会把已挂在旧树上的 View 再挂到新树
//    Android 抛 "specified child already has a parent"
clusterPreview = null
```

### dock 操作项

`ConnectedMenuDock` 新增 `addAction(key, title, accent, danger, onClick)`：

```kotlin
private val actionKeys = mutableSetOf<String>()

fun showSection(key: String) {
    // 操作项没有详情容器，误传进来会让右侧全空
    if (key in actionKeys) return
    ...
    rows.forEach { (rowKey, row) ->
        row.isSelected = rowKey == key
        // 操作项保留自己的强调色/危险色，不被选中态刷成普通白字
        if (rowKey in actionKeys) return@forEach
        row.setTextColor(if (rowKey == key) selectedColor else Color.WHITE)
    }
}
```

6 个操作项：侧边面板 / 保存并重连(accent) / 打开app 设置 / 手势 / 语言 / 退出(danger)。

底部页脚代码**整段删除**（原 69 行），`build()` 去掉 `footerView` 参数，右侧详情拿到整列高度（1920×910 上多约 300px）。

---

## 五、免重连设置（复制而非搬家）

新增 `KEY_MENU_QUICK`「常用设置」分区。

| 组 | 项 | persistence |
|---|---|---|
| 显示 | 昼夜模式（5 选 1） | `saveCarPlayNightMode` |
| 显示 | 隐藏顶栏 / 底栏 | `saveHideTopBar/BottomBar` |
| 仪表 | 地图总开关 | `saveClusterMapEnabled` |
| 仪表 | 媒体卡 / 路口卡 / 通话卡 | `saveCluster*CardEnabled` |
| 仪表 | 车辆数据回传 | `saveB561VehicleStatusToIphone` |
| 音频 | 通话语音过滤 | `saveCallVoiceFilter` |
| 系统 | 开机自启 / 调试日志 | `saveAutoStartOnBoot/DebugLogsEnabled` |

**为什么复制而不是搬家**：搬走会让 app 设置页在未连接时反而没处调。两边写同一份 SharedPreferences，不会分叉。

**刻意不搬**：

- **回声消除**（`saveCallEchoCancellation`）—— 它改音频通路，**要**重连
- **仪表几何滑块** —— 要在 1920×910 的预览上拖动，overlay 菜单里手感差

---

## 六、音频路由

CarPlay 把导航语音与手机 UI 提示音放在**同一路音频流**上，导致打字咔哒声从主驾导航喇叭出来。

**根因**：硬编 `usage = 11` (NAVIGATION_GUIDANCE) 会让 HAL 认定为导航专属音，全车播放。

**方案**：导航语音用 `usage = 12` (USAGE_UNDEFINED)，让车机 HAL 自主决定音频分区 —— 车企可以把它映射到导航专声区，从而实现主驾独占。

相关：`systemAudioRoutingControls`、`AudioChannelMapping`、音区探测（`AudioChannelPreview`）。

---

## 七、车辆数据双路融合

```
                 ┌─ CarCabinVehicleStatus ─┐
VHAL 反射 ───────┤                         ├─→ VehicleProbe ─→ CarCabinVehicleStatus
                 └─ VehicleLogTap ─────────┘      逐字段择优
```

**为什么要两路**：VHAL 是结构化的但字段覆盖不全；logcat 解析能拿到 VHAL 没有的字段但容易断流。任何一路不可用时另一路顶上。

`HybridVehicleStatus` 做字段级合并，标出数据来源（VHAL / logcat），设置页能实时看到读数与来源。

---

## 八、状态栏昼夜反色

上游 `hide(statusBars())` + 固定背景色 + `isAppearanceLightStatusBars = false` —— 状态栏一旦被用户下拉露出就是"深色图标配深色底"。

本分支改为按昼夜反色。判据优先级：

1. 用户选死 `DAY` / `NIGHT` → 照办
2. `SYSTEM` → 读系统 `uiMode`，但**读到 `UNDEFINED` 就保持上一次**（车机常年报 `UNDEFINED`，直接跟随等于永远不切）
3. `AMBIENT` / `SCHEDULE` → 退回系统 `uiMode`

见 `SystemBars.apply()` 与 `DiPlayActivity.applySystemBars()`。

---

## 九、测试适配

底部按钮改成 dock 操作项后是 `TextView` 而非 `Button`，`CarPlayHostSettingsTest` 的 `filterIsInstance<Button>()` 找不到元素：

```
NoSuchElementException: Sequence contains no element matching the predicate.
  at CarPlayHostSettingsTest.fullSettingsButton(CarPlayHostSettingsTest.kt:527)
```

`fullSettingsButton()` 与 `gestureButton()` 都改成 `filterIsInstance<TextView>()`。28 个测试全绿。