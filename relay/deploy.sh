#!/bin/bash
# 把转发 jar 上传到面板服务器 (SFTP)。由用户在自己的终端里运行，密码在提示时手动输入，脚本不读取、不保存密码。
# 用法: ./deploy.sh        (先在 relay/ 下 ./gradlew build)
set -euo pipefail

ENV_FILE="${RELAY_ENV:-$(cd "$(dirname "$0")" && pwd)/../../.config/ssh/relayserver.env}" # 含 ip/port/user(和密码) 的文件，放在项目外；可用环境变量 RELAY_ENV 指定
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/server/build/libs/minecraft-relay.jar"
UPD="$DIR/ls/update"

[ -f "$JAR" ] || { echo "找不到 $JAR，请先在 relay/ 下运行 ./gradlew build"; exit 1; }
[ -f "$ENV_FILE" ] || { echo "找不到连接信息文件 $ENV_FILE"; exit 1; }

# 只取地址、端口、用户名 (不碰 password 行)
get() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2- || true; }
ip=$(get ip); [ -n "$ip" ] || ip=$(get stfp); [ -n "$ip" ] || ip=$(get sftp)  # 地址的键名可能是 ip / stfp / sftp
port=$(get port); user=$(get user)
[ -n "$ip" ] || { echo "env 文件里没找到地址 (ip= / stfp= / sftp=)"; exit 1; }

echo "即将上传 $(basename "$JAR") ($(du -h "$JAR" | cut -f1)) 到 $user@$ip:$port ，按提示输入密码。"
echo "注意: 不要删除服务器上的 relay-data 文件夹；上传后需要在面板里手动重启实例。"
sftp -P "$port" "$user@$ip" <<SFTP
put "$JAR" minecraft-relay.jar
-mkdir relay-data
-mkdir relay-data/ls
-mkdir relay-data/ls/update
$( [ -f "$UPD/mod.jar" ] && echo "put \"$UPD/mod.jar\" relay-data/ls/update/mod.jar
put \"$UPD/latest.txt\" relay-data/ls/update/latest.txt" )
bye
SFTP
echo "模组更新包 (relay-data/ls/update) 上传后立即生效，不用重启；只有转发 jar 变了才需要重启。"
echo "上传完成。请到面板重启实例 (启动命令: java -jar minecraft-relay.jar 25570 80)。"
