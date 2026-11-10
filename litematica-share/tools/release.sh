#!/bin/bash
# 发布新版本: 构建模组 → 签名 → 生成 relay/update/litematica_share/ (mod.jar + latest.txt)。
# 用法: tools/release.sh ["更新说明"]   (版本号取自 gradle.properties 的 mod_version；先改版本号和 CHANGELOG.md)
# 之后运行 relay/deploy.sh 上传。私钥在项目外 (见 UPDATE_SIGNING_KEY)；公钥写进 local.properties 的 update_public_key。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="${UPDATE_SIGNING_KEY:-$ROOT/../../.config/update/signing.key}" # 签名私钥放在项目外；可用环境变量 UPDATE_SIGNING_KEY 指定
[ -f "$KEY" ] || { echo "找不到签名私钥 $KEY"; exit 1; }
VERSION=$(grep -E '^mod_version=' "$ROOT/gradle.properties" | cut -d= -f2)
(cd "$ROOT" && ./gradlew build -q)
JAR="$ROOT/mod/build/libs/litematica-share-$VERSION.jar"
[ -f "$JAR" ] || { echo "找不到 $JAR"; exit 1; }
# 泄露检查: local.properties 里的 leak_pattern (正则，写服务器域名/端口等)；模组 jar 里不允许出现
PATTERN=$(grep -E '^leak_pattern=' "$ROOT/local.properties" 2>/dev/null | head -1 | cut -d= -f2- || true)
if [ -n "$PATTERN" ] && grep -aqE "$PATTERN" "$JAR"; then echo "模组 jar 含服务器信息，拒绝发布"; exit 1; fi
java "$ROOT/tools/SignUpdate.java" sign "$KEY" litematica_share "$VERSION" "$JAR" "$ROOT/../relay/ls/update" "${1:-}"
