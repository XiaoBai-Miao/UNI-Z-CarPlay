#!/usr/bin/env bash
# 环境自检:看这台机器能不能直接构建本项目。缺什么会明确告诉你,而不是让你撞一堵墙。
set -uo pipefail

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0

head_ "源码"
[ -f "$ROOT/src/settings.gradle.kts" ] && ok "src/ 就位($(du -sh "$ROOT/src" | cut -f1))" || { bad "src/ 缺失"; fail=1; }

head_ "JDK(实测需 25,低版本会报 toolchain 下载失败)"
java_ver=""
for c in "${JAVA_HOME:-}" /opt/jdk25 /usr/lib/jvm/*; do
  [ -x "$c/bin/javac" ] || continue
  v="$("$c/bin/javac" -version 2>&1 | sed -n 's/javac \([0-9]*\).*/\1/p')"
  if [ -z "$java_ver" ] || [ "$v" -gt "$java_ver" ]; then java_ver="$v"; JAVA_CANDIDATE="$c"; fi
done
if [ -n "$java_ver" ]; then
  if [ "$java_ver" -ge 25 ]; then ok "JDK $java_ver @ $JAVA_CANDIDATE"; else warn "只找到 JDK $java_ver(< 25),构建可能失败 → $ROOT/scripts/fetch-deps.sh"; fi
else
  bad "没找到 JDK → $ROOT/scripts/fetch-deps.sh"; fail=1
fi

head_ "Android SDK"
sdk=""
for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android-sdk "$HOME/Android/Sdk"; do
  [ -n "$c" ] && [ -d "$c/platforms" ] && { sdk="$c"; break; }
done
if [ -n "$sdk" ]; then
  ok "SDK @ $sdk"
  [ -d "$sdk/platforms/android-37.0" ] || warn "缺 platform android-37.0(compileSdk=37)"
  comp="$(ls "$sdk/build-tools" 2>/dev/null | sort -V | tail -1)"
  [ -n "$comp" ] && ok "build-tools $comp" || warn "缺 build-tools"
else
  bad "没找到 Android SDK → $ROOT/scripts/fetch-deps.sh"; fail=1
fi

head_ "Gradle(AGP 9.3.0 要求 ≥ 9.5.0)"
g=""
for c in "${GRADLE_BIN:-}" /opt/gradle/gradle-*/bin/gradle "$HOME"/gradle-*/bin/gradle; do
  [ -x "$c" ] || continue
  v="$("$c" --version 2>/dev/null | sed -n 's/^Gradle \([0-9.]*\).*/\1/p' | head -1)"
  [ -n "$v" ] || continue
  case "$v" in 9.[5-9]*|9.[1-9][0-9]*|[1-9][0-9]*) g="$c"; gv="$v"; break;; esac
done
if [ -n "$g" ]; then ok "Gradle $gv @ $g"; else bad "没有 ≥9.5.0 的 Gradle → $ROOT/scripts/fetch-deps.sh(或直接用 ./gradlew,需联网)"; fail=1; fi

head_ "签名与身份资产"
[ -f "$ROOT/keys/b561-platform.p12" ] && ok "平台密钥 keys/b561-platform.p12" || { bad "缺平台密钥(自签名会让 Wi-Fi P2P 失效)"; fail=1; }
[ -f "$ROOT/mfi-auth/auth_assets/offline-mfi/identity.pk8" ] && ok "MFi 身份资产 mfi-auth/auth_assets/offline-mfi/" || { bad "缺 MFi 身份资产"; fail=1; }

head_ "结论"
if [ "$fail" -eq 0 ]; then
  echo "  环境就绪,直接跑:"
  echo "    $ROOT/scripts/build-oushang.sh"
else
  echo "  有缺失项,先跑:"
  echo "    $ROOT/scripts/fetch-deps.sh"
fi
exit 0
