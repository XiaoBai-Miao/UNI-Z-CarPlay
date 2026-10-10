#!/usr/bin/env bash
# 补齐缺失的构建工具链(JDK 25 / Android SDK / Gradle)。需要联网。
# 已经就位的组件会自动跳过,可以重复执行。
#
# 跨平台:按 uname 选择下载 URL 与本地探测路径(Windows / macOS / Linux)。
# 原版脚本硬编码 linux/x64 与 /opt/*,在 Windows 上会拉到无法执行的 Linux 二进制。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/.tools"
mkdir -p "$TOOLS"
say() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ---------------------------------------------------------------- 平台识别
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) PLAT="windows" ;;
  Darwin*)                PLAT="macos" ;;
  *)                      PLAT="linux" ;;
esac
case "$(uname -m)" in
  x86_64|amd64)  ARCH="x64" ;;
  aarch64|arm64) ARCH="aarch64" ;;
  *)             ARCH="x64" ;;
esac
# JDK/Gradle 可执行文件后缀。★ Gradle 在 Windows 上是 **.bat 不是 .exe**
# (发行包里只有无扩展名的 unix 脚本 gradle 与 gradle.bat);javac 才是 .exe。
EXT=""; [ "$PLAT" = "windows" ] && EXT=".exe"
GEXT=""; [ "$PLAT" = "windows" ] && GEXT=".bat"
echo ">>> 平台 $PLAT/$ARCH  (uname: $(uname -s) $(uname -m))"

# ---------------------------------------------------------------- JDK 25
say "JDK"
jdk_ok=0
JDK_DIR=""
for c in "${JAVA_HOME:-}" "$TOOLS"/jdk-25* /opt/jdk25 \
         "/c/Program Files/Java/jdk-25"* "/c/Program Files/Java/jdk-2"* \
         "/c/Program Files/Eclipse Adoptium/jdk-25"* "/usr/lib/jvm"/*; do
  [ -x "$c/bin/javac$EXT" ] || continue
  v="$("$c/bin/javac$EXT" -version 2>&1 | sed -n 's/javac \([0-9]*\).*/\1/p')" || continue
  if [ "${v:-0}" -ge 25 ]; then jdk_ok=1; JDK_DIR="$c"; echo "  已就绪:JDK $v @ $c"; break; fi
done
if [ "$jdk_ok" -eq 0 ]; then
  echo "  下载 Temurin JDK 25 ($PLAT/$ARCH) …"
  url="https://api.adoptium.net/v3/binary/latest/25/ga/$PLAT/$ARCH/jdk/hotspot/normal/eclipse"
  if [ "$PLAT" = "windows" ]; then
    curl -fL --retry 3 -o "$TOOLS/jdk25.zip" "$url"
    mkdir -p "$TOOLS/jdk25" "$TOOLS/jdkx" && unzip -q "$TOOLS/jdk25.zip" -d "$TOOLS/jdkx"
    # zip 顶层是 jdk-25.0.x+y/ 这一层,要剥掉
    inner="$(find "$TOOLS/jdkx" -mindepth 1 -maxdepth 1 -type d | head -1)"
    cp -r "$inner"/. "$TOOLS/jdk25"/ && rm -rf "$TOOLS/jdkx" "$TOOLS/jdk25.zip"
  else
    curl -fL --retry 3 -o "$TOOLS/jdk25.tar.gz" "$url"
    mkdir -p "$TOOLS/jdk25" && tar -xzf "$TOOLS/jdk25.tar.gz" -C "$TOOLS/jdk25" --strip-components=1
    rm -f "$TOOLS/jdk25.tar.gz"
  fi
  JDK_DIR="$TOOLS/jdk25"
  echo "  完成:$JDK_DIR"
fi

# ---------------------------------------------------------------- Android SDK
say "Android SDK"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] || [ ! -d "$sdk/platforms" ]; then
  # 优先复用任何已装好的 SDK,不重复下载
  for cand in "$TOOLS/android-sdk" "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk" \
              "/c/Android/Sdk" "/opt/android-sdk"; do
    if [ -d "$cand/platforms" ]; then sdk="$cand"; echo "  发现现成 SDK:$sdk"; break; fi
  done
fi
if [ -z "$sdk" ] || [ ! -d "$sdk/platforms" ]; then
  sdk="$TOOLS/android-sdk"
  # cmdline-tools 的文件名里带操作系统:win / mac / linux
  case "$PLAT" in
    windows) ct_os="win" ;;
    macos)   ct_os="mac" ;;
    *)       ct_os="linux" ;;
  esac
  if [ ! -d "$sdk/cmdline-tools/latest" ]; then
    echo "  下载 command line tools ($ct_os) …"
    curl -fL --retry 3 -o "$TOOLS/cmdline-tools.zip" \
      "https://dl.google.com/android/repository/commandlinetools-${ct_os}-13114758_latest.zip"
    mkdir -p "$sdk/cmdline-tools" && unzip -q "$TOOLS/cmdline-tools.zip" -d "$TOOLS/cmdt"
    mv "$TOOLS/cmdt/cmdline-tools" "$sdk/cmdline-tools/latest"
    rm -rf "$TOOLS/cmdt" "$TOOLS/cmdline-tools.zip"
  fi
fi
echo "  SDK 落在:$sdk"

export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
SDKM="$sdk/cmdline-tools/latest/bin/sdkmanager"
# cmdline-tools 的启动脚本自己去找 JAVA_HOME / PATH 上的 java。Windows 上如果
# JAVA_HOME 空、PATH 里也没有 java,它会报 "Java version 17 or higher is required"
# 然后什么都不装 —— 必须把上面挑中的 JDK 明确传进去。
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
if   [ -x "$SDKM" ]; then sdk_run() { "$SDKM" "$@"; }
elif [ -f "$SDKM.bat" ]; then sdk_run() { cmd //c "$(cygpath -w "$SDKM")" "$@"; }
else echo "  sdkmanager 不可用,请手动装 Android SDK"; exit 1; fi

echo "  接受 licenses …"
yes 2>/dev/null | sdk_run --sdk_root="$sdk" --licenses >/dev/null || true

need_pkgs=()
# ★ 包名带 `.0` 后缀:`platforms;android-37` 不存在,报 "Failed to find package"。
#   实际可用的当前是 android-37.0 / 37.1 / 37.2。目录名也是 platforms/android-37.0。
[ -d "$sdk/platforms/android-37.0" ] || need_pkgs+=("platforms;android-37.0")
[ -d "$sdk/build-tools/36.0.0" ]  || need_pkgs+=("build-tools;36.0.0")
[ -d "$sdk/platform-tools" ]         || need_pkgs+=("platform-tools")
# 模拟器实测需要 emulator + 一个 system image
if [ "${WITH_EMULATOR:-0}" = "1" ]; then
  [ -d "$sdk/emulator" ] || need_pkgs+=("emulator")
  if ! ls "$sdk"/system-images/*/* >/dev/null 2>&1; then
    need_pkgs+=("system-images;android-34;google_apis;x86_64")
  fi
fi
if [ ${#need_pkgs[@]} -gt 0 ]; then
  echo "  安装:${need_pkgs[*]}(这一步要下几个 GB,耐心等)"
  # 进度条走stderr,这里合并显示
  sdk_run --sdk_root="$sdk" "${need_pkgs[@]}" || {
    echo "  ⚠️ 37.0 平台可能仍在预览通道,失败时改用:build-oushang.sh SDK_PLATFORM_OVERRIDE"
    exit 1
  }
else
  echo "  所需 SDK 组件均已就绪"
fi

# ---------------------------------------------------------------- Gradle
say "Gradle"
GRADLE_VER=9.8.0
GRADLE_SHA=bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c
g=""
gradle_version() {
  local gg="$1" v
  if [ "$GEXT" = ".bat" ] && [ -f "$gg$GEXT" ]; then
    v="$(cmd //c "$(cygpath -w "$gg$GEXT")" --version 2>/dev/null | tr -d '\r' \
         | sed -n 's/^Gradle \([0-9.]*\).*/\1/p' | head -1)"
  else
    v="$("$gg" --version 2>/dev/null | sed -n 's/^Gradle \([0-9.]*\).*/\1/p' | head -1)"
  fi
  echo "$v"
}
for c in "$TOOLS"/gradle-*/bin/gradle /opt/gradle/gradle-*/bin/gradle "$HOME"/gradle-*/bin/gradle; do
  if [ ! -f "$c$GEXT" ] && [ ! -x "$c" ]; then continue; fi
  v="$(gradle_version "$c")"
  case "${v:-}" in 9.[5-9]*|[1-9][0-9]*) g="$c$GEXT"; echo "  已就绪:Gradle $v @ $c"; break;; esac
done
if [ -z "$g" ]; then
  if [ ! -f "$TOOLS/gradle-$GRADLE_VER/bin/gradle$GEXT" ]; then
    echo "  下载 Gradle $GRADLE_VER …"
    # ★ 镜像优先。实测 services.gradle.org 在本网络下只有 ~180 B/s(144MB 要十几小时),
    #   腾讯云镜像能到 ~20 MB/s。官方源保留为回退。
    ok=0
    for url in \
      "https://mirrors.cloud.tencent.com/gradle/gradle-$GRADLE_VER-bin.zip" \
      "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
    do
      echo "    源: $url"
      # -# 让进度条落到 stderr,不污染管道
      if curl -fL --retry 2 --connect-timeout 15 -o "$TOOLS/gradle.zip" "$url" 2>/dev/null; then
        ok=1; break
      fi
      echo "    失败,换下一个源"
    done
    [ "$ok" -eq 1 ] || { echo "  所有源都失败"; exit 1; }
    echo "$GRADLE_SHA  $TOOLS/gradle.zip" | sha256sum -c - || { echo "  校验失败,中止"; exit 1; }
    unzip -q "$TOOLS/gradle.zip" -d "$TOOLS" && rm -f "$TOOLS/gradle.zip"
  fi
  g="$TOOLS/gradle-$GRADLE_VER/bin/gradle$GEXT"
  echo "  完成:$g"
fi

printf '\n\033[32m全部就绪\033[0m。接下来:\n  %s/scripts/build-oushang.sh\n\n' "$ROOT"
