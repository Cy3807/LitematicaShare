# Litematica Share (LS)

多人共享 [Litematica](https://github.com/maruohon/litematica) 投影进度的 Fabric 客户端模组（Minecraft 1.21.11），以及它配套的自托管转发服务器。

- **`litematica-share/`** — Fabric 客户端模组。房间内共享同一个投影（文件、位置、旋转、镜像、子区域），团队材料统计、团队验证、共享箱子库存索引、房间子任务、自动更新。
- **`relay/`** — 独立的 Java 转发服务器 (`minecraft-relay.jar`)，不是 Fabric 模组。按通道转发数据（`litematica_share` 通道 + `mod_update` 自动更新通道），房间数据存在 `relay-data/`。

> 模组里**不内置任何服务器地址**：你需要自己部署转发服务器，玩家用 `/ls server <地址:端口>` 设置（只保存在本机配置里）。

## 构建

需要 Java 21。

```bash
cd relay && ./gradlew build              # → relay/server/build/libs/minecraft-relay.jar
cd litematica-share && ./gradlew build   # → litematica-share/mod/build/libs/litematica-share-<版本>.jar
```

运行时需要 Fabric Loader、Fabric API、Litematica 0.26.x、MaLiLib（版本见 `litematica-share/gradle.properties`）。

## 部署转发服务器

```bash
java -jar minecraft-relay.jar [端口,默认 25570] [下载总带宽上限 Mbps,默认 80]
```

控制台命令：`s` 落盘并重启（会用磁盘上最新的 jar），`stop` / `exit` 退出。房间数据在 jar 所在目录的 `relay-data/ls/rooms/`，**不要删除**。

## 自动更新（可选）

默认关闭（没有公钥就不启用）。想给自己的发行版启用：

1. 生成密钥对：`java litematica-share/tools/SignUpdate.java keygen <私钥文件>`（私钥放项目外，别提交），命令会打印公钥。
2. 在 `litematica-share/local.properties`（已在 `.gitignore` 里）写 `update_public_key=<公钥>`，之后构建出来的模组就会用这把公钥验证更新包。
3. 发版：改 `gradle.properties` 的 `mod_version`，运行 `litematica-share/tools/release.sh "更新说明"`（私钥路径用环境变量 `UPDATE_SIGNING_KEY` 指定），再把 `relay/ls/update/` 里的 `mod.jar` 和 `latest.txt` 放到服务器的 `relay-data/ls/update/`。更新包用私钥签名，模组验签通过才会替换；更新从玩家设置的那个转发服务器下载。
   `relay/deploy.sh` 可以用 SFTP 上传转发 jar 和更新包（地址/用户从 `RELAY_ENV` 指定的 env 文件读取，密码在提示时手动输入）。

## 许可

MIT，见 `LICENSE`。Litematica 和 MaLiLib 是 LGPL-3.0，本项目只针对它们的 API 编译，没有打包进模组。
