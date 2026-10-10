#!/usr/bin/env bash
# 删掉 API 建的仓库，改用「Fork 上游」重建，让仓库带 fork 徽章。
#
# 为什么脚本不自己删：
#   GCM 存的是**细粒度 token**（scopes: gist, repo, workflow），
#   没有 delete_repo 权限，DELETE /repos 返回 403
#   "Must have admin rights to Repository." —— 只能本人去网页点。
#
# 为什么值得重建：
#   API 建仓不会让 GitHub 关联父仓库，fork: false、页面无 fork 徽章；
#   而用 Fork 按钮建出来的是**真fork**，能显示 "forked from shihabal3amri/DiPlay"，
#   且能直接开 PR 回上游。
#
# 用法：
#   步骤1（网页）：删掉 https://github.com/XiaoBai-Miao/UNI-Z-CarPlay
#   步骤2（网页）：打开 https://github.com/shihabal3amri/DiPlay/fork 点 Fork
#   步骤3（本脚本）：把定制分支推上去

set -euo pipefail

REMOTE="uni-z-carplay"
BRANCH="b561-adapt-v69"
UPSTREAM_FORK="https://github.com/XiaoBai-Miao/UNI-Z-CarPlay.git"

cd "$(dirname "$0")/.."

echo "==> 本地提交（将被推送）:"
git log --oneline -3
echo
echo "==> 目标: $UPSTREAM_FORK  分支: $BRANCH"
echo

if git ls-remote --exit-code "$UPSTREAM_FORK" >/dev/null 2>&1; then
    echo "    远端已存在 —— 如果你刚Fork 完，这正常。"
else
    echo "    ✗ 远端不存在。请先在网页完成步骤 1 和 2。"
    exit 1
fi

echo "==> 推送（Fork 出来的仓库默认分支通常是 main/master，"
echo "    而我们推的是 b561-adapt-v69，互不影响）"
echo
git push -u "$REMOTE" "$BRANCH"

echo
echo "==> 完成。页面刷新后应能看到 'forked from shihabal3amri/DiPlay'。"
echo "==> 提醒：GitHub 有时需要几分钟才渲染 fork 徽章，刷新或稍后再看。"
