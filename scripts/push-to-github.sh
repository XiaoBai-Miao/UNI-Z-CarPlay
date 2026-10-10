#!/usr/bin/env bash
# 一键推送到 GitHub 新仓库 UNI-Z CarPlay
#
# 为什么需要这个脚本：
#   - GitHub 连接器只有读权限（create_repository 返回 403 Resource not accessible by integration）
#   - 本机没装 gh CLI
#   - 所以「建仓库 + 首次 push」这一步必须由你本人交互完成（要输密码/令牌）
#
# 前置条件：
#   1. 分支 b561-adapt-v69 已提交（f76b740），本地无需再改
#   2. 远端 uni-z-carplay 已指向 https://github.com/XiaoBai-Miao/UNI-Z-CarPlay.git
#
# 用法：
#   bash scripts/push-to-github.sh
#
# 脚本会在需要时提示你输入 GitHub 用户名与 Personal Access Token
# （密码框不接收 GitHub 账号密码，必须用令牌；令牌勾选 repo 权限即可）

set -euo pipefail

REPO_URL="https://github.com/XiaoBai-Miao/UNI-Z-CarPlay.git"
REMOTE="uni-z-carplay"
BRANCH="b561-adapt-v69"
REPO_NAME="UNI-Z CarPlay"
REPO_DESC="UNI-Z CarPlay — DiPlay 0.2.14 基础上适配长安欧尚 / 比亚迪 B561 车机（Renesas R-Car，Android 9，1920x910）。含仪表屏投图、主驾专声音频路由、车辆数据 VHAL+logcat 双路回传、双指下滑快捷设置。"

cd "$(dirname "$0")/.."

echo "==> 仓库: $REPO_NAME"
echo "==> 目标: $REPO_URL"
echo

# ── 第1 步：建仓库（只在远端不存在时需要） ──
echo "==> 检查远端仓库是否存在..."
if git ls-remote --exit-code "$REPO_URL" >/dev/null 2>&1; then
    echo "    远端已存在，跳过创建。"
else
    echo "    远端不存在。"
    echo
    echo "    请在浏览器打开下面这个地址点「Create」，或用 API 创建："
    echo
    echo "      https://github.com/new?name=UNI-Z-CarPlay&description=$(python -c "
import urllib.parse,sys
print(urllib.parse.quote(sys.argv[1]))" "$REPO_DESC" 2>/dev/null || echo UNI-Z%20CarPlay)"
    echo
    echo "    创建时选Public（公开），然后按任意键继续..."
    read -r _
fi
echo

# ── 第 2 步：推送 ──
echo "==> 推送到 $REMOTE / $BRANCH"
echo "    （GitHub 已不支持账号密码，密码处请粘贴 Personal Access Token）"
echo
git push -u "$REMOTE" "$BRANCH"

echo
echo "==> 完成：https://github.com/XiaoBai-Miao/UNI-Z-CarPlay/tree/$BRANCH"