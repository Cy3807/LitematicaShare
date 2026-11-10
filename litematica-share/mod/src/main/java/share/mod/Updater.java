package share.mod;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import share.protocol.Bulk;
import share.protocol.ModUpdate;
import share.protocol.Packet;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.*;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 模组自动更新: 从转发服务器的 mod_update 通道取最新版本，用内置公钥验证签名(+sha256)后放到 mods 旁边的暂存位置，
 * 游戏退出时替换旧 jar，下次启动生效。签名不对的文件一律丢弃，所以转发服务器被入侵也推不了恶意 jar。
 */
final class Updater {
    static final String MOD_ID = "litematica_share";
    /** 发布签名用的 Ed25519 公钥 (X.509, base64)，构建时由 local.properties 的 update_public_key 填入；空 = 此构建不启用自动更新。私钥只在发布者本机。 */
    static String publicKey() { return BuildDefaults.updateKey(); }

    static boolean enabled() { return !publicKey().isEmpty(); }

    /** 检查结果: state 0 已是最新 1 已下载待重启 2 失败/不可用；version 为服务器上的最新版本。 */
    record Result(int state, String version, String notes, String error) { }

    private static volatile boolean running;
    private static volatile String pendingVersion;

    static String currentVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    /** 已下载、重启游戏后生效的版本 (没有则 null)。 */
    static String pendingVersion() { return pendingVersion; }

    static boolean busy() { return running; }

    /** 后台检查并下载，完成后在游戏线程回调。 */
    static void check(ShareConfig cfg, java.util.function.Consumer<Result> done) {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            Result r;
            try {
                r = run(cfg);
            } catch (Exception e) {
                ShareMod.LOGGER.warn("update check failed: {}", e.toString());
                r = new Result(2, "", "", e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                running = false;
            }
            Result res = r;
            net.minecraft.client.Minecraft.getInstance().execute(() -> done.accept(res));
        }, "litematica-share-update");
        t.setDaemon(true);
        t.start();
    }

    private static Result run(ShareConfig cfg) throws Exception {
        if (!enabled()) return new Result(2, "", "", "此构建未启用自动更新");
        if (!cfg.hasServer()) return new Result(2, "", "", "未设置服务器地址");
        Path jar = currentJar();
        if (jar == null) return new Result(2, "", "", "开发环境不更新");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(cfg.host, cfg.port), 6000);
            s.setSoTimeout(30_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            Packet.of(Packet.HELLO).str(ModUpdate.CHANNEL).i32(ModUpdate.VERSION).build().write(out);
            String refused = Packet.readStr(Packet.read(in).reader());
            if (!refused.isEmpty()) return new Result(2, "", "", "转发服务器不支持自动更新，请让管理员更新转发 jar");
            Packet.of(ModUpdate.CHECK).str(MOD_ID).build().write(out);
            Packet info = Packet.read(in);
            if (info.type != ModUpdate.INFO) return new Result(0, currentVersion(), "", "");
            DataInputStream r = info.reader();
            Packet.readStr(r);
            String version = Packet.readStr(r), sha = Packet.readStr(r);
            long size = r.readLong();
            String sig = Packet.readStr(r), notes = Packet.readStr(r);
            if (ModUpdate.compareVersions(version, currentVersion()) <= 0) return new Result(0, version, notes, "");
            if (version.equals(pendingVersion)) return new Result(1, version, notes, "");
            if (size > 32L * 1024 * 1024) return new Result(2, version, notes, "文件过大");
            // 先验签再下载: 签名覆盖 模组id+版本+sha256
            if (!verify(version, sha, sig)) return new Result(2, version, notes, "签名无效，已忽略");
            Packet.of(ModUpdate.DOWNLOAD).str(MOD_ID).build().write(out);
            Bulk.Assembler asm = new Bulk.Assembler();
            Packet f;
            do { f = asm.accept(Packet.read(in)); } while (f == null);
            if (f.type != ModUpdate.FILE) return new Result(2, version, notes, "下载失败");
            DataInputStream fr = f.reader();
            byte[] data = new byte[fr.readInt()];
            fr.readFully(data);
            String got = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            if (!got.equals(sha)) return new Result(2, version, notes, "文件校验失败，已丢弃");
            Path staged = jar.resolveSibling(jar.getFileName() + ".update");
            Files.write(staged, data);
            scheduleSwap(jar, staged, version);
            pendingVersion = version;
            return new Result(1, version, notes, "");
        }
    }

    private static boolean verify(String version, String sha, String sigB64) {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey())));
            Signature sg = Signature.getInstance("Ed25519");
            sg.initVerify(key);
            sg.update(ModUpdate.signedBytes(MOD_ID, version, sha));
            return sg.verify(Base64.getDecoder().decode(sigB64));
        } catch (Exception e) {
            return false;
        }
    }

    private static Path currentJar() {
        ModContainer c = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
        if (c == null) return null;
        for (Path p : c.getOrigin().getPaths()) {
            if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) return p;
        }
        return null;
    }

    private static boolean hookAdded;

    /** 游戏退出时把暂存的新 jar 换成旧 jar (旧 jar 此时仍被占用的系统上，交给一个延迟的脚本去换)。 */
    private static synchronized void scheduleSwap(Path jar, Path staged, String version) {
        if (hookAdded) return;
        hookAdded = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Path target = jar.resolveSibling("litematica-share-" + version + ".jar");
                try {
                    Files.deleteIfExists(jar);
                    Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    if (!System.getProperty("os.name", "").toLowerCase().contains("win")) throw e;
                    // Windows: 运行中的 jar 删不掉，退出后由 cmd 延迟几秒再替换
                    new ProcessBuilder("cmd", "/c", "ping -n 4 127.0.0.1 >nul & del /f /q \"" + jar + "\" & move /y \"" + staged + "\" \"" + target + "\"")
                            .start();
                }
            } catch (IOException ignored) {
            }
        }, "litematica-share-update-swap"));
    }

    private Updater() { }
}
