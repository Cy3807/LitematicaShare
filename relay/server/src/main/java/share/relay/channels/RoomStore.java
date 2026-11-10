package share.relay.channels;

import share.protocol.Packet;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** litematica_share 房间状态的磁盘存档: 重启转发服务或所有人离线后，房间、管理员和共享的投影都还在。 */
final class RoomStore {
    record Meta(String name, String keyHash, String creator, Set<String> admins, boolean sharing) { }

    private final Path root;

    RoomStore(Path dataDir) throws IOException {
        Path ls = dataDir.resolve("ls"); // 本模组的数据都在 relay-data/ls/ 下: rooms/(房间数据) 和 update/(自动更新包)
        this.root = ls.resolve("rooms");
        Path old = dataDir.resolve("litematica_share"); // 旧版本的位置: 第一次启动时整个搬过来，房间数据不丢
        if (Files.isDirectory(old) && !Files.exists(root)) {
            Files.createDirectories(ls);
            Files.move(old, root);
            System.out.println("moved room data " + old + " -> " + root);
        }
        Files.createDirectories(root);
    }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path dir(String roomName) { return root.resolve(hash(roomName)); }

    void saveMeta(Meta m) throws IOException {
        Path d = dir(m.name());
        Files.createDirectories(d);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeUTF(m.name());
            out.writeUTF(m.keyHash());
            out.writeUTF(m.creator());
            out.writeBoolean(m.sharing());
            out.writeInt(m.admins().size());
            for (String a : m.admins()) out.writeUTF(a);
        }
        atomicWrite(d.resolve("meta.bin"), buf.toByteArray());
    }

    void savePacket(String roomName, String slot, Packet p) throws IOException {
        Path d = dir(roomName);
        Files.createDirectories(d);
        if (p == null) {
            Files.deleteIfExists(d.resolve(slot + ".bin"));
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        p.write(new DataOutputStream(buf));
        atomicWrite(d.resolve(slot + ".bin"), buf.toByteArray());
    }

    Packet loadPacket(String roomName, String slot) {
        Path f = dir(roomName).resolve(slot + ".bin");
        if (!Files.isRegularFile(f)) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            return Packet.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    void saveRaw(String roomName, String slot, byte[] data) throws IOException {
        Path d = dir(roomName);
        Files.createDirectories(d);
        if (data == null) {
            Files.deleteIfExists(d.resolve(slot + ".bin"));
            return;
        }
        atomicWrite(d.resolve(slot + ".bin"), data);
    }

    byte[] loadRaw(String roomName, String slot) {
        try {
            Path f = dir(roomName).resolve(slot + ".bin");
            return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    List<Meta> loadAll() {
        List<Meta> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path d : ds) {
                Path f = d.resolve("meta.bin");
                if (!Files.isRegularFile(f)) continue;
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
                    String name = in.readUTF(), keyHash = in.readUTF(), creator = in.readUTF();
                    boolean sharing = in.readBoolean();
                    int n = in.readInt();
                    Set<String> admins = new HashSet<>();
                    for (int i = 0; i < n; i++) admins.add(in.readUTF());
                    out.add(new Meta(name, keyHash, creator, admins, sharing));
                } catch (IOException e) {
                    System.err.println("skip broken room store " + d + ": " + e);
                }
            }
        } catch (IOException e) {
            System.err.println("cannot read room store: " + e);
        }
        return out;
    }

    private static void atomicWrite(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
