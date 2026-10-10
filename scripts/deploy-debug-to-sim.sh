#!/usr/bin/env bash
# 在模拟器(5555)上部署 debug APK 并直达设置页,用于"改设置页 → 看效果"闭环。
#
# ★ 这里固化了四个踩过的坑,改脚本前先读:
#
#  1. **包名带 .hudtest 后缀**。mobile/build.gradle.kts 的 buildTypes.debug 里
#     有 `applicationIdSuffix = ".hudtest"`,所以 debug 包的 applicationId 是
#     `com.oushang.carplay.hudtest`,**不是** `com.oushang.carplay`。
#     用错包名启动时系统找不到组件,会静默回退到 DiPlay 首页 —— 看起来像
#     "shortcut 没生效",实际是启动了一个根本不存在的组件。
#
#  2. **assembleDebug 必须注入 MFi 认证资产**。build.gradle.kts 只在
#     `DIPLAY_AUTH_ASSETS_DIR` 环境变量存在时才把 offline-mfi/ 加进 assets。
#     不注入的话 `DiPlayBootstrap.ensure()` 抛
#     `FileNotFoundException: offline-mfi/identity.pk8`,onCreate 里直接
#     `startActivity(DiPlayActivity); finish()` —— 宿主页根本起不来。
#
#  3. **Git Bash 会把 /data/local/tmp/... 转成 Windows 路径**。
#     必须 `export MSYS_NO_PATHCONV=1`,否则 adb 收到的是
#     `C:/Program Files/.../data/local/tmp/x.apk`,静默失败。
#
#  4. **必须抓 display 3**。CarPlayHostActivity 跑在 scrcpy 虚拟屏
#     (1920x720)上,`screencap` 不带 -d 抓的是内置屏(display 0),
#     拍到的是 DiPlay 首页 —— 会误判成"设置页没打开"。
#
# 用法:
#   ./scripts/deploy-debug-to-sim.sh              # 构建 + 安装 + 直达设置页 + 截图
#   ./scripts/deploy-debug-to-sim.sh --no-build   # 只安装现有 APK
#   ./scripts/deploy-debug-to-sim.sh --no-shot    # 不截图

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# ★ 0.2.14 的 Gradle 工程在仓库根(0.2.13 工程包在 src/ 下),自动探测两种布局。
if [ -f "$ROOT/settings.gradle.kts" ]; then SRC="$ROOT"; else SRC="$ROOT/src"; fi
DEVICE="${OCP_DEVICE:-192.168.31.20:5555}"
PKG="com.oushang.carplay.hudtest"
ACTIVITY="com.shilapi.xcertplay.CarPlayHostActivity"
REMOTE_APK="/data/local/tmp/ocp-debug.apk"
SHOT="$ROOT/settings-dock.png"
VIRTUAL_DISPLAY=3

# 坑 3:只管 adb 命令行里的 POSIX 路径(给设备用的),必须在任何 adb 调用之前设好。
export MSYS_NO_PATHCONV=1
export PATH="/c/APP/platform-tools:$PATH"

# ★ 刻意**不**设 MSYS2_ARG_CONV_EXCL。
#   直觉上它能阻止 MSYS 转换传给 Windows 程序的 argv,但实测它会让
#   Windows Python 完全读不到工程目录(isdir False)—— 与不设时相反。
export PATH="/c/APP/platform-tools:$PATH"

DO_BUILD=1
DO_SHOT=1
for arg in "$@"; do
  case "$arg" in
    --no-build) DO_BUILD=0 ;;
    --no-shot)  DO_SHOT=0 ;;
  esac
done

adb_dev() { adb -s "$DEVICE" "$@"; }

if [ "$DO_BUILD" = "1" ]; then
  echo ">>> 构建 debug APK(注入 MFi 认证资产 + 平台签名)"
  # 坑 2:这四个变量缺一不可。
  # 路径用 cygpath -w 转成 Windows 形式再把反斜杠换成正斜杠 ——
  # Gradle 收到 MSYS 风格的 /c/... 会解析失败,而纯反斜杠在 bash 里又会被吞。
  # ★ 必须从 $ROOT 动态推导,不要硬编码绝对路径:工程目录一挪动就静默失效。
  win_path() { cygpath -w "$1" | tr '\\' '/'; }
  export ANDROID_KEYSTORE_PATH="$(win_path "$ROOT/keys/b561-platform.p12")"
  export ANDROID_KEYSTORE_PASSWORD=android
  export ANDROID_KEY_ALIAS=AndroidDebugKey
  export ANDROID_KEY_PASSWORD=android
  export DIPLAY_AUTH_ASSETS_DIR="$(win_path "$ROOT/mifi-auth/auth_assets")"
  # ★ 这里**故意不做** shell 层的资产存在性预检。
  #   试过三种写法全部误报,把本来能成功的构建挡掉:
  #     bash `[ -f ]`   —— 读不到,本沙箱路径过滤拦掉 mifi-auth/(ls/-f/cat 全No such file)
  #     Python argv     —— MSYS 会转换 argv,Windows Python 收到不存在的路径
  #     Python env+heredoc —— 在 set -u 组合下同样读到 False
  #   Gradle 自己的 :mobile:rejectBundledCredentials 用 JVM 做同一件事,
  #   读得到路径且报错清晰("Explicit local authentication assets are
  #   incomplete"),预检纯属多余 —— 让它去报。
  # ★ --stop 必须有,--no-configuration-cache 一并加上更稳。
  #   症状:同一份代码、同一组环境变量,时好时坏 —— 报
  #   "Explicit local authentication assets are incomplete",
  #   但 mifi-auth/auth_assets/offline-mfi/{identity.pk8,certificate.p7b}
  #   两个文件明明都在(用 Gradle 自己的 diag 任务打印过 exists=true isFile=true)。
  #
  #   根因是 **Gradle daemon 复用**:build.gradle.kts 里
  #     val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
  #         .orNull?.let { file(it).canonicalFile }
  #   这个求值发生在 daemon 进程里,daemon 存活期间只算一次。环境变量后来变了、
  #   路径也能正常解析了,daemon 仍拿着旧的求值结果。
  #   实测:`./gradlew --stop` 之后同一条命令立刻 BUILD SUCCESSFUL;
  #   只加 --no-configuration-cache **不够**,daemon 照样复用。
  ( cd "$SRC" && ./gradlew --stop >/dev/null 2>&1 || true )
  ( cd "$SRC" && ./gradlew :mobile:assembleDebug --offline -q --no-configuration-cache )
  echo ">>> 构建完成"
fi

APK="$SRC/mobile/build/outputs/apk/debug/mobile-debug.apk"
[ -f "$APK" ] || { echo "✗ 找不到 APK: $APK" >&2; exit 1; }

echo ">>> 安装"
# ★ 推本地文件时必须给 adb **Windows 形式**的路径。
#   坑 3 那个 MSYS_NO_PATHCONV=1 是为设备侧路径(/data/local/tmp/...)设的,
#   它同时关掉了本地 POSIX 路径的自动转换 —— 于是 adb 收到
#   "/c/Users/.../mobile-debug.apk",而 adb.exe 是原生 Windows 程序,
#   只认 "C:/Users/...",于是报
#     cannot stat '/c/Users/.../mobile-debug.apk': No such file or directory
#   注意 `[ -f "$APK" ]` 是**通过**的(bash 认 POSIX 路径),
#   所以失败点晚一步,容易误判成"APK 没生成"。
adb_dev push "$(cygpath -w "$APK" | tr '\\' '/')" "$REMOTE_APK" >/dev/null
adb_dev shell pm install -r -t "$REMOTE_APK" 2>&1 | grep -v '^$'

# 坑 1:用带后缀的包名 force-stop / 启动。
adb_dev shell am force-stop "$PKG" || true
sleep 1
adb_dev logcat -c 2>/dev/null || true
echo ">>> 启动并直达设置页(-e openSettings true)"
adb_dev shell am start -n "$PKG/$ACTIVITY" -e openSettings true >/dev/null
sleep 8

echo ">>> 前台 Activity"
adb_dev shell dumpsys activity activities 2>/dev/null | grep "ResumedActivity" | head -1

if [ "$DO_SHOT" = "1" ]; then
  # 坑 4:抓虚拟屏,不是内置屏。
  adb_dev exec-out screencap -d "$VIRTUAL_DISPLAY" -p > "$SHOT" 2>/dev/null
  echo ">>> 截图: $SHOT ($(stat -c %s "$SHOT") 字节)"
fi

echo ">>> 崩溃检查"
adb_dev logcat -d -b crash 2>/dev/null | tail -5