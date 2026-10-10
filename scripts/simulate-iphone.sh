#!/usr/bin/env bash
# 在模拟器上模拟 iPhone 连接(仅 debug 变体)。
#
#   ./scripts/simulate-iphone.sh boot          # 起模拟器 + 装 debug APK
#   ./scripts/simulate-iphone.sh route         # 发一条转向指令
#   ./scripts/simulate-iphone.sh media         # 发一条媒体信息
#   ./scripts/simulate-iphone.sh heartbeat     # 1s 周期重发(切主题/重建后要重跑)
#   ./scripts/simulate-iphone.sh cluster       # 仪表叠加层(目视卡片)
#   ./scripts/simulate-iphone.sh theme-shots   # 2 个仪表主题各拍一张
#   ./scripts/simulate-iphone.sh map           # 生成并推送模拟地图图(1920x720)
#   ./scripts/simulate-iphone.sh cover         # 生成并推送模拟封面图(512x512)
#   ./scripts/simulate-iphone.sh show          # 看当前仪表总线快照
#   ./scripts/simulate-iphone.sh shot out.png  # 截图
#   ./scripts/simulate-iphone.sh vdisp start   # 起 1920x720 虚拟仪表屏(scrcpy,前台)
#   ./scripts/simulate-iphone.sh vdisp show    # 叠加层开到虚拟屏并截图
#   ./scripts/simulate-iphone.sh prefs list    # 看设备上生效的仪表相关偏好
#   ./scripts/simulate-iphone.sh prefs clear-turn-card   # 清尺寸 key,回退新默认值
#   ./scripts/simulate-iphone.sh stop          # 停止心跳
#
# 原理:SimulatedIphone 直接把 iAP2 帧喂进 CarPlayGlance —— 和真实链路
# 走的是同一个入口(CarPlayController.onRouteFrame)。所以仪表卡片的渲染
# 路径是真的,只有 P2P 握手和 MFi 认证是假的。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="$ROOT/.tools/android-sdk"
ADB="$SDK/platform-tools/adb.exe"
# 合成地图图用纯 Python 生成(无 PIL 依赖),解释器路径可被PYTHON 覆盖。
PY="${PYTHON:-$(ls /c/Users/zjlcw/.workbuddy/binaries/python/versions/*/python.exe 2>/dev/null | sort -V | tail -1)}"
EMU="$SDK/emulator/emulator.exe"
AVD="${AVD_NAME:-b561_test}"
# debug 变体 applicationId 带 .hudtest 后缀,见 mobile/build.gradle.kts
PKG="com.oushang.carplay.hudtest"
APK="$ROOT/src/mobile/build/outputs/apk/debug/mobile-debug.apk"

# adb pull 的路径会被 MSYS 改写,截图必须走 /data/local/tmp + 禁掉转换
export MSYS_NO_PATHCONV=1

# ★ 默认锁 emulator-5556。这台机器上同时挂着一台 Android 12 的 Redmi 设备
#   (192.168.31.20:5555),裸 `adb shell` 在多设备时会打到它上面。
#   debug 包 applicationId 带 .hudtest 后缀,不会覆盖任何正式包,用户已确认
#   该设备可作为测试靶机,所以用 SERIAL= 显式切换即可,不再硬性拦模拟器。
SERIAL="${SERIAL:-emulator-5556}"
adb_() { "$ADB" -s "$SERIAL" "$@"; }

# 只做提示,不再阻断。仍然把设备身份打出来,避免"以为在模拟器上其实打到了别的机器"。
report_device() {
  local model brand sdk
  model="$(adb_ shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  brand="$(adb_ shell getprop ro.product.brand 2>/dev/null | tr -d '\r')"
  sdk="$(adb_ shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  local qemu
  qemu="$(adb_ shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
  echo ">> 目标 $SERIAL  model=$model brand=$brand sdk=$sdk qemu=${qemu:-0}"
}

# 只打印设备身份,不再阻断。原先这里硬性要求 ro.kernel.qemu=1,现在允许
# 任意 Android 设备作为靶机(叠加层 + 帧注入在真机上同样能跑)。
require_emulator() { report_device; }

# 自我调用用绝对路径。子命令(theme-shots)会重新执行本脚本,而用户可能在
# 任意 cwd 下调用(例如仓库根),`./simulate-iphone.sh` 解析不到 —— 实测症状是
# theme-shots 静默只跑第一轮就退出,因为 set -e 撞上 "No such file"。
SELF="$ROOT/scripts/simulate-iphone.sh"

# 应用私有目录 —— 模拟器上的素材(地图图/封面)必须放这里。
APP_DIR="/data/data/$PKG/files"

# 把一张本地图片推进应用私有目录,设备侧文件名固定为 $1。
# ★ 为什么要这一层而不是直接 push 到目标目录:
#   1. adb push 落地后属主是 shell(/data/local/tmp 是 shell:shell 771),
#      app 进程读会 Permission denied → decodeFile 返回 null,
#      症状是"传了图但没显示",极易误判成绘制 bug。实测在 map 上踩过。
#   2. /sdcard 更早被分区存储挡住(app 读自己域外的文件要 MANAGE_EXTERNAL_STORAGE)。
#   所以固定走 /data/local/tmp 暂存 + run-as cp 进沙箱(debug 变体才可 run-as)。
#
# ★ 本地源必须传**纯文件名**并先 cd 过去:脚本顶部全局 export 了
#   MSYS_NO_PATHCONV=1(为了保护远端 /data/local/tmp 不被改写),adb.exe 是
#   Windows 原生程序,拿到的 /c/Users/... 这种 POSIX 路径无法 stat ——
#   症状是 "adb: error: cannot stat '/c/Users/...'"。用 cygpath -m 转成
#   C:\... 也能过,但 cd + 纯文件名对中文/空格路径同样安全,不需要额外转换。
push_asset() {
  local dev_name="$1" local_src="$2"
  local src abs
  abs="$(cd "$(dirname "$local_src")" && pwd)/$(basename "$local_src")"
  [ -f "$abs" ] || abs="$local_src"
  src="$abs"
  local dev_path="/data/local/tmp/$dev_name"
  (cd "$(dirname "$src")" && adb_ -s "$SERIAL" push \
    "$(basename "$src")" "$dev_path" >/dev/null)
  adb_ -s "$SERIAL" shell \
    "run-as $PKG mkdir -p files && run-as $PKG cp $dev_path files/$dev_name" \
    >/dev/null 2>&1 || {
      echo "!! 复制到应用沙箱失败,run-as 需要 debuggable 构建(debug 变体才有)"
      exit 1
    }
}

cmd="${1:-show}"

# 除 boot/show 外的每个动作都先打设备身份 —— 多设备时一眼看出打到了哪台。
case "$cmd" in
  boot|show) ;;
  *) require_emulator ;;
esac

case "$cmd" in
  boot)
    if ! "$ADB" devices | grep -q emulator-; then
      echo ">> 启动模拟器 $AVD (无头)"
      "$EMU" -avd "$AVD" -no-window -gpu swiftshader_indirect -no-boot-anim \
        >/dev/null 2>&1 &
      "$ADB" wait-for-device
      # 等 boot_completed 真正为 1,否则 install 会失败
      for _ in $(seq 1 120); do
        [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 2
      done
    fi
    echo ">> 安装 debug APK"
    # ★ adb 是 Windows 原生程序,MSYS 会把 /c/Users/... 原样传过去,报
    #   "failed to stat /c/Users/...: No such file or directory"。
    #   MSYS_NO_PATHCONV=1 在这里帮不上忙(它连自动转换都关掉了),只能显式转。
    adb_ install -r -g "$(cygpath -m "$APK")"
    echo ">> 启动 $PKG/com.shilapi.xcertplay.DiPlayActivity"
    adb_ shell am start -n "$PKG/com.shilapi.xcertplay.DiPlayActivity" | tr -d '\r'
    ;;

  connect)
    adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.CONNECT | tr -d '\r'
    ;;

  route)
    # maneuver: 1=直行 2=右转 4=左转 7=稍右 8=稍左 32=并入右 40=环岛 45=到达
    adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.ROUTE \
      --ei maneuver "${MANEUVER:-2}" --ei side "${SIDE:-1}" \
      --ei distance "${DISTANCE:-350}" --es road "${ROAD:-长安路}" | tr -d '\r'
    ;;

  media)
    adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.NOW_PLAYING \
      --es title "${TITLE:-Numb}" --es artist "${ARTIST:-Linkin Park}" \
      --ez playing "${PLAYING:-true}" | tr -d '\r'
    ;;

  heartbeat)
    adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.HEARTBEAT \
      --ei maneuver "${MANEUVER:-2}" --ei side "${SIDE:-1}" \
      --ei distance "${DISTANCE:-350}" --es road "${ROAD:-长安路}" \
      --el period "${PERIOD:-1000}" | tr -d '\r'
    ;;

  arrived)
    adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.ARRIVED | tr -d '\r'
    ;;

  stop)
    adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.DISCONNECT | tr -d '\r'
    ;;

  cluster)
    # 启动仪表叠加层。没有真实副屏时用它把卡片渲染到主屏上目视验证。
    # 复用真实的 ClusterTurnCardView / ClusterMediaCardView,所以卡片绘制是真的,
    # 只有地图流没有(模拟器上没 iPhone 推流)。
    # 默认 fit=1:内部按 1920x720 真实坐标布局后等比缩放到屏幕内,
    # 这样手机屏上也能看全整个仪表流(1:1 的话右边 840px 在屏幕外)。
    # CLUSTER_THEME: MAP / CARDS,默认 CARDS。
    # CLUSTER_MAP: 设备侧地图图路径(必须 1920x720)。不设=只显示卡片。
    #   设了会套用与生产 TextureView 相同的 plan 裁切矩阵,所以卡片和地图对得上。
    # CLUSTER_ARTWORK: 设备侧封面图路径(正方形即可,会裁成方图)。不设=媒体卡画 ♪ 底板。
    #   走 --es artwork,与生产的 CarPlayMediaKeys 封面通道同一入口。
    # 需要 SYSTEM_ALERT_WINDOW:boot 已 grant 过的机型可跳过。
    #
    # ★ Activity 是 singleTask:不先 force-stop 的话 am start 只会走 onNewIntent,
    #   onCreate 不重跑,换 CLUSTER_THEME 截图会和上一次一模一样(实测踩过)。
    #   需要连续拍多个主题时用 CLUSTER_FORCE=1。
    if [ -n "$CLUSTER_FORCE" ]; then
      adb_ shell am force-stop "$PKG" >/dev/null 2>&1
      sleep 1
      # ★ 注入顺序有讲究:force-stop 把进程掐了,进程刚起时立刻广播会丢 ——
      #   实测 CONNECT/NOW_PLAYING 都在进程起来前发出,结果 title=null,
      #   媒体卡整张不显示(它靠 title 非空才 VISIBLE)。
      #   正确顺序:先把 Activity 拉起来 → 等进程稳→ 再注数据。
      adb_ shell am start -n "$PKG/com.shilapi.xcertplay.DiPlayActivity" >/dev/null 2>&1
      sleep 2
      adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.CONNECT >/dev/null 2>&1
      adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.NOW_PLAYING \
        --es title "${TITLE:-Numb}" --es artist "${ARTIST:-LinkinPark}" \
        --ez playing true >/dev/null 2>&1
      adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.HEARTBEAT \
        --ei maneuver "${MANEUVER:-2}" --ei side "${SIDE:-1}" \
        --ei distance "${DISTANCE:-350}" --es road "${ROAD:-长安路}" \
        --el period "${PERIOD:-1000}" >/dev/null 2>&1
      sleep 1
    fi
    adb_ shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
    adb_ shell am start -n "$PKG/com.shilapi.xcertplay.sim.SimulatedClusterActivity" \
      ${CLUSTER_W:+--ei width "$CLUSTER_W"} ${CLUSTER_H:+--ei height "$CLUSTER_H"} \
      ${CLUSTER_THEME:+--es theme "$CLUSTER_THEME"} \
      ${CLUSTER_FIT:+--ei fit "$CLUSTER_FIT"} \
      ${CLUSTER_MAP:+--es map "$CLUSTER_MAP"} \
      ${CLUSTER_ARTWORK:+--es artwork "$CLUSTER_ARTWORK"} | tr -d '\r'
    ;;

  theme-shots)
    # 逐个主题拍一张,用来目视对比仪表布局。
    # 用法:./scripts/simulate-iphone.sh theme-shots [输出目录]
    # 每个主题都是冷启动(force-stop)后再注入数据,否则 singleTask 不重跑 onCreate。
    # CLUSTER_MAP 非空时带上模拟地图图(默认不带,只显示卡片)。
    #
    # 主题只有 MAP / CARDS 两个 —— 原先的 SCENARIO/SIMPLE/FLOATING 已删。
    #   MAP   :地图铺满仪表,转向卡悬浮其上
    #   CARDS :左导航卡 / 中地图 / 右媒体卡三栏并排,无底部条
    out="${2:-theme-shots}"
    mkdir -p "$out"
    for t in MAP CARDS; do
      adb_ logcat -c >/dev/null 2>&1
      CLUSTER_FORCE=1 CLUSTER_THEME="$t" "$SELF" cluster >/dev/null 2>&1
      sleep 2
      "$SELF" shot "/data/local/tmp/theme-$t.png" >/dev/null 2>&1
      mv -f "theme-$t.png" "$out/$t.png" 2>/dev/null
      # 实测几何,不要靠读 LayoutParams 推断:子视图可能被容器强行测量。
      # CARDS 期望打印 turn/media 两栏的 slots 行(钉死区域);
      # MAP 期望打印 plan=... fullMap=true。
      echo ">> $t  $(adb_ logcat -d | grep -iE 'cards slots|overlay cluster' | tail -1 \
        | sed 's/.*SimClusterOverlay//' | tr -d '\r')"
    done
    echo ">> 截图已存 $(pwd)/$out/"
    echo ">> 校验总线快照(确认每张截图都有完整数据,而不是渲染了空卡片)"
    "$SELF" dump 2>&1 | tail -1
    ;;

  map)
    # 生成一张 1920x720 的合成导航图并推到设备,供 cluster --es map 使用。
    # 为什么要合成图而不是截图/照片:裁切矩阵以编码流坐标(1920x720)算,
    # 尺寸不对卡片就会错位。夜间配色是为了和 dark=true 时的浅字深底卡片对比。
    #
    # ★ 必须推到**应用私有目录**,不能放 /sdcard:adb push 落地后属主是
    #   media_rw /权限 660,app 进程读会 Permission denied → decodeFile 返回
    #   null(表现为"传了图但没显示",很容易误判成绘制 bug)。实测踩过。
    local_src="${2:-$ROOT/scripts/sim-map.png}"
    if [ ! -f "$local_src" ]; then
      # ★ 必须 cygpath -m:$ROOT 是 MSYS 路径(/c/Users/...),Windows 原生 python.exe
      #   收到会当成 C:\c\Users\... 找不到文件。装 APK 时踩过同一个坑。
      "$PY" "$(cygpath -m "$ROOT")/scripts/make-sim-map.py" "$(cygpath -m "$local_src")"
    fi
    push_asset "sim-map.png" "$local_src"
    echo ">> 地图就绪:$APP_DIR/sim-map.png(源 $(basename "$local_src"))"
    echo ">> 用法:CLUSTER_MAP=$APP_DIR/sim-map.png ./simulate-iphone.sh cluster"
    ;;

  cover)
    # 生成一张 512x512 的合成专辑封面并推到设备,供 cluster --es artwork 使用。
    # 封面走的是 CSM(NowPlayingArtworkQueue)通道,和 iAP2 帧无关 ——
    # 所以这里只能整张图喂进去,模拟不了真实解码路径。
    # 尺寸不限正方形,媒体卡会居中裁成方图;但正方形最省事,避免裁掉主体。
    local_src="${2:-$ROOT/scripts/sim-cover.png}"
    if [ ! -f "$local_src" ]; then
      "$PY" "$(cygpath -m "$ROOT")/scripts/make-sim-cover.py" "$(cygpath -m "$local_src")"
    fi
    push_asset "sim-cover.png" "$local_src"
    echo ">> 封面就绪:$APP_DIR/sim-cover.png(源 $(basename "$local_src"))"
    echo ">> 用法:CLUSTER_ARTWORK=$APP_DIR/sim-cover.png ./simulate-iphone.sh cluster"
    ;;

  dump)
    # 读回总线快照。这是判断「帧有没有真的被解析对」最直接的手段 ——
    # 虽然 cluster 子命令已经把卡片渲染到屏幕上了,但 dump 给出的是解析后的
    # 结构化字段(距离/路名/剩余时间/歌名/歌手),能逐字段核对。
    adb_ logcat -c
    adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.DUMP >/dev/null
    sleep 1
    adb_ logcat -d | grep -i "SimulateIphone" | grep -v System.err | tail -3 | tr -d '\r'
    ;;

  show)
    # ClusterMapPresentation.attachGlance 每次 attach 都会打这条
    adb_ logcat -d | grep -i "ClusterMapPresentation" | tail -5 | tr -d '\r'
    echo "--- 注入日志 ---"
    adb_ logcat -d | grep -i "SimulateIphone" | tail -20 | tr -d '\r'
    ;;

  shot)
    # 参数既可以是远端路径(/data/local/tmp/x.png),也可以是本地文件名(shot.png)。
    # ★ MSYS 会把 `/data/local/tmp/...` 这种单斜杠参数当成本地路径改写,
    #   adb pull 会报 "failed to stat remote object 'C:/Program Files/.../data/local/tmp/...'"。
    #   MSYS2_ARG_CONV_EXCL="*" 必须在**这条命令上**设,设成全局 export 无效
    #   (Git Bash 已在进程启动时定好了转换规则)。
    arg="${2:-/data/local/tmp/sim.png}"
    case "$arg" in
      /*) remote="$arg"; local_name="$(basename "$arg")" ;;
      *)  remote="/data/local/tmp/$arg"; local_name="$arg" ;;
    esac
    # DISPLAY_ID 非空时抓那一块屏(scrcpy --new-display 建的虚拟仪表屏)。
    # screencap 的 -d 收**物理 display id**,用 `dumpsys display` 里的
    # displayId(Android 7+ 是逻辑 id);scrcpy 的虚拟屏逻辑 id 通常是 2 或 3。
    if [ -n "$DISPLAY_ID" ]; then
      adb_ shell screencap -p -d "$DISPLAY_ID" "$remote"
    else
      adb_ shell screencap -p "$remote"
    fi
    # 拉回:cd 到当前目录用相对文件名,让 adb 自己在本地落地,避免路径转换。
    (cd "$(pwd)" && MSYS2_ARG_CONV_EXCL='*' adb_ -s "$SERIAL" pull "$remote" "$local_name" >/dev/null)
    echo ">> 已保存 $(pwd)/$local_name"
    ;;

  vdisp)
    # 在设备上建一块 1920x720 的虚拟屏当仪表,并把叠加层开上去。
    # 为什么要它:手机横屏虽然也能跑出 scale=1.0,但那是**借用主屏**;
    # 虚拟屏是与实车仪表同分辨率的独立显示器,互不干扰,更像真车。
    #
    # 用法:
    #   ./simulate-iphone.sh vdisp start    # 起 scrcpy 虚拟屏(前台,Ctrl-C 结束)
    #   ./simulate-iphone.sh vdisp show     # 叠加层开到虚拟屏并截图
    #   ./simulate-iphone.sh vdisp stop
    #
    # ★ 必须 --render-driver=software:scrcpy 5.0 默认走 EGL/GPU,
    #   在无独显的机器上会 "Could not load EGL library" 直接退出。
    #   注意虚拟屏随 scrcpy 进程退出而被销毁,所以 start 要保持前台运行。
    SCRCPY="$ROOT/.tools/scrcpy/scrcpy-win64-v5.0/scrcpy.exe"
    [ -x "$SCRCPY" ] || SCRCPY="$ROOT/.tools/scrcpy/scrcpy-win64/scrcpy.exe"
    case "${2:-show}" in
      start)
        [ -f "$SCRCPY" ] || { echo "!! 没找到 scrcpy:期望 $ROOT/.tools/scrcpy/scrcpy-win64-v5.0/"; exit 1; }
        echo ">> 起虚拟仪表屏 1920x720/160(软件渲染),窗口标题 B561-仪表虚拟屏"
        echo ">> 保持这个窗口开着,关掉虚拟屏就没了。Ctrl-C 结束。"
        MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$SCRCPY" -s "$SERIAL" \
          --new-display=1920x720/160 --render-driver=software --no-audio \
          --window-title="B561-仪表虚拟屏"
        ;;
      show)
        # displayId 从 dumpsys 里取 scrcpy 那块(名字是 "scrcpy")。
        did="$(adb_ shell dumpsys display 2>/dev/null | tr -d '\r' \
          | grep -o 'DisplayInfo{"scrcpy", displayId [0-9]*' | grep -o '[0-9]*$' | head -1)"
        if [ -z "$did" ]; then
          echo "!! 没找到 scrcpy 虚拟屏,先跑:./simulate-iphone.sh vdisp start"
          exit 1
        fi
        echo ">> 虚拟屏 displayId=$did"
        adb_ shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
        # 冷启动:Activity 是 singleTask,不 force-stop 则 onCreate 不重跑。
        adb_ shell am force-stop "$PKG" >/dev/null 2>&1
        sleep 1
        adb_ shell am start -n "$PKG/com.shilapi.xcertplay.DiPlayActivity" >/dev/null 2>&1
        sleep 2
        # ★ 顺序:先拉起进程 → 等稳 → 再注数据,否则广播早于进程起来会丢。
        adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.CONNECT >/dev/null 2>&1
        adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.NOW_PLAYING \
          --es title "${TITLE:-Numb}" --es artist "${ARTIST:-LinkinPark}" \
          --ez playing true >/dev/null 2>&1
        adb_ shell am broadcast -p "$PKG" -a com.shilapi.xcertplay.sim.HEARTBEAT \
          --ei maneuver "${MANEUVER:-2}" --ei side "${SIDE:-1}" \
          --ei distance "${DISTANCE:-350}" --es road "${ROAD:-长安路}" \
          --el period "${PERIOD:-1000}" >/dev/null 2>&1
        sleep 1
        # ★ MSYS2_ARG_CONV_EXCL 必设,否则 /data/data/... 会被改写成
        #   C:\...\data\data\...,am start 会报 "Activity class does not exist"。
        MSYS2_ARG_CONV_EXCL='*' adb_ shell am start --display "$did" \
          -n "$PKG/com.shilapi.xcertplay.sim.SimulatedClusterActivity" \
          ${CLUSTER_THEME:+--es theme "$CLUSTER_THEME"} \
          ${CLUSTER_MAP:+--es map "$CLUSTER_MAP"} \
          ${CLUSTER_ARTWORK:+--es artwork "$CLUSTER_ARTWORK"} >/dev/null 2>&1
        sleep 2
        adb_ logcat -d | grep -i SimClusterOverlay | tail -2 | tr -d '\r'
        out="${3:-vdisp.png}"
        (cd "$(pwd)" && MSYS2_ARG_CONV_EXCL='*' adb_ -s "$SERIAL" shell \
          "screencap -p -d $did /data/local/tmp/$out" >/dev/null \
          && MSYS2_ARG_CONV_EXCL='*' adb_ -s "$SERIAL" pull \
          "/data/local/tmp/$out" "$out" >/dev/null)
        echo ">> 已保存 $(pwd)/$out"
        ;;
      stop)
        # scrcpy 没有「关虚拟屏」的命令 —— 杀进程即可,虚拟屏随之销毁。
        taskkill //IM scrcpy.exe //F >/dev/null 2>&1 || true
        echo ">> scrcpy 已结束,虚拟屏销毁"
        ;;
      *) echo "用法:vdisp start|show|stop"; exit 1 ;;
    esac
    ;;

  prefs)
    # 查看 / 改 / 清 AirPlayPersistence 的 SharedPreferences。
    #
    # 为什么要它:改了 kts 里的 DEFAULT_* 常量后,**设备上不会自动生效** ——
    # loadXxx() 优先读已存的 key,只有 key 不存在才回退到默认值。所以
    # 改完默认值装机截图,看到的还是旧值(实测把悬浮卡默认从 55 改成 38,
    # 截图仍是 883px 的旧横幅)。这个子命令就是绕开"必须先手动进设置页
    # 拨一下滑条"的绕路。
    #
    # 用法:
    #   prefs list                       # 打印全部相关 key
    #   prefs get <key>
    #   prefs set <key> <value>          # int / bool / string 自动判别
    #   prefs clear-turn-card            # 只删悬浮卡尺寸相关 key,回退到新默认值
    #
    # 走 run-as 而不是 adb root:debug 变体是 debuggable 的,run-as 拿得到
    # 私有目录权限,且不需要 root。release 变体不可用(正常,那是给用户测的)。
    prefs_file() { echo "/data/data/$PKG/shared_prefs/xcertplay_airplay.xml"; }
    _prefs_cat() { adb_ exec-out "run-as $PKG cat $(prefs_file)" 2>/dev/null | tr -d '\r'; }
    # 删完 prefs 文件要让进程重建,否则内存里的旧值还在。
    _prefs_restart() { adb_ shell am force-stop "$PKG" >/dev/null 2>&1 || true; }
    case "${2:-list}" in
      list)
        # 只看 Cluster/仪表相关 —— 全量有几十个 key,噪声太大。
        _prefs_cat | grep -oE '<(boolean|int|long|string) name="cluster[^/]*' \
          | sed 's/.*name="/  /;s/"$//' || echo "  (没有 cluster_* key)"
        ;;
      get)
        [ -n "${3:-}" ] || { echo "!! 用法:prefs get <key>"; exit 1; }
        _prefs_cat | grep -oE "<[a-z]+ name=\"$3\"[^>]*>[^<]*<" \
          | sed 's/^/  /' || echo "  (未设置:$3)"
        ;;
      set)
        [ -n "${3:-}" ] && [ -n "${4:-}" ] || { echo "!! 用法:prefs set <key> <value>"; exit 1; }
        k="$3"; v="$4"
        case "$v" in
          true|false)
            xml="<boolean name=\"$k\" value=\"$v\" />" ;;
          ''|*[!0-9-]*)
            xml="<string name=\"$k\">$v</string>" ;;
          *)
            xml="<int name=\"$k\" value=\"$v\" />" ;;
        esac
        # 直接改 XML 是最快的,但 app 运行中改会被内存副本覆盖,所以先停进程。
        _prefs_restart
        cur="$(_prefs_cat)"
        if [ -z "$cur" ]; then
          echo "!! 读不到 $(prefs_file)。确认 SERIAL 指向装了 debug 包的设备(SERIAL=$SERIAL)"
          echo "   release 变体不是 debuggable,run-as 会失败 —— 这是预期行为。"
          exit 1
        fi
        # 删掉同名旧行,再插到 </map> 前。sed -i 走设备侧,MSYS 路径不参与。
        { echo "$cur" | sed -E "s#<[a-z]+ name=\"$k\"[^>]*>[^<]*<#\#&#; s#<[a-z]+ name=\"$k\"[^>]*/>#\#&#g" \
          | sed "s#</map>#    $xml\n</map>#"; } \
          | adb_ exec-out "run-as $PKG sh -c 'cat > $(prefs_file)'" >/dev/null 2>&1 \
          || adb_ push /dev/stdin "$(prefs_file)" >/dev/null 2>&1 || true
        echo ">> 已写 $k=$v(必要时重启 app 生效)"
        ;;
      clear-turn-card)
        _prefs_restart
        cur="$(_prefs_cat)"
        # 删掉所有 turn card 尺寸 key,让 loadClusterTurnCardOverlaySizePercent()
        # 回退到新的 DEFAULT_SIZE_PERCENT。旧版本写过 cluster_turn_card_overlay_size
        # 这个 string key(SMALL/LARGE),也要一起删,否则会走 legacy 分支。
        { echo "$cur" \
          | sed -E 's#<[a-z]+ name="cluster_turn_card_overlay_size_percent"[^>]*/>##g; s#<[a-z]+ name="cluster_turn_card_overlay_size_percent"[^>]*>[^<]*<##g; s#<[a-z]+ name="cluster_turn_card_overlay_size"[^>]*>[^<]*<##g'; } \
          | adb_ exec-out "run-as $PKG sh -c 'cat > $(prefs_file)'" >/dev/null 2>&1 || true
        echo ">> 已清 turn card 尺寸 key,下次启动回退到默认值"
        ;;
      *) echo "用法:prefs list|get <key>|set <key> <value>|clear-turn-card"; exit 1 ;;
    esac
    ;;

  *)
    sed -n '2,20p' "$0"
    exit 1
    ;;
esac
