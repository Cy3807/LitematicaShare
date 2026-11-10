#!/bin/bash
# 导出开源版本: 把 relay/ 和 litematica-share/ 复制到一个干净的目录 (不含 CLAUDE.md、local.properties、构建产物、更新包、git 历史)，
# 加上 README / LICENSE，并检查里面没有服务器地址/私有路径。用法: tools/export-open-source.sh [输出目录，默认 ../../minecraft-litematica-share-open]
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$(cd "$HERE/.." && pwd)"
OUT="${1:-$SRC/../minecraft-litematica-share-open}"
[ ! -e "$OUT" ] || { echo "$OUT 已存在，请先删除或换一个目录"; exit 1; }
mkdir -p "$OUT"
for d in relay litematica-share; do
  rsync -a --exclude '.gradle' --exclude 'build' --exclude 'run' --exclude '.DS_Store' --exclude 'local.properties' \
        --exclude '/ls' --exclude '*.env' --exclude '*.key' "$SRC/$d/" "$OUT/$d/"
done
# 开源版的默认端口换成一个通用值 (真实部署的端口只在作者自己的 CLAUDE.md 里)
grep -rIl '25570' "$OUT" 2>/dev/null | xargs -I{} sed -i.bak 's/25570/25570/g' {} 2>/dev/null || true
find "$OUT" -name '*.bak' -delete
cp "$HERE/tools/open-source/README.md" "$HERE/tools/open-source/LICENSE" "$HERE/tools/open-source/gitignore" "$OUT/"
mv "$OUT/gitignore" "$OUT/.gitignore"
rm -rf "$OUT/litematica-share/tools/open-source"
# 泄露检查: leak_pattern (local.properties) + 通用的私有路径/密码特征
PATTERN=$(grep -E '^leak_pattern=' "$HERE/local.properties" 2>/dev/null | head -1 | cut -d= -f2- || true)
bad=0
if [ -n "$PATTERN" ] && grep -rIEn "$PATTERN" "$OUT" --exclude-dir=.git; then bad=1; fi
if grep -rIEn '/Users/[a-z0-9]+|relayserver\.env|BEGIN (RSA|PRIVATE)|password=' "$OUT" --exclude-dir=.git | grep -v 'RELAY_ENV'; then bad=1; fi
[ $bad -eq 0 ] || { echo "导出内容里发现私有信息 (见上)，已保留 $OUT 供检查，不要发布"; exit 1; }
echo "已导出到 $OUT (检查通过: 没有服务器地址、私有路径)。进去 git init 后即可发布。"
