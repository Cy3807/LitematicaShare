package share.relay.channels;

import share.protocol.ModUpdate;
import share.protocol.Packet;
import share.relay.Channel;
import share.relay.Connection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static share.protocol.ModUpdate.*;

/**
 * 模组自动更新: 提供 relay-data/&lt;模组文件夹&gt;/update/ 下的 latest.txt 和 mod.jar。
 * latest.txt 每行: 版本 / sha256 / 签名(base64) / 更新说明(可空)，由 tools/release.sh 生成并签名。
 * 每次请求都现读文件，上传新版本后不用重启转发服务。
 */
public final class UpdateChannel implements Channel {
    private static final long MAX_JAR = 32L * 1024 * 1024;
    /** 模组 id -> relay-data 下该模组的文件夹名 (没列出的用模组 id 本身)；更新包在 relay-data/&lt;文件夹&gt;/update/。 */
    private static final java.util.Map<String, String> FOLDERS = java.util.Map.of("litematica_share", "ls");
    private final Path dataDir;

    public UpdateChannel(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override public String id() { return CHANNEL; }

    @Override public int version() { return VERSION; }

    @Override
    public void handle(Connection conn) throws IOException {
        while (true) {
            Packet p = conn.read();
            if (p.type != CHECK && p.type != DOWNLOAD) continue;
            String modId = Packet.readStr(p.reader());
            if (!modId.matches("[a-z0-9_]{1,64}")) return;
            Path folder = dataDir.resolve(FOLDERS.getOrDefault(modId, modId)).resolve("update");
            if (p.type == CHECK) {
                List<String> lines = Files.isRegularFile(folder.resolve("latest.txt"))
                        ? Files.readAllLines(folder.resolve("latest.txt"), StandardCharsets.UTF_8) : List.of();
                Path jar = folder.resolve("mod.jar");
                if (lines.size() < 3 || !Files.isRegularFile(jar) || Files.size(jar) > MAX_JAR) {
                    conn.send(Packet.of(NONE).build());
                } else {
                    conn.send(Packet.of(INFO).str(modId).str(lines.get(0).trim()).str(lines.get(1).trim())
                            .i64(Files.size(jar)).str(lines.get(2).trim()).str(lines.size() > 3 ? lines.get(3) : "").build());
                }
            } else {
                Path jar = folder.resolve("mod.jar");
                if (!Files.isRegularFile(jar) || Files.size(jar) > MAX_JAR) {
                    conn.send(Packet.of(NONE).build());
                } else {
                    conn.send(Packet.of(FILE).bytes(Files.readAllBytes(jar)).build()); // 大包由 Connection 分块限速
                }
            }
        }
    }
}
