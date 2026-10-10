#!/usr/bin/env bash
# 跑测试。与 build-oushang.sh 共用同一套工具链探测。
#
#   ./scripts/test.sh              # 三个关键测试类(B561 适配相关,应全绿)
#   ./scripts/test.sh --all-shared # shared 模块全量(含 8 个已知历史失败)
#
# ★ 必须 --no-daemon:实测 daemon 模式下 :common:testDebugUnitTest 会挂死
#   (CPU 0%、测试结果文件一个都不生成)。构建用 daemon、测试用单进程,两者都实测过。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJ="$ROOT/src"

# 工具链探测与 build-oushang.sh 保持一致(跨平台)。直接复用同一段逻辑,避免两处漂移。
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) PLAT="windows" ;;
  Darwin*)                PLAT="macos" ;;
  *)                      PLAT="linux" ;;
esac
EXT=""; [ "$PLAT" = "windows" ] && EXT=".exe"
# ★ Gradle 在 Windows 上是 .bat,发行包里没有 gradle.exe
GEXT=""; [ "$PLAT" = "windows" ] && GEXT=".bat"

JDK=""
for c in "${JAVA_HOME:-}" "$ROOT"/.tools/jdk25 /opt/jdk25 \
         "/c/Program Files/Java/jdk-25"* "/c/Program Files/Java/jdk-2"* \
         "/c/Program Files/Eclipse Adoptium/jdk-25"* /usr/lib/jvm/*; do
  [ -x "$c/bin/javac$EXT" ] || continue
  v="$("$c/bin/javac$EXT" -version 2>&1 | sed -n 's/javac \([0-9]*\).*/\1/p')" || continue
  if [ "${v:-0}" -ge 25 ]; then JDK="$c"; break; fi
done
[ -n "$JDK" ] || { echo "找不到 JDK 25,先跑 scripts/fetch-deps.sh" >&2; exit 1; }
export JAVA_HOME="$JDK"

SDK=""
for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$ROOT"/.tools/android-sdk /opt/android-sdk \
         "$HOME/Android/Sdk" "$HOME/AppData/Local/Android/Sdk" "/c/Android/Sdk"; do
  [ -n "$c" ] && [ -d "$c/platforms" ] && { SDK="$c"; break; }
done
[ -n "$SDK" ] || { echo "找不到 Android SDK,先跑 scripts/fetch-deps.sh" >&2; exit 1; }
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

GRADLE=""
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
for c in "${GRADLE_BIN:-}" "$ROOT"/.tools/gradle-*/bin/gradle /opt/gradle/gradle-*/bin/gradle \
         "$HOME"/gradle-*/bin/gradle; do
  if [ ! -f "$c$GEXT" ] && [ ! -x "$c" ]; then continue; fi
  v="$(gradle_version "$c")"
  case "${v:-}" in 9.[5-9]*|[1-9][0-9]*) GRADLE="$c$GEXT"; break;; esac
done
[ -n "$GRADLE" ] || GRADLE="$PROJ/gradlew"
[ -f "$GRADLE" ] || [ -f "$GRADLE$GEXT" ] || [ -x "$GRADLE" ] \
  || { echo "找不到 Gradle ≥9.5.0,先跑 scripts/fetch-deps.sh" >&2; exit 1; }

# gradle.bat / gradlew.bat 不能在 bash 里直接 exec,要经 cmd //c 且用 Windows 路径
if [ "$PLAT" = "windows" ]; then
  case "$GRADLE" in
    *.bat|*/gradlew) GEXEC="$(cygpath -w "$GRADLE")" ;;
    *)               GEXEC="$(cygpath -w "$GRADLE$GEXT")" ;;
  esac
  gradle_run() { cmd //c "$GEXEC" "$@"; }
else
  gradle_run() { "$GRADLE" "$@"; }
fi

# local.properties 按实际 SDK 重写;Windows 上必须是 Windows 绝对路径(反斜杠)
if [ "$PLAT" = "windows" ]; then
  printf 'sdk.dir=%s\n' "$(cygpath -m "$SDK")" > "$PROJ/local.properties"
else
  printf 'sdk.dir=%s\n' "$SDK" > "$PROJ/local.properties"
fi

echo ">>> JDK    $JDK"
echo ">>> SDK    $SDK"
echo ">>> Gradle $GRADLE"

cd "$PROJ"
if [ "${1:-}" = "--all-shared" ]; then
  echo ">>> shared 全量(预期 8 个历史失败,见 docs/测试现状.md)"
  exec gradle_run --console=plain --no-daemon :shared:testDebugUnitTest
fi

echo ">>> B561 适配相关测试(预期全绿)"
gradle_run --console=plain --no-daemon \
  :common:testDebugUnitTest \
    --tests "*ClusterTurnCardPersistenceTest*" \
    --tests "*ClusterMapPresentationTest*" \
    --tests "*SimulatedIphoneTest*" \
  :shared:testDebugUnitTest \
    --tests "*ClusterTurnCardOverlayTest*"

echo
echo ">>> 结果"
for f in \
  "$PROJ/common/build/test-results/testDebugUnitTest/TEST-com.shilapi.xcertplay.ClusterMapPresentationTest.xml" \
  "$PROJ/common/build/test-results/testDebugUnitTest/TEST-com.shilapi.xcertplay.ClusterTurnCardPersistenceTest.xml" \
  "$PROJ/common/build/test-results/testDebugUnitTest/TEST-com.shilapi.xcertplay.sim.SimulatedIphoneTest.xml" \
  "$PROJ/shared/build/test-results/testDebugUnitTest/TEST-com.shilapi.xcertplay.airplay.ClusterTurnCardOverlayTest.xml"
do
  if [ -f "$f" ]; then
    printf '  %-38s %s\n' \
      "$(basename "$f" .xml | sed 's/TEST-com\.shilapi\.xcertplay\.//')" \
      "$(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f" | head -1)"
  fi
done
