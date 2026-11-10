package share.relay;

import org.junit.jupiter.api.Test;
import share.protocol.Bulk;
import share.protocol.LitematicaShare;
import share.protocol.Packet;

import java.io.*;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

class RelayServerTest {
    static java.nio.file.Path tmp() throws IOException {
        return java.nio.file.Files.createTempDirectory("relay-test");
    }

    static final class Client implements AutoCloseable {
        final Socket s;
        final DataInputStream in;
        final DataOutputStream out;
        final Bulk.Assembler assembler = new Bulk.Assembler();
        final java.util.List<Byte> seen = new java.util.ArrayList<>();

        Client(int port, String room, String key, String uuid) throws IOException {
            this(port, room, key, uuid, "");
        }

        Client(int port, String room, String key, String uuid, String haveSha) throws IOException {
            s = new Socket("127.0.0.1", port);
            s.setSoTimeout(5000);
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            Packet.of(Packet.HELLO).str(LitematicaShare.CHANNEL).i32(LitematicaShare.VERSION).build().write(out);
            Packet.read(in);
            Packet.of(LitematicaShare.JOIN).str(room).str(key).str(uuid).str("n-" + uuid).str(haveSha).build().write(out);
        }

        Packet next(byte type) throws IOException {
            while (true) {
                Packet p = assembler.accept(Packet.read(in));
                if (p == null) continue;
                seen.add(p.type);
                if (p.type == type) return p;
            }
        }

        public void close() throws IOException { s.close(); }
    }

    @Test
    void unknownChannelRejected() throws Exception {
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), tmp());
        srv.start(0);
        try (Socket s = new Socket("127.0.0.1", srv.port())) {
            s.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            Packet.of(Packet.HELLO).str("other_mod").i32(1).build().write(out);
            Packet reply = Packet.read(new DataInputStream(s.getInputStream()));
            assertEquals(Packet.HELLO, reply.type);
            assertTrue(Packet.readStr(reply.reader()).contains("unsupported"));
        } finally {
            srv.stop();
        }
    }

    static final String PLACE = "{\"sha\":\"abc123\",\"x\":1}";

    static final String FULL = "{\"sha\":\"abc123\",\"name\":\"n\",\"x\":1,\"y\":2,\"z\":3,\"rotation\":\"NONE\",\"mirror\":\"NONE\",\"enabled\":true}";
    static final String PK = LitematicaShare.placeKey(1, 2, 3, "NONE", "NONE");

    private static Packet progress(String sha, String pk, int cx, int cz, Object... blockReqPlaced) {
        Packet.Builder b = Packet.of(LitematicaShare.PROGRESS).str(sha).str(pk).i32(cx).i32(cz).i32(1).str("").i32(blockReqPlaced.length / 3);
        for (int i = 0; i < blockReqPlaced.length; i += 3) b.str((String) blockReqPlaced[i]).i32((Integer) blockReqPlaced[i + 1]).i32((Integer) blockReqPlaced[i + 2]);
        return b.build();
    }

    @Test
    void materialsAggregateAcrossReporters() throws Exception {
        java.nio.file.Path dir = tmp();
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), dir);
        srv.start(0);
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            a.next(LitematicaShare.JOINED);
            Packet.of(LitematicaShare.SHARE).bool(true).build().write(a.out);
            Packet.of(LitematicaShare.FILE).str("f.litematic").str("abc123").bytes(new byte[]{1}).build().write(a.out);
            Packet.of(LitematicaShare.PLACEMENT).str(FULL).build().write(a.out);
            try (Client b = new Client(srv.port(), "r", "k", "B")) {
                b.next(LitematicaShare.PLACEMENT);
                progress("abc123", PK, 0, 0, "minecraft:stone", 10, 4, "minecraft:dirt", 5, 5).write(b.out);
                progress("abc123", PK, 1, 0, "minecraft:stone", 6, 6).write(b.out);
                progress("abc123", PK, 1, 0, "minecraft:stone", 6, 3).write(b.out);         // 同一区块以最新上报为准
                progress("abc123", "9,9,9|NONE|NONE", 5, 5, "minecraft:stone", 99, 99).write(b.out); // 位置不符，丢弃
                progress("deadbeef", PK, 6, 6, "minecraft:stone", 99, 99).write(b.out);               // 文件不符，丢弃
                Packet.of(LitematicaShare.MATERIALS_REQUEST).build().write(b.out); // 同一连接保证顺序
                DataInputStream r = b.next(LitematicaShare.MATERIALS).reader();
                assertEquals("abc123", Packet.readStr(r));
                assertEquals(2, r.readInt(), "已观测区块数");
                assertEquals(2, r.readInt());
                assertEquals("minecraft:dirt", Packet.readStr(r));
                assertEquals(5, r.readLong());
                assertEquals(5, r.readLong());
                assertEquals("minecraft:stone", Packet.readStr(r));
                assertEquals(16, r.readLong());  // 10 + 6
                assertEquals(7, r.readLong());   // 4 + 3

                // 投影被移动后旧进度作废
                Packet.of(LitematicaShare.PLACEMENT).str(FULL.replace("\"x\":1", "\"x\":50")).build().write(a.out);
                int chunks = -1;
                for (int i = 0; i < 40 && chunks != 0; i++) { // A 与 B 是不同连接，轮询等服务端处理完
                    Thread.sleep(50);
                    Packet.of(LitematicaShare.MATERIALS_REQUEST).build().write(b.out);
                    DataInputStream r2 = b.next(LitematicaShare.MATERIALS).reader();
                    Packet.readStr(r2);
                    chunks = r2.readInt();
                }
                assertEquals(0, chunks);
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    void progressMergesPerRegionInTheSameChunk() throws Exception {
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), tmp());
        srv.start(0);
        String sep = LitematicaShare.REGION_SEP;
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            a.next(LitematicaShare.JOINED);
            Packet.of(LitematicaShare.SHARE).bool(true).build().write(a.out);
            Packet.of(LitematicaShare.FILE).str("f.litematic").str("abc123").bytes(new byte[]{1}).build().write(a.out);
            Packet.of(LitematicaShare.PLACEMENT).str(FULL).build().write(a.out);
            try (Client b = new Client(srv.port(), "r", "k", "B")) {
                b.next(LitematicaShare.PLACEMENT);
                // A 上报同一个区块里 X、Y 两个子区域；B 只看得到 X，只上报 X —— Y 的数据不能被覆盖
                Packet.of(LitematicaShare.PROGRESS).str("abc123").str(PK).i32(0).i32(0).i32(2).str("X").str("Y").i32(2)
                        .str("X" + sep + "minecraft:stone").i32(10).i32(1).str("Y" + sep + "minecraft:stone").i32(20).i32(2).build().write(a.out);
                Packet.of(LitematicaShare.PROGRESS).str("abc123").str(PK).i32(0).i32(0).i32(1).str("X").i32(1)
                        .str("X" + sep + "minecraft:stone").i32(10).i32(9).build().write(b.out);
                Packet.of(LitematicaShare.MATERIALS_REQUEST).build().write(b.out);
                long needY = 0, placedX = 0;
                DataInputStream r = null;
                for (int i = 0; i < 40 && placedX != 9; i++) { // A 与 B 是不同连接，轮询等服务端处理完
                    Thread.sleep(50);
                    Packet.of(LitematicaShare.MATERIALS_REQUEST).build().write(b.out);
                    r = b.next(LitematicaShare.MATERIALS).reader();
                    Packet.readStr(r);
                    r.readInt();
                    int n = r.readInt();
                    needY = 0;
                    placedX = 0;
                    for (int j = 0; j < n; j++) {
                        String key = Packet.readStr(r);
                        long need = r.readLong(), placed = r.readLong();
                        if (key.startsWith("Y")) needY = need;
                        if (key.startsWith("X")) placedX = placed;
                    }
                }
                assertEquals(9, placedX, "B 更新了 X");
                assertEquals(20, needY, "Y 的数据保留");
            }
        } finally {
            srv.stop();
        }
    }

    private static Packet chestReport(String dim, int x, int y, int z, String kind, Object... idCount) {
        java.util.Map<String, Integer> items = new java.util.LinkedHashMap<>();
        for (int i = 0; i < idCount.length; i += 2) items.put((String) idCount[i], (Integer) idCount[i + 1]);
        return new share.protocol.ChestRecord(dim, x, y, z, kind, "", 0, items).write(Packet.of(LitematicaShare.CHEST_REPORT)).build();
    }

    private static Packet chestAllow(boolean allow, int x, int y, int z) {
        return Packet.of(LitematicaShare.CHEST_ALLOW).str("minecraft:overworld").i32(x).i32(y).i32(z).bool(allow).build();
    }

    @Test
    void inventoriesAreSharedAndDropped() throws Exception {
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), tmp());
        srv.start(0);
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            a.next(LitematicaShare.JOINED);
            try (Client b = new Client(srv.port(), "r", "k", "B")) {
                b.next(LitematicaShare.JOINED);
                Packet.of(LitematicaShare.INVENTORY).i32(1).str("minecraft:iron_ingot").i32(40).build().write(b.out);
                DataInputStream r = a.next(LitematicaShare.INV_UPDATE).reader();
                assertEquals("B", Packet.readStr(r));
                Packet.readStr(r);
                assertEquals(1, r.readInt());
                assertEquals("minecraft:iron_ingot", Packet.readStr(r));
                assertEquals(40, r.readInt());
                // 后加入的人也能拿到已共享的背包
                try (Client c = new Client(srv.port(), "r", "k", "C")) {
                    assertEquals("B", Packet.readStr(c.next(LitematicaShare.INV_UPDATE).reader()));
                }
            }
            assertEquals("B", Packet.readStr(a.next(LitematicaShare.INV_GONE).reader())); // B 离开
        } finally {
            srv.stop();
        }
    }

    @Test
    void chestIndexSharedAndPersisted() throws Exception {
        java.nio.file.Path dir = tmp();
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), dir);
        srv.start(0);
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            a.next(LitematicaShare.JOINED);
            chestAllow(true, 10, 64, 20).write(a.out);
            chestAllow(true, 12, 64, 20).write(a.out);
            while (a.next(LitematicaShare.CHEST_ALLOWED).reader().readInt() < 2) { /* 等服务器处理完两条允许 */ }
            // 普通成员也能上报，但只有被管理员允许的容器才收；成员不能改白名单
            try (Client b = new Client(srv.port(), "r", "k", "B")) {
                b.next(LitematicaShare.JOINED);
                chestAllow(true, 50, 64, 50).write(b.out);
                assertEquals("not admin", Packet.readStr(b.next(LitematicaShare.ERROR).reader()));
                chestReport("minecraft:overworld", 50, 64, 50, "chest", "minecraft:dirt", 1).write(b.out); // 没被允许
                chestReport("minecraft:overworld", 10, 64, 20, "chest", "minecraft:iron_ingot", 320, "minecraft:stone", 64).write(b.out);
                chestReport("minecraft:overworld", 12, 64, 20, "barrel", "minecraft:iron_ingot", 5).write(b.out);
                share.protocol.ChestRecord got = share.protocol.ChestRecord.read(a.next(LitematicaShare.CHEST_UPDATE).reader());
                assertEquals("B".equals(got.reporter()) ? "B" : "n-B", got.reporter());
                assertEquals(320, got.items().get("minecraft:iron_ingot"));
                a.next(LitematicaShare.CHEST_UPDATE);
                // 同一位置再次上报 = 覆盖
                chestReport("minecraft:overworld", 10, 64, 20, "chest", "minecraft:iron_ingot", 100).write(b.out);
                assertEquals(100, share.protocol.ChestRecord.read(a.next(LitematicaShare.CHEST_UPDATE).reader()).items().get("minecraft:iron_ingot"));
            }
        } finally {
            srv.stop();
        }
        // 重启后新成员加入，拿到全量
        RelayServer srv2 = new RelayServer(new BandwidthLimiter(1_000_000), dir);
        srv2.start(0);
        try (Client c = new Client(srv2.port(), "r", "k", "C")) {
            DataInputStream r = c.next(LitematicaShare.CHEST_SYNC).reader();
            assertEquals(2, r.readInt());
            share.protocol.ChestRecord first = share.protocol.ChestRecord.read(r);
            assertTrue(c.seen.contains(LitematicaShare.CHEST_ALLOWED), "白名单重启后保留并随加入下发");
            assertEquals(100, first.items().get("minecraft:iron_ingot"));
            assertTrue(first.time() >= 0 && first.time() < 60_000, "time 是多久之前");
            // 容器被拆: 移除并通知别人
            try (Client d = new Client(srv2.port(), "r", "k", "D")) {
                d.next(LitematicaShare.CHEST_SYNC);
                Packet.of(LitematicaShare.CHEST_REMOVE).str("minecraft:overworld").i32(12).i32(64).i32(20).build().write(d.out);
                DataInputStream g = c.next(LitematicaShare.CHEST_GONE).reader();
                assertEquals("minecraft:overworld", Packet.readStr(g));
                assertEquals(12, g.readInt());
            }
            // 一键删除: 白名单和记录都清空
            try (Client a2 = new Client(srv2.port(), "r", "k", "A")) {
                a2.next(LitematicaShare.JOINED);
                Packet.of(LitematicaShare.CHEST_CLEAR).build().write(a2.out);
                int n;
                do { n = c.next(LitematicaShare.CHEST_ALLOWED).reader().readInt(); } while (n != 0);
            }
        } finally {
            srv2.stop();
        }
    }

    private static Packet taskSet(String id, String title, String region, String... assignees) {
        return new share.protocol.TaskRecord(id, title, region, java.util.List.of(assignees), 0, "", "").write(Packet.of(LitematicaShare.TASK_SET)).build();
    }

    private static java.util.List<share.protocol.TaskRecord> readTasks(Packet p) throws IOException {
        DataInputStream r = p.reader();
        int n = r.readInt();
        java.util.List<share.protocol.TaskRecord> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) out.add(share.protocol.TaskRecord.read(r));
        return out;
    }

    @Test
    void tasksAssignedUpdatedAndPersisted() throws Exception {
        java.nio.file.Path dir = tmp();
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), dir);
        srv.start(0);
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            a.next(LitematicaShare.JOINED);
            try (Client b = new Client(srv.port(), "r", "k", "B"); Client c = new Client(srv.port(), "r", "k", "C")) {
                b.next(LitematicaShare.JOINED);
                c.next(LitematicaShare.JOINED);
                // 房间模式: 成员不能改，管理员改了广播并持久化
                Packet.of(LitematicaShare.MODE_SET).bool(true).build().write(b.out);
                assertEquals("not admin", Packet.readStr(b.next(LitematicaShare.ERROR).reader()));
                Packet.of(LitematicaShare.MODE_SET).bool(true).build().write(a.out);
                while (!b.next(LitematicaShare.MODE_STATE).reader().readBoolean()) { /* 等广播 */ }
                // 成员不能建任务
                taskSet("", "x", "").write(b.out);
                assertEquals("not admin", Packet.readStr(b.next(LitematicaShare.ERROR).reader()));
                // 管理员建任务并分配给 B
                taskSet("", "盖东楼", "A区", "B").write(a.out);
                java.util.List<share.protocol.TaskRecord> ts;
                do { ts = readTasks(b.next(LitematicaShare.TASKS)); } while (ts.isEmpty());
                assertEquals("盖东楼", ts.get(0).title());
                assertEquals(java.util.List.of("B"), ts.get(0).assignees());
                String id = ts.get(0).id();
                // 没被分配的 C 不能改状态；被分配的 B 可以
                Packet.of(LitematicaShare.TASK_STATUS).str(id).i32(2).build().write(c.out);
                assertEquals("not assigned", Packet.readStr(c.next(LitematicaShare.ERROR).reader()));
                Packet.of(LitematicaShare.TASK_STATUS).str(id).i32(1).build().write(b.out);
                do { ts = readTasks(a.next(LitematicaShare.TASKS)); } while (ts.isEmpty() || ts.get(0).status() != 1);
                assertEquals("n-B", ts.get(0).by());
            }
        } finally {
            srv.stop();
        }
        // 重启后任务还在，新加入的人拿到全量
        RelayServer srv2 = new RelayServer(new BandwidthLimiter(1_000_000), dir);
        srv2.start(0);
        try (Client d = new Client(srv2.port(), "r", "k", "D")) {
            java.util.List<share.protocol.TaskRecord> ts;
            do { ts = readTasks(d.next(LitematicaShare.TASKS)); } while (ts.isEmpty());
            assertTrue(d.next(LitematicaShare.MODE_STATE).reader().readBoolean(), "任务模式重启后保留");
            assertEquals(1, ts.size());
            assertEquals(1, ts.get(0).status());
            try (Client a2 = new Client(srv2.port(), "r", "k", "A")) {
                a2.next(LitematicaShare.JOINED);
                Packet.of(LitematicaShare.TASK_DELETE).str(ts.get(0).id()).build().write(a2.out);
                do { ts = readTasks(d.next(LitematicaShare.TASKS)); } while (!ts.isEmpty());
            }
        } finally {
            srv2.stop();
        }
    }

    @Test
    void roomListShowsEveryRoomWithoutJoining() throws Exception {
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), tmp());
        srv.start(0);
        try (Client a = new Client(srv.port(), "alpha", "k", "A"); Client b = new Client(srv.port(), "beta", "k", "B")) {
            a.next(LitematicaShare.JOINED);
            b.next(LitematicaShare.JOINED);
            Packet.of(LitematicaShare.SHARE).bool(true).build().write(a.out);
            Packet.of(LitematicaShare.FILE).str("f.litematic").str("abc123").bytes(new byte[]{1}).build().write(a.out);
            Packet.of(LitematicaShare.PLACEMENT).str(FULL).build().write(a.out);
            Thread.sleep(300);
            // 不加入房间，只要列表 (不用密钥)
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", srv.port())) {
                s.setSoTimeout(5000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                Packet.of(Packet.HELLO).str(LitematicaShare.CHANNEL).i32(LitematicaShare.VERSION).build().write(out);
                Packet.read(in);
                Packet.of(LitematicaShare.ROOM_LIST).build().write(out);
                DataInputStream r = Packet.read(in).reader();
                assertEquals(2, r.readInt());
                assertEquals("alpha", Packet.readStr(r));
                assertEquals(1, r.readInt());
                assertTrue(r.readBoolean(), "alpha 共享中");
                assertEquals("n", Packet.readStr(r), "投影名");
                assertFalse(r.readBoolean());
                assertEquals("beta", Packet.readStr(r));
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    void shareOffKeepsTheRoomProjectionAndKnownFilesAreNotResent() throws Exception {
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), tmp());
        srv.start(0);
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            a.next(LitematicaShare.JOINED);
            Packet.of(LitematicaShare.SHARE).bool(true).build().write(a.out);
            Packet.of(LitematicaShare.FILE).str("f.litematic").str("abc123").bytes(new byte[]{1, 2, 3}).build().write(a.out);
            Packet.of(LitematicaShare.PLACEMENT).str(FULL).build().write(a.out);
            try (Client b = new Client(srv.port(), "r", "k", "B")) {
                b.next(LitematicaShare.FILE);
                b.next(LitematicaShare.PLACEMENT);
                // 关共享: 只是暂停，房间的投影文件保留 (SHARE_STATE 的第二个 boolean = 房间里有投影)
                Packet.of(LitematicaShare.SHARE).bool(false).build().write(a.out);
                DataInputStream off = b.next(LitematicaShare.SHARE_STATE).reader();
                assertFalse(off.readBoolean());
                assertTrue(off.readBoolean(), "关共享后房间的投影还在");
                // 开回来: 不用重新上传，转发端让所有人重新生成 (文件没变，只发 FILE_SAME + 位置)
                Packet.of(LitematicaShare.SHARE).bool(true).build().write(a.out);
                assertEquals("abc123", Packet.readStr(b.next(LitematicaShare.FILE_SAME).reader()));
                assertEquals(FULL, Packet.readStr(b.next(LitematicaShare.PLACEMENT).reader()));
                // 本机没有文件时可以要: FILE_REQUEST -> FILE
                Packet.of(LitematicaShare.FILE_REQUEST).str("abc123").build().write(b.out);
                assertEquals("f.litematic", Packet.readStr(b.next(LitematicaShare.FILE).reader()));
            }
            // 已经有同一个文件的人加入: 不重发文件，只给 FILE_SAME + 位置
            try (Client c = new Client(srv.port(), "r", "k", "C", "abc123")) {
                c.next(LitematicaShare.FILE_SAME);
                c.next(LitematicaShare.PLACEMENT);
                assertFalse(c.seen.contains(LitematicaShare.FILE), "已有同一个文件，不重发");
            }
            // 没有的人加入: 照常发文件
            try (Client d = new Client(srv.port(), "r", "k", "D", "")) {
                d.next(LitematicaShare.FILE);
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    void pingGetsPong() throws Exception {
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), tmp());
        srv.start(0);
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            a.next(LitematicaShare.JOINED);
            Packet.of(LitematicaShare.PING).build().write(a.out);
            a.next(LitematicaShare.PONG);
        } finally {
            srv.stop();
        }
    }

    @Test
    void adminCanClearSharedPlacement() throws Exception {
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), tmp());
        srv.start(0);
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            a.next(LitematicaShare.JOINED);
            Packet.of(LitematicaShare.SHARE).bool(true).build().write(a.out);
            Packet.of(LitematicaShare.FILE).str("f.litematic").str("abc123").bytes(new byte[]{1}).build().write(a.out);
            Packet.of(LitematicaShare.PLACEMENT).str(PLACE).build().write(a.out);
            try (Client b = new Client(srv.port(), "r", "k", "B")) {
                b.next(LitematicaShare.PLACEMENT);
                Packet.of(LitematicaShare.CLEAR).build().write(b.out);
                assertEquals("not admin", Packet.readStr(b.next(LitematicaShare.ERROR).reader()));
                Packet.of(LitematicaShare.CLEAR).build().write(a.out);
                b.next(LitematicaShare.CLEARED);
            }
            try (Client c = new Client(srv.port(), "r", "k", "C")) { // 清除后加入的人不应再收到旧投影
                c.next(LitematicaShare.JOINED);
                c.next(LitematicaShare.MEMBERS);
                assertFalse(c.seen.contains(LitematicaShare.FILE) || c.seen.contains(LitematicaShare.PLACEMENT));
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    void roomSurvivesRestartAndEveryoneLeaving() throws Exception {
        java.nio.file.Path dir = tmp();
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), dir);
        srv.start(0);
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            a.next(LitematicaShare.JOINED);
            Packet.of(LitematicaShare.SHARE).bool(true).build().write(a.out);
            Packet.of(LitematicaShare.FILE).str("f.litematic").str("abc123").bytes(new byte[]{9, 9}).build().write(a.out);
            Packet.of(LitematicaShare.PLACEMENT).str(PLACE).build().write(a.out);
            try (Client b = new Client(srv.port(), "r", "k", "B")) {
                b.next(LitematicaShare.PLACEMENT); // 确认服务器已处理完
            }
        } finally {
            srv.stop();
        }
        // 所有人离线 + 转发服务重启
        RelayServer srv2 = new RelayServer(new BandwidthLimiter(1_000_000), dir);
        srv2.start(0);
        try (Client b = new Client(srv2.port(), "r", "k", "B")) {
            DataInputStream jb = b.next(LitematicaShare.JOINED).reader();
            assertFalse(jb.readBoolean(), "B 不是管理员"); // 房间还在，B 不会变成创建者
            assertFalse(jb.readBoolean(), "房间不是新建的");
            assertTrue(b.next(LitematicaShare.SHARE_STATE).reader().readBoolean());
            b.next(LitematicaShare.FILE);
            assertEquals(PLACE, Packet.readStr(b.next(LitematicaShare.PLACEMENT).reader()));
            try (Client a = new Client(srv2.port(), "r", "k", "A")) {
                assertTrue(a.next(LitematicaShare.JOINED).reader().readBoolean(), "创建者仍是管理员");
            }
            try (Client bad = new Client(srv2.port(), "r", "wrong", "X")) {
                assertEquals("wrong room key", Packet.readStr(bad.next(LitematicaShare.ERROR).reader()));
            }
        } finally {
            srv2.stop();
        }
    }

    @Test
    void adminSharesToMembers() throws Exception {
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), tmp());
        srv.start(0);
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            DataInputStream ja = a.next(LitematicaShare.JOINED).reader();
            assertTrue(ja.readBoolean());
            assertTrue(ja.readBoolean());
            try (Client b = new Client(srv.port(), "r", "k", "B")) {
            assertFalse(b.next(LitematicaShare.JOINED).reader().readBoolean());

            // 非管理员不能共享
            Packet.of(LitematicaShare.SHARE).bool(true).build().write(b.out);
            assertEquals("not admin", Packet.readStr(b.next(LitematicaShare.ERROR).reader()));

            Packet.of(LitematicaShare.SHARE).bool(true).build().write(a.out);
            assertTrue(b.next(LitematicaShare.SHARE_STATE).reader().readBoolean());
            Packet.of(LitematicaShare.PLACEMENT).str(PLACE).build().write(a.out);
            assertEquals("placement does not match the shared file", Packet.readStr(a.next(LitematicaShare.ERROR).reader()));
            Packet.of(LitematicaShare.FILE).str("f.litematic").str("abc123").bytes(new byte[]{1, 2, 3}).build().write(a.out);
            b.next(LitematicaShare.FILE);
            Packet.of(LitematicaShare.PLACEMENT).str(PLACE).build().write(a.out);
            assertEquals(PLACE, Packet.readStr(b.next(LitematicaShare.PLACEMENT).reader()));

            // 指派 B 为管理员，B 也可以广播
            Packet.of(LitematicaShare.SET_ADMIN).str("B").bool(true).build().write(a.out);
            Packet.of(LitematicaShare.SET_ADMIN).str("A").bool(false).build().write(b.out);
            assertEquals("cannot demote room creator", Packet.readStr(b.next(LitematicaShare.ERROR).reader()));

            // 晚加入的人收到当前状态
            try (Client c = new Client(srv.port(), "r", "k", "C")) {
                c.next(LitematicaShare.JOINED);
                assertTrue(c.next(LitematicaShare.SHARE_STATE).reader().readBoolean());
                c.next(LitematicaShare.FILE);
                assertEquals(PLACE, Packet.readStr(c.next(LitematicaShare.PLACEMENT).reader()));
            }
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    void wrongKeyRejected() throws Exception {
        RelayServer srv = new RelayServer(new BandwidthLimiter(1_000_000), tmp());
        srv.start(0);
        try (Client a = new Client(srv.port(), "r", "k", "A")) {
            a.next(LitematicaShare.JOINED);
            try (Client b = new Client(srv.port(), "r", "bad", "B")) {
            assertEquals("wrong room key", Packet.readStr(b.next(LitematicaShare.ERROR).reader()));
            }
        } finally {
            srv.stop();
        }
    }

    @Test
    void largeFileIsRateLimitedAndDoesNotBlockOthers() throws Exception {
        long rate = 400_000; // 字节/秒
        RelayServer srv = new RelayServer(new BandwidthLimiter(rate), tmp());
        srv.start(0);
        byte[] data = new byte[800_000];
        new java.util.Random(1).nextBytes(data);
        try (Client a = new Client(srv.port(), "r", "k", "A"); Client b = join(srv, "B")) {
            a.next(LitematicaShare.JOINED);
            b.next(LitematicaShare.JOINED);
            Packet.of(LitematicaShare.SHARE).bool(true).build().write(a.out);
            b.next(LitematicaShare.SHARE_STATE);
            b.seen.clear();
            long t0 = System.nanoTime();
            Packet.of(LitematicaShare.FILE).str("x.litematic").str("sha").bytes(data).build().write(a.out);
            Client c = join(srv, "C"); // 传输进行中加入
            Packet file = b.next(LitematicaShare.FILE);
            double secs = (System.nanoTime() - t0) / 1e9;
            DataInputStream r = file.reader();
            Packet.readStr(r);
            Packet.readStr(r);
            assertArrayEquals(data, Packet.readBytes(r));
            assertTrue(secs >= 1.7, "800KB at 400KB/s should take about 2s, took " + secs);
            assertTrue(secs < 4, "took " + secs);
            // 传输期间 C 加入产生的成员列表小包应穿插在文件分块之间，不用等文件发完
            c.close();
            assertTrue(b.seen.indexOf(LitematicaShare.MEMBERS) < b.seen.indexOf(LitematicaShare.FILE));
        } finally {
            srv.stop();
        }
    }

    @Test
    void limiterSpacesOutTransfers() throws Exception {
        BandwidthLimiter l = new BandwidthLimiter(1_000_000);
        long t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) l.acquire(50_000);
        double secs = (System.nanoTime() - t0) / 1e9;
        assertTrue(secs >= 0.4 && secs < 1.0, "took " + secs);
        assertEquals(1_000_000, BandwidthLimiter.mbpsToBytesPerSecond(8));
    }

    private static Client join(RelayServer srv, String uuid) throws IOException {
        return new Client(srv.port(), "r", "k", uuid);
    }

    @Test
    void updateChannelServesSignedFiles() throws Exception {
        java.nio.file.Path data = tmp();
        java.nio.file.Path mod = data.resolve("demo_mod").resolve("update");
        java.nio.file.Files.createDirectories(mod);
        byte[] jar = new byte[100_000];
        new java.util.Random(1).nextBytes(jar);
        java.nio.file.Files.write(mod.resolve("mod.jar"), jar);
        java.nio.file.Files.writeString(mod.resolve("latest.txt"), "0.2.0\nabc\nc2ln\n新功能\n");
        RelayServer srv = new RelayServer(new BandwidthLimiter(100_000_000), data);
        srv.start(0);
        try (Socket s = new Socket("127.0.0.1", srv.port())) {
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            Packet.of(Packet.HELLO).str(share.protocol.ModUpdate.CHANNEL).i32(share.protocol.ModUpdate.VERSION).build().write(out);
            assertEquals("", Packet.readStr(Packet.read(in).reader()));
            Packet.of(share.protocol.ModUpdate.CHECK).str("demo_mod").build().write(out);
            java.io.DataInputStream r = Packet.read(in).reader();
            assertEquals("demo_mod", Packet.readStr(r));
            assertEquals("0.2.0", Packet.readStr(r));
            assertEquals("abc", Packet.readStr(r));
            assertEquals(100_000, r.readLong());
            assertEquals("c2ln", Packet.readStr(r));
            assertEquals("新功能", Packet.readStr(r));
            Packet.of(share.protocol.ModUpdate.CHECK).str("other").build().write(out);
            assertEquals(share.protocol.ModUpdate.NONE, Packet.read(in).type);
            Packet.of(share.protocol.ModUpdate.DOWNLOAD).str("demo_mod").build().write(out);
            Bulk.Assembler asm = new Bulk.Assembler();
            Packet f;
            do { f = asm.accept(Packet.read(in)); } while (f == null);
            assertEquals(share.protocol.ModUpdate.FILE, f.type);
            java.io.DataInputStream fr = f.reader();
            byte[] got = new byte[fr.readInt()];
            fr.readFully(got);
            assertArrayEquals(jar, got);
        } finally {
            srv.stop();
        }
        assertTrue(share.protocol.ModUpdate.compareVersions("0.10.0", "0.9.9") > 0);
        assertEquals(0, share.protocol.ModUpdate.compareVersions("1.0", "1.0.0"));
    }

    @Test
    void oldRoomDataFolderIsMovedUnderLs() throws Exception {
        java.nio.file.Path data = tmp();
        java.nio.file.Path old = data.resolve("litematica_share").resolve("abc");
        java.nio.file.Files.createDirectories(old);
        java.nio.file.Files.writeString(old.resolve("meta.bin"), "x");
        new RelayServer(new BandwidthLimiter(1_000_000), data);
        assertTrue(java.nio.file.Files.exists(data.resolve("ls").resolve("rooms").resolve("abc").resolve("meta.bin")));
        assertFalse(java.nio.file.Files.exists(data.resolve("litematica_share")));
    }
}
