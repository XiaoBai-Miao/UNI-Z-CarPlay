#!/usr/bin/env bash
# 一键构建 B561 定制版 CarPlay(平台签名 + MFi 身份资产全部自动带好)。
#
#   ./scripts/build-oushang.sh                # 构建 release
#   ./scripts/build-oushang.sh :shared:testDebugUnitTest   # 跑别的任务
#   CLEAN_DAEMONS=1 ./scripts/build-oushang.sh # 先杀掉残留 daemon 再构建
#
# 产物:src/mobile/build/outputs/apk/release/mobile-release.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# ★ 0.2.14 的Gradle 根在仓库根目录(0.2.13 工程把它包在 src/ 下)。
#   这里不写死,两种布局都能用。
if [ -f "$ROOT/settings.gradle.kts" ]; then PROJ="$ROOT"; else PROJ="$ROOT/src"; fi
TASKS=("$@"); [ ${#TASKS[@]} -eq 0 ] && TASKS=(":mobile:assembleRelease")

# ---------------------------------------------------------------- 平台识别
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) PLAT="windows" ;;
  Darwin*)                PLAT="macos" ;;
  *)                      PLAT="linux" ;;
esac
# 可执行文件后缀。注意:Gradle 在 Windows 上是 **gradle.bat**(不是 .exe —— 发行包里
# 只有无扩展名的 unix 启动脚本 gradle + gradle.bat),而 javac 是 .exe。两者不能混用。
EXT=""; [ "$PLAT" = "windows" ] && EXT=".exe"
GEXT=""; [ "$PLAT" = "windows" ] && GEXT=".bat"

# ---------------------------------------------------------------- 工具链探测
pick_jdk() {
  local best="" bestv=0 c v
  # 注意 Windows 路径要加引号 —— "C:/Program Files/..." 里有空格
  for c in "${JAVA_HOME:-}" "$ROOT"/.tools/jdk25 /opt/jdk25 /usr/lib/jvm/* \
           "/c/Program Files/Java/jdk-25"* "/c/Program Files/Java/jdk-2"* \
           "/c/Program Files/Eclipse Adoptium/jdk-25"*; do
    [ -x "$c/bin/javac$EXT" ] || continue
    # B561 工程用 AGP 9.3.0，JDK 27(2026-09) 比 AGP 9.3 新，Kotlin/Gradle 工具链
    # 可能不认其 class file 版本 → 强制停在 JDK 25(LTS，AGP 9.x 支持区间)。
    case "$c" in *jdk-27*|*jdk-2[89]*|*jdk-3[0-9]*) continue;; esac
    v="$("$c/bin/javac$EXT" -version 2>&1 | sed -n 's/javac \([0-9]*\).*/\1/p')" || continue
    if [ "${v:-0}" -gt "$bestv" ]; then bestv="${v:-0}"; best="$c"; fi
  done
  echo "$best"
}
pick_sdk() {
  for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$ROOT"/.tools/android-sdk /opt/android-sdk \
           "$HOME/Android/Sdk" "$HOME/AppData/Local/Android/Sdk" "/c/Android/Sdk"; do
    [ -n "$c" ] && [ -d "$c/platforms" ] && { echo "$c"; return; }
  done
  return 1
}
# Gradle 版本探测:.bat 要经 cmd 才能跑,且输出是 CRLF
gradle_version() {
  local g="$1" v
  if [ -f "$g$GEXT" ] && [ "$GEXT" = ".bat" ]; then
    v="$(cmd //c "$(cygpath -w "$g$GEXT")" --version 2>/dev/null | tr -d '\r' \
         | sed -n 's/^Gradle \([0-9.]*\).*/\1/p' | head -1)"
  else
    v="$("$g" --version 2>/dev/null | sed -n 's/^Gradle \([0-9.]*\).*/\1/p' | head -1)"
  fi
  echo "$v"
}
pick_gradle() {
  local c v
  for c in "${GRADLE_BIN:-}" "$ROOT"/.tools/gradle-*/bin/gradle /opt/gradle/gradle-*/bin/gradle \
           "$HOME"/gradle-*/bin/gradle; do
    if [ ! -f "$c$GEXT" ] && [ ! -x "$c" ]; then continue; fi
    v="$(gradle_version "$c")"
    case "${v:-}" in 9.[5-9]*|[1-9][0-9]*) echo "$c$GEXT"; return;; esac
  done
  # 最后退回 wrapper(需要联网下载 Gradle 9.8.0 —— 注意官方源很慢,见 fetch-deps.sh)
  [ -f "$PROJ/gradlew" ] && echo "$PROJ/gradlew"
}

JDK="$(pick_jdk)"; [ -n "$JDK" ] || { echo "找不到 JDK,先跑 scripts/fetch-deps.sh" >&2; exit 1; }
SDK="$(pick_sdk)"  || { echo "找不到 Android SDK,先跑 scripts/fetch-deps.sh" >&2; exit 1; }
GRADLE="$(pick_gradle)" || { echo "找不到 Gradle ≥9.5.0,先跑 scripts/fetch-deps.sh" >&2; exit 1; }

export JAVA_HOME="$JDK" ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# local.properties 是本机路径,不能从包里带死 —— 每次构建按实际 SDK 重写。
# ★ Windows 上必须写 Windows 绝对路径(反斜杠),Gradle 不认 /c/... 这种 MSYS 路径 —— 否则
#   报 "SDK location not found"。
if [ "$PLAT" = "windows" ]; then
  SDK_PROP="$(cygpath -m "$SDK")"
else
  SDK_PROP="$SDK"
fi
printf 'sdk.dir=%s\n' "$SDK_PROP" > "$PROJ/local.properties"

# 签名:MFi 认证要 AOSP 平台私钥签出来的包,自签名会让 Wi-Fi P2P 失效(API 28 上必须是系统应用身份)
export ANDROID_KEYSTORE_PATH="$ROOT/keys/b561-platform.p12"
export ANDROID_KEYSTORE_PASSWORD=android
export ANDROID_KEY_ALIAS=androiddebugkey
export ANDROID_KEY_PASSWORD=android
# 注意:指向 auth_assets 本身,构建脚本内部会自己拼 offline-mfi/。指到 offline-mfi 会报
# "Explicit local authentication assets are incomplete"
export DIPLAY_AUTH_ASSETS_DIR="$ROOT/mfi-auth/auth_assets"

echo ">>> JDK    $JDK"
echo ">>> SDK    $SDK"
echo ">>> Gradle $GRADLE"
echo ">>> 签名   keys/b561-platform.p12 (平台密钥)"
echo ">>> 任务   ${TASKS[*]}"

# gradle.bat / gradlew.bat 不能在 bash 里直接 exec(会被当成 ELF),要经 cmd //c 且用
# Windows 绝对路径。Gradle 收到 MSYS 风格的 /c/... 路径会解析失败。
# ★ 必须定义在使用点之前(bash 里函数在首次调用时才求值)。
gradle_run() {
  local w
  if [ "$PLAT" = "windows" ]; then
    # GRADLE 可能已带 .bat（探测阶段拼过）或以 gradlew 结尾（wrapper 无扩展名）。
    # 统一在这里只补一次，别拼成 gradle.bat.bat。
    case "$GRADLE" in
      *.bat|*/gradlew) w="$(cygpath -w "$GRADLE")" ;;
      *)               w="$(cygpath -w "$GRADLE$GEXT")" ;;
    esac
    cmd //c "$w" "$@"
  else
    "$GRADLE" "$@"
  fi
}

# 多 daemon 抢 ~/.gradle 缓存锁会让构建永久挂起(CPU 0%)。需要时用 CLEAN_DAEMONS=1 清理。
if [ "${CLEAN_DAEMONS:-0}" = "1" ]; then
  echo ">>> 清理残留 daemon"
  # ★ 用 Gradle 自己的 --stop,不要用 jps + taskkill —— jps 会把当前 shell 的
  #   进程树一起列出来,粗粒度 kill 会连带杀掉本次构建,表现为"打完日志立刻退出且无报错"。
  gradle_run --stop >/dev/null 2>&1 || true
  sleep 1
fi

cd "$PROJ"
# 注意:**跑测试任务请用 scripts/test.sh**(单进程模式)。daemon 模式下
# :common:testDebugUnitTest 可能挂死(CPU 0%、测试结果文件一个都不生成)。
#
# ★ --max-workers=1:AGP 的 configureNdkBuildRelease 会为 arm64-v8a / x86_64 /
#   armeabi-v7a 三个 ABI 并发写**同一个** build/.cxx/<variant>/<hash>/hash_key.txt,
#   在 Windows 上偶发 FileNotFoundException(...hash_key.txt 拒绝访问)。目录本身可写,
#   是并发竞态。串行后稳定通过(实测 125 任务 BUILD SUCCESSFUL)。
#   可以用 GRADLE_EXTRA_OPTS 覆盖。
gradle_run --console=plain ${GRADLE_EXTRA_OPTS:---max-workers=1} "${TASKS[@]}"

APK="$PROJ/mobile/build/outputs/apk/release/mobile-release.apk"
[ -f "$APK" ] || exit 0

# 产物校验用的 aapt2 / apksigner 在 Windows 上是 .bat(要经 cmd 才能跑),
# 批处理还会把 stdout 混进 CRLF 噪音 —— 统一包一层并过滤。
run_bt() {
  local tool="$1"; shift
  if [ -f "$tool.bat" ]; then
    cmd //c "$(cygpath -w "$tool")" "$@" 2>&1 | tr -d '\r'
  else
    "$tool" "$@" 2>&1
  fi
}

echo
echo "===== 产物 ====="
ls -l "$APK"
sha256sum "$APK"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
if [ -n "$BT" ] && [ -x "$BT/aapt2" -o -f "$BT/aapt2.bat" ]; then
  run_bt "$BT/aapt2" dump badging "$APK" | grep -E "^package" || true
  if [ -x "$BT/apksigner" -o -f "$BT/apksigner.bat" ]; then
    run_bt "$BT/apksigner" verify --print-certs "$APK" | grep -E "SHA-256 digest" || true
  fi
else
  echo "(未找到 build-tools,跳过包信息校验)"
fi
echo "签名应为 c8a2e9bc…51192ab8(AOSP 平台公钥);对不上就说明用错密钥了,Wi-Fi P2P 会失效。"
