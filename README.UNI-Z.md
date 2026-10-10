# UNI-Z CarPlay

**DiPlay 0.2.14 的车机适配分支 —— 面向长安欧尚 / 比亚迪 B561。**

在上游 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 基础上，为 **Renesas R-Car 车机（Android 9/ API 28 / 1920×910）** 与 **比亚迪 B561 仪表屏**做深度定制：仪表屏投图、主驾专声音频路由、车辆数据回传。

> 上游 README（`README.md` / `README.zh-CN.md`）是完整的DiPlay 官方文档，**未做改动**。本文件只描述本分支的增量。

---

## 这个分支解决了什么

上游 DiPlay 面向比亚迪 DiLink 车机，直接搬到长安欧尚上有四类问题 —— 本分支逐一处理：

| 问题 | 上游行为 | 本分支 |
|---|---|---|
| **仪表屏选屏** | `DiLink4ClusterDisplay.matches()` 按「屏名 + 1920×720」硬匹配指纹，B561 恒为 `false` → 界面上**根本没有选屏入口** | 改回 0.2.13 的「枚举副屏 + 用户选屏」（`clusterDisplayControl`） |
| **仪表卡片几何** | 多张卡共用转向卡一份 x/y/size，媒体卡与转向卡**永远重叠** | 每张卡一套独立几何（`addCardGeometrySliders`） |
| **音频分区** | 提示音与导航语音走同一路 → 打字咔哒声从主驾导航喇叭出来 | 提示音声道独立（`systemAudioRoutingControls`），导航语音走 HAL 自主分区实现主驾独占 |
| **车辆数据** | 依赖 BYD 专有广播 | VHAL 反射 + logcat 解析**双路融合**（`VehicleProbe`），逐字段择优 |

---

## 主要特性

### 仪表屏投图

四张卡各自独立配置，位置/大小/透明度互不干扰：

- **转向卡** —— 箭头、距离、路名，带滞回防抖（`ClusterGuidanceStabilizer`）
- **媒体卡** —— 歌名 / 歌手 / 专辑三行；三种版式（横幅 / 竖版 1:1.35 / 自由）；过长文本走马灯滚动
- **通话卡** —— 来电号码与通话时长（安全信息，默认开）
- **路口放大图** —— 由 iPhone 的 `0x5202` 路口几何 + `0x5204` 车道数据**自行渲染**（CarPlay 不发成品图）

**卡片几何按仪表内容的实际宽高计算**，不再按 Presentation 窗口。宽高百分比基数是**屏幕高度**而非宽度 —— 1920×720 超宽屏上用宽度做基数会算出 1000+px 宽的卡，一张卡横跨半屏。

设置页有**位置预览**，未连接 iPhone 也能看到卡片摆在哪（预览与实车共用 `ClusterTurnCardOverlay` 几何，所见即所得）。

### 媒体卡三行语义

第三行是**专辑名**，不是第二个歌手 —— iAP2 `MediaItemAttributes` 只有 `title(1)` / `album(6)` / `artist(12)`，没有第二个artist 字段。

数据链路：`ClusterSong.album` → `CarPlayGlance.Snapshot` → `CarPlayHostActivity` → `ClusterMapPresentation` → `ClusterMediaCardView`。换歌时 title 变空会同时清歌手和专辑。

### 主驾专声音频路由

CarPlay 把导航语音与手机 UI 提示音放在**同一路音频流**上。DiPlay 让导航语音走 `usage` 让车机 HAL 自主分区，实现主驾独占；提示音单独一路，不抢导航喇叭。

### 车辆数据回传

电量 / 续航 / 车速 / 挡位回传到手机 CarPlay 地图。

**双路融合**：VHAL 反射（`CarCabinVehicleStatus`）与 logcat 解析（`VehicleLogTap`）并行取数，逐字段择优 —— VHAL 是结构化的但字段覆盖不全，logcat 反之。任何一路不可用时另一路顶上。

---

## 构建

### 环境要求

| 项 | 版本 |
|---|---|
| Android SDK | compileSdk 37|
| minSdk / targetSdk | 28 / 37 |
| JDK | 17+ |
| Gradle | **9.8.0**（见下方踩坑） |

### 签��

签名密钥**不入库**，通过环境变量传入：

```bash
export ANDROID_KEYSTORE_PATH="<路径>/platform.jks"
export ANDROID_KEYSTORE_PASSWORD=<口令>
export ANDROID_KEY_ALIAS=platform
export ANDROID_KEY_PASSWORD=<口令>
```

### 构建命令

```bash
./gradlew assembleRelease     # 产物在 mobile/build/outputs/apk/release/
```

交付的是 **`mobile-release.apk`**（不是 `automotive-release.apk`）。

### 踩坑记录

**① gradle wrapper 无法下载**
`gradle/wrapper/gradle-wrapper.properties` 指向 `gradle-9.5.0-bin.zip`，下载超时（`.part` 仅 512KB）。这是**基线既有问题**，不是本分支引入。绕过：

```bash
~/.gradle/wrapper/dists/gradle-9.8.0-bin/3m7h6ceboy5k31n8kzwzuxssm/gradle-9.8.0/bin/gradle assembleRelease
```

**② `--offline` 构建失败**
`No cached version of androidx.appcompat:appcompat:1.8.0`。`assembleRelease` 必须联网。

**③ 仓库是浅克隆时推送失败**
```
remote: fatal: did not receive expected object <sha>
```
`.git/shallow` 截断了历史，GitHub 收不了不完整的对象链。**极易误判成权限或网络问题**。修复：

```bash
git fetch --unshallow origin
```

**④ Windows 下凭据助手路径带空格会被截断**
```bash
# ❌ git 报 "C:/Program: No such file or directory"
git config credential.helper "C:/Program Files/Git/ucrt64/bin/git-credential-manager.exe"

# ✅ 用 shell 函数包一层
git config credential.helper '!f() { "C:/Program Files/Git/ucrt64/bin/git-credential-manager.exe" "$@"; }; f'
```

---

## 架构

```
shared/     跨模块核心：投图几何 ClusterTurnCardOverlay、音频路由、
            仪表数据 BydClusterSong / ClusterLaneGuidance
common/     Android UI：设置页 DiPlayActivity、四张卡的 View、
            ConnectedMenuDock（双指下滑菜单）、车辆数据 VHAL 桥接
mobile/     应用模块（applicationId = com.oushang.carplay）
```

包名与上游 DiPlay 不同（`com.oushang.carplay`），**可以与原版共存**。

---

## 设置界面结构

设置页按「驾驶员的目标」分组，不是按实现分组。新增或移动设置项时必须同步 `SettingsInformationArchitecture`，否则 `SettingsLayoutPolicyTest` 会失败（分区必须恰好属于一个分类）。

分类：`连接` / `显示` / `音频` / `导航` / `仪表` / `车辆` / `诊断` / `高级`

### 双指下滑菜单

- 底部页脚已**彻底取消**（原5 个常驻按钮吃掉近 300px），全部改为左侧目录的操作项
- 新增**「常用设置」分区**：把设置页里免重连的项按显示 / 仪表 / 音频 / 系统四类**复制**一份（不是搬家 —— 搬走未连接时反而没处调）

---

## 测试

```bash
./gradlew :shared:test :common:testDebugUnitTest
```

**已知环境类失败**（与本分支无关，基线同样失败）：

| 测试 | 数量 | 原因 |
|---|---|---|
| `UsbPermissionSetupTest` | 3 | Windows `sh -c: line 7: syntax error` |
| `AirPlayCryptoChachaTest` + `AirPlayCryptoAndroidCompatibilityTest` | 8 | 需真机 Android crypto provider |
| `HotspotJoinNormalizedTransactionTest` | 3 | 需 Android SDK |

---

## 致谢与许可

- 上游：[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（基于 [shilapi/xcertplay](https://github.com/shilapi/xcertplay)，GPL-3.0）
- 界面风格改编自 [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0）
- CarPlay 及其图标属于 Apple Inc.；本项目与 Apple 或比亚迪无任何从属或背书关系

分发修改版时请保留上述声明。签名密钥、配件身份资产**均不在仓库内**。