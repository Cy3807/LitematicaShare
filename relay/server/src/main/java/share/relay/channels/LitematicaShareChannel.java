package share.relay.channels;

import share.protocol.ChestRecord;
import share.protocol.LitematicaShare;
import share.protocol.Packet;
import share.protocol.TaskRecord;
import share.relay.Channel;
import share.relay.Connection;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static share.protocol.LitematicaShare.*;

/** litematica_share 模组: 按房间转发管理员的投影文件与放置信息。 */
public final class LitematicaShareChannel implements Channel {
    private static final int MAX_ROOMS = 200;
    private static final int MAX_TASKS = 200;
    private static final int MAX_CHESTS = 20_000; // 每个房间最多记录的容器数

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final RoomStore store;

    public LitematicaShareChannel(Path dataDir) throws IOException {
        store = new RoomStore(dataDir);
        for (RoomStore.Meta m : store.loadAll()) {
            Room r = new Room(m.name(), m.keyHash(), m.creator());
            r.admins.clear();
            r.admins.addAll(m.admins());
            r.sharing = m.sharing();
            r.lastFile = store.loadPacket(m.name(), "file");
            r.lastPlacement = store.loadPacket(m.name(), "placement");
            r.placeKey = placeKey(r.lastPlacement == null ? "" : placementJson(r.lastPlacement));
            r.regionsSig = regionsOf(r.lastPlacement == null ? "" : placementJson(r.lastPlacement));
            r.fileSha = fileShaOf(r.lastFile);
            loadProgress(r);
            r.taskMode = store.loadRaw(r.name, "mode") != null;
            loadTasks(r);
            loadAllowed(r);
            loadChests(r);
            rooms.put(m.name(), r);
        }
        System.out.println("litematica_share: loaded " + rooms.size() + " room(s) from disk");
        startProgressFlusher();
    }

    private static String placementJson(Packet p) {
        try {
            return Packet.readStr(p.reader());
        } catch (IOException e) {
            return "";
        }
    }

    private static String fileShaOf(Packet file) {
        if (file == null) return "";
        try {
            DataInputStream r = file.reader();
            Packet.readStr(r);
            return Packet.readStr(r);
        } catch (IOException e) {
            return "";
        }
    }

    /** 从位置信息 json 里取 x,y,z,rotation,mirror，和客户端上报进度时带的 placeKey 对得上才算数。 */
    static String placeKey(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"x\":(-?\\d+),\"y\":(-?\\d+),\"z\":(-?\\d+),\"rotation\":\"(\\w+)\",\"mirror\":\"(\\w+)\"").matcher(json);
        return m.find() ? LitematicaShare.placeKey(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), m.group(4), m.group(5)) : "";
    }

    private void clearVerify(Room r) {
        if (r.verify.isEmpty()) return;
        r.verify.clear();
        r.broadcast(Packet.of(VERIFY_RESET).build(), null);
    }

    private void clearProgress(Room r) {
        clearVerify(r);
        if (r.progress.isEmpty() && !r.progressDirty) return;
        r.progress.clear();
        r.progressDirty = true;
        r.pushDirty = true;
    }

    private void saveProgress(Room r) {
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(buf)) {
                out.writeUTF(r.fileSha);
                out.writeUTF(r.placeKey);
                out.writeInt(r.progress.size());
                for (Map.Entry<Long, Map<String, int[]>> e : r.progress.entrySet()) {
                    out.writeLong(e.getKey());
                    out.writeInt(e.getValue().size());
                    for (Map.Entry<String, int[]> b : e.getValue().entrySet()) {
                        out.writeUTF(b.getKey());
                        out.writeInt(b.getValue()[0]);
                        out.writeInt(b.getValue()[1]);
                    }
                }
            }
            store.saveRaw(r.name, "progress", r.progress.isEmpty() ? null : buf.toByteArray());
        } catch (IOException e) {
            System.err.println("failed to save progress of room " + r.name + ": " + e);
        }
    }

    private void loadProgress(Room r) {
        byte[] raw = store.loadRaw(r.name, "progress");
        if (raw == null) return;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw))) {
            if (!in.readUTF().equals(r.fileSha) || !in.readUTF().equals(r.placeKey)) return; // 文件或位置变了，旧进度作废
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long key = in.readLong();
                int m = in.readInt();
                Map<String, int[]> entries = new HashMap<>();
                for (int j = 0; j < m; j++) entries.put(in.readUTF(), new int[]{in.readInt(), in.readInt()});
                r.progress.put(key, entries);
            }
        } catch (IOException e) {
            r.progress.clear();
        }
    }

    private void saveChests(Room r) {
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            for (ChestRecord c : r.chests.values()) {
                Packet p = c.write(Packet.of(CHEST_UPDATE)).build();
                java.io.DataOutputStream out = new java.io.DataOutputStream(buf);
                out.writeInt(p.body.length);
                out.write(p.body);
            }
            store.saveRaw(r.name, "chests", r.chests.isEmpty() ? null : buf.toByteArray());
        } catch (IOException e) {
            System.err.println("failed to save chests of room " + r.name + ": " + e);
        }
    }

    private void loadChests(Room r) {
        byte[] raw = store.loadRaw(r.name, "chests");
        if (raw == null) return;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw))) {
            while (in.available() > 0) {
                byte[] body = new byte[in.readInt()];
                in.readFully(body);
                ChestRecord c = ChestRecord.read(new Packet(CHEST_UPDATE, body).reader());
                r.chests.put(c.key(), c);
            }
        } catch (IOException e) {
            System.err.println("failed to load chests of room " + r.name + ": " + e);
        }
        r.chests.keySet().removeIf(k -> !r.allowed.containsKey(k)); // 升级前没有白名单时存下的记录一律作废
    }

    private static Packet materialsPacket(Room rm) {
        Map<String, long[]> sum = new TreeMap<>();
        for (Map<String, int[]> entries : rm.progress.values()) {
            for (Map.Entry<String, int[]> e : entries.entrySet()) {
                long[] t = sum.computeIfAbsent(e.getKey(), k -> new long[2]);
                t[0] += e.getValue()[0];
                t[1] += e.getValue()[1];
            }
        }
        Packet.Builder b = Packet.of(MATERIALS).str(rm.fileSha).i32(rm.progress.size()).i32(sum.size());
        for (Map.Entry<String, long[]> e : sum.entrySet()) b.str(e.getKey()).i64(e.getValue()[0]).i64(e.getValue()[1]);
        return b.build();
    }

    private void saveTasks(Room r) {
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            for (TaskRecord t : r.tasks.values()) {
                Packet p = t.write(Packet.of(TASKS)).build();
                java.io.DataOutputStream out = new java.io.DataOutputStream(buf);
                out.writeInt(p.body.length);
                out.write(p.body);
            }
            store.saveRaw(r.name, "tasks", r.tasks.isEmpty() ? null : buf.toByteArray());
        } catch (IOException e) {
            System.err.println("failed to save tasks of room " + r.name + ": " + e);
        }
    }

    private void loadTasks(Room r) {
        byte[] raw = store.loadRaw(r.name, "tasks");
        if (raw == null) return;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw))) {
            while (in.available() > 0) {
                byte[] body = new byte[in.readInt()];
                in.readFully(body);
                TaskRecord t = TaskRecord.read(new Packet(TASKS, body).reader());
                r.tasks.put(t.id(), t);
            }
        } catch (IOException e) {
            System.err.println("failed to load tasks of room " + r.name + ": " + e);
        }
    }

    private static Packet tasksPacket(Room r) {
        Packet.Builder b = Packet.of(TASKS).i32(r.tasks.size());
        for (TaskRecord t : r.tasks.values()) t.write(b);
        return b.build();
    }

    private void saveAllowed(Room r) {
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(buf)) {
                out.writeInt(r.allowed.size());
                for (Object[] a : r.allowed.values()) {
                    out.writeUTF((String) a[0]);
                    out.writeInt((Integer) a[1]);
                    out.writeInt((Integer) a[2]);
                    out.writeInt((Integer) a[3]);
                }
            }
            store.saveRaw(r.name, "chestallow", r.allowed.isEmpty() ? null : buf.toByteArray());
        } catch (IOException e) {
            System.err.println("failed to save allowed chests of room " + r.name + ": " + e);
        }
    }

    private void loadAllowed(Room r) {
        byte[] raw = store.loadRaw(r.name, "chestallow");
        if (raw == null) return;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String dim = in.readUTF();
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                r.allowed.put(chestKey(dim, x, y, z), new Object[]{dim, x, y, z});
            }
        } catch (IOException e) {
            r.allowed.clear();
        }
    }

    private static String chestKey(String dim, int x, int y, int z) {
        return dim + "|" + x + "," + y + "," + z;
    }

    private Packet roomsPacket() {
        List<Room> list = new ArrayList<>(rooms.values());
        list.sort(Comparator.comparing((Room r) -> r.name));
        Packet.Builder b = Packet.of(ROOMS).i32(Math.min(list.size(), 500));
        int n = 0;
        for (Room r : list) {
            if (n++ >= 500) break;
            String proj = "";
            if (r.sharing && r.lastPlacement != null) {
                java.util.regex.Matcher mt = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"").matcher(placementJson(r.lastPlacement));
                if (mt.find()) proj = mt.group(1);
            }
            synchronized (r) {
                b.str(r.name).i32(r.members.size()).bool(r.sharing).str(proj).bool(r.taskMode);
            }
        }
        return b.build();
    }

    private static Packet shareState(Room r) {
        return Packet.of(SHARE_STATE).bool(r.sharing).bool(r.lastFile != null).build();
    }

    private static Packet allowedPacket(Room r) {
        Packet.Builder b = Packet.of(CHEST_ALLOWED).i32(r.allowed.size());
        for (Object[] a : r.allowed.values()) b.str((String) a[0]).i32((Integer) a[1]).i32((Integer) a[2]).i32((Integer) a[3]);
        return b.build();
    }

    private static Packet invPacket(Member m) {
        Packet.Builder b = Packet.of(INV_UPDATE).str(m.uuid).str(m.name).i32(m.inv.size());
        m.inv.forEach((id, n) -> b.str(id).i32(n));
        return b.build();
    }

    /** 记录不多时立刻落盘(重启不丢)；很多时交给后台每 10 秒一次的刷盘，避免每次上报都重写大文件。 */
    private void markChestsDirty(Room r) {
        if (r.chests.size() <= 1000) saveChests(r); else r.chestsDirty = true;
    }

    /** 下发给客户端的记录: time 换成"多久之前"。 */
    private static Packet chestPacket(byte type, ChestRecord c, long now) {
        return c.withTime(c.reporter(), Math.max(0, now - c.time())).write(Packet.of(type)).build();
    }

    /** 进度改动较频繁，不每次落盘，后台每 10 秒把有改动的房间存一次。 */
    private void startProgressFlusher() {
        java.util.concurrent.ScheduledExecutorService ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "progress-flusher");
            t.setDaemon(true);
            return t;
        });
        // 进度有变化就主动推给房间里所有成员 (合并成每 2 秒最多一次)，成员不用重新验证也能一直看到最新进度
        ex.scheduleWithFixedDelay(() -> {
            for (Room r : rooms.values()) {
                synchronized (r) {
                    if (!r.pushDirty) continue;
                    r.pushDirty = false;
                    if (r.sharing && !r.members.isEmpty()) r.broadcast(materialsPacket(r), null);
                }
            }
        }, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
        ex.scheduleWithFixedDelay(() -> {
            for (Room r : rooms.values()) {
                synchronized (r) {
                    if (r.progressDirty) {
                        r.progressDirty = false;
                        saveProgress(r);
                    }
                    if (r.chestsDirty) {
                        r.chestsDirty = false;
                        saveChests(r);
                    }
                }
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void saveMeta(Room r) {
        try {
            store.saveMeta(new RoomStore.Meta(r.name, r.keyHash, r.creator, new HashSet<>(r.admins), r.sharing));
        } catch (IOException e) {
            System.err.println("failed to save room " + r.name + ": " + e);
        }
    }

    private void savePacket(Room r, String slot, Packet p) {
        try {
            store.savePacket(r.name, slot, p);
        } catch (IOException e) {
            System.err.println("failed to save " + slot + " of room " + r.name + ": " + e);
        }
    }

    @Override
    public void flush() {
        for (Room r : rooms.values()) {
            synchronized (r) {
                if (r.progressDirty) { r.progressDirty = false; saveProgress(r); }
                if (r.chestsDirty) { r.chestsDirty = false; saveChests(r); }
            }
        }
    }

    @Override public String id() { return CHANNEL; }

    @Override public int version() { return VERSION; }

    @Override
    public void handle(Connection conn) throws IOException {
        Member m = new Member(conn);
        try {
            Packet first = conn.read();
            if (first.type == ROOM_LIST) { // 只想看房间列表: 回一个列表就结束
                conn.send(roomsPacket());
                return;
            }
            if (first.type != JOIN || !join(m, first)) return;
            conn.setReadTimeout(90_000); // 客户端每 20 秒心跳一次，90 秒没动静就当作断线
            while (true) onPacket(m, conn.read());
        } finally {
            leave(m);
        }
    }

    private static final class Room {
        final String name, keyHash, creator;
        final Map<String, Member> members = new LinkedHashMap<>();
        final Set<String> admins = new HashSet<>();
        boolean sharing;
        Packet lastPlacement, lastFile;
        String fileSha = "";
        /** 材料统计: 区块 -> (方块id -> {需要, 已摆})，最近一次上报为准。 */
        final Map<Long, Map<String, int[]>> progress = new HashMap<>();
        String placeKey = "";
        boolean progressDirty;
        /** 进度有变化、还没推送给成员。 */
        boolean pushDirty;
        /** 团队验证: 开关和各区块最近一次的验证结果 (VERIFY_CHUNK 正文，只在内存里)。 */
        boolean verifyOn = true; // 团队验证默认开启 (管理员可以关)
        /** 房间模式: 任务模式(子任务、按子区域筛选)或简单模式(整个投影文件)，持久化。 */
        boolean taskMode;
        /** 子任务 (id -> 任务)，持久化。 */
        final Map<String, TaskRecord> tasks = new LinkedHashMap<>();
        /** 渲染层同步: 开关和管理员最近的渲染层 json。 */
        boolean layerOn;
        String layerJson = "";
        /** 启用的子区域 (placement json 里的 regions)，变了说明统计范围变了，旧进度作废。 */
        String regionsSig = "";
        final Map<String, Packet> verify = new HashMap<>();
        /** 共享箱子库存索引: 容器 key(维度|x,y,z) -> 最近一次上报，time 为转发端的绝对时间。 */
        final Map<String, ChestRecord> chests = new LinkedHashMap<>();
        boolean chestsDirty;
        /** 允许上传的容器: key(维度|x,y,z) -> {dim,x,y,z}，管理员维护，持久化。 */
        final Map<String, Object[]> allowed = new LinkedHashMap<>();

        Room(String name, String keyHash, String creator) {
            this.name = name;
            this.keyHash = keyHash;
            this.creator = creator;
            admins.add(creator);
        }

        void broadcast(Packet p, Member except) {
            for (Member c : members.values()) if (c != except) c.conn.send(p);
        }

        void sendMembers() {
            Packet.Builder b = Packet.of(MEMBERS).i32(members.size());
            for (Member c : members.values()) b.str(c.uuid).str(c.name).bool(admins.contains(c.uuid));
            broadcast(b.build(), null);
        }
    }

    private static final class Member {
        final Connection conn;
        String uuid, name, haveSha = "";
        /** 该成员共享的背包 (不持久化)，null = 没有共享。 */
        Map<String, Integer> inv;
        Room room;

        Member(Connection conn) { this.conn = conn; }

        void error(String msg) { conn.send(Packet.of(ERROR).str(msg).build()); }
    }

    private boolean join(Member m, Packet p) throws IOException {
        DataInputStream r = p.reader();
        String roomName = Packet.readStr(r), key = Packet.readStr(r);
        m.uuid = Packet.readStr(r);
        m.name = Packet.readStr(r);
        m.haveSha = Packet.readStr(r);
        if (roomName.isBlank() || roomName.length() > 64 || m.uuid.isBlank()) { m.error("invalid join"); return false; }
        boolean[] created = {false}, full = {false};
        String keyHash = RoomStore.hash(key);
        Room rm = rooms.compute(roomName, (n, old) -> {
            if (old != null) return old;
            if (rooms.size() >= MAX_ROOMS) { full[0] = true; return null; }
            created[0] = true;
            return new Room(n, keyHash, m.uuid);
        });
        if (full[0]) { m.error("too many rooms"); return false; }
        synchronized (rm) {
            if (!rm.keyHash.equals(keyHash)) { m.error("wrong room key"); return false; }
            if (created[0]) saveMeta(rm);
            Member prev = rm.members.put(m.uuid, m);
            if (prev != null) prev.conn.close();
            m.room = rm;
            m.conn.send(Packet.of(JOINED).bool(rm.admins.contains(m.uuid)).bool(created[0]).build());
            m.conn.send(shareState(rm));
            if (rm.sharing) {
                if (rm.lastFile != null) m.conn.send(m.haveSha.equals(rm.fileSha) ? Packet.of(FILE_SAME).str(rm.fileSha).build() : rm.lastFile); // 本机已有同一个文件就不重发
                if (rm.lastPlacement != null) m.conn.send(rm.lastPlacement);
            }
            if (rm.layerOn) m.conn.send(Packet.of(LAYER_STATE).bool(true).str(rm.layerJson).build());
            m.conn.send(Packet.of(VERIFY_STATE).bool(rm.verifyOn).build());
            if (rm.verifyOn) for (Packet vp : rm.verify.values()) m.conn.send(vp);
            m.conn.send(allowedPacket(rm));
            m.conn.send(tasksPacket(rm));
            m.conn.send(Packet.of(MODE_STATE).bool(rm.taskMode).build());
            for (Member o : rm.members.values()) if (o != m && o.inv != null) m.conn.send(invPacket(o));
            if (!rm.chests.isEmpty()) {
                long now = System.currentTimeMillis();
                Packet.Builder b = Packet.of(CHEST_SYNC).i32(rm.chests.size());
                for (ChestRecord c : rm.chests.values()) c.withTime(c.reporter(), Math.max(0, now - c.time())).write(b);
                m.conn.send(b.build());
            }
            rm.sendMembers();
        }
        return true;
    }

    private void onPacket(Member m, Packet p) throws IOException {
        Room rm = m.room;
        synchronized (rm) {
            boolean admin = rm.admins.contains(m.uuid);
            switch (p.type) {
                case SET_ADMIN -> {
                    if (!admin) { m.error("not admin"); return; }
                    DataInputStream r = p.reader();
                    String target = Packet.readStr(r);
                    boolean on = r.readBoolean();
                    if (target.equals(rm.creator) && !on) { m.error("cannot demote room creator"); return; }
                    if (!rm.members.containsKey(target)) { m.error("no such member"); return; }
                    if (on) rm.admins.add(target); else rm.admins.remove(target);
                    saveMeta(rm);
                    rm.sendMembers();
                }
                case SHARE -> {
                    if (!admin) { m.error("not admin"); return; }
                    rm.sharing = p.reader().readBoolean();
                    // 关共享只是暂停：房间的投影文件、位置、进度都保留 (投影文件是房间唯一的，开回来不用重新上传)
                    saveMeta(rm);
                    rm.broadcast(shareState(rm), null);
                    if (rm.sharing && rm.lastFile != null) { // 开回来: 让所有人重新生成共享投影 (有文件的人不用再下载)
                        rm.broadcast(Packet.of(FILE_SAME).str(rm.fileSha).build(), null);
                        if (rm.lastPlacement != null) rm.broadcast(rm.lastPlacement, null);
                    }
                }
                case FILE_REQUEST -> {
                    String sha = Packet.readStr(p.reader());
                    if (rm.lastFile != null && sha.equals(rm.fileSha)) m.conn.send(rm.lastFile);
                }
                case PROGRESS -> {
                    DataInputStream r = p.reader();
                    String sha = Packet.readStr(r), pk = Packet.readStr(r);
                    if (!rm.sharing || !sha.equals(rm.fileSha) || !pk.equals(rm.placeKey)) return; // 过期的上报直接丢弃
                    long chunk = ((long) r.readInt() << 32) | (r.readInt() & 0xFFFFFFFFL);
                    int nr = r.readInt();
                    if (nr < 0 || nr > 512) return;
                    Set<String> regions = new HashSet<>();
                    for (int i = 0; i < nr; i++) regions.add(Packet.readStr(r));
                    int n = r.readInt();
                    if (n < 0 || n > 4096 || (rm.progress.size() >= 200_000 && !rm.progress.containsKey(chunk))) return;
                    Map<String, int[]> entries = new HashMap<>();
                    for (int i = 0; i < n; i++) entries.put(Packet.readStr(r), new int[]{r.readInt(), r.readInt()});
                    // 只替换这次扫描覆盖的子区域的旧数据；没覆盖的子区域(比如本机没显示的、别人上报的)保留
                    Map<String, int[]> merged = new HashMap<>(rm.progress.getOrDefault(chunk, Map.of()));
                    merged.keySet().removeIf(k -> {
                        int sep = k.indexOf(REGION_SEP);
                        return regions.contains(sep < 0 ? "" : k.substring(0, sep));
                    });
                    merged.putAll(entries);
                    if (merged.isEmpty()) rm.progress.remove(chunk); else rm.progress.put(chunk, merged);
                    rm.progressDirty = true;
                    rm.pushDirty = true;
                }
                case PING -> m.conn.send(Packet.of(PONG).build());
                case CHEST_REPORT -> {
                    ChestRecord c = ChestRecord.read(p.reader());
                    if (c.dim().length() > 128 || c.kind().length() > 32) { m.error("bad chest record"); return; }
                    if (!rm.allowed.containsKey(c.key())) return; // 没被管理员允许的容器一律不收
                    c = c.withTime(m.name, System.currentTimeMillis());
                    if (!rm.chests.containsKey(c.key()) && rm.chests.size() >= MAX_CHESTS) { m.error("too many chests"); return; }
                    rm.chests.put(c.key(), c);
                    markChestsDirty(rm);
                    rm.broadcast(chestPacket(CHEST_UPDATE, c, c.time()), m);
                }
                case VERIFY_REFRESH -> {
                    m.conn.send(Packet.of(VERIFY_STATE).bool(rm.verifyOn).build());
                    if (rm.verifyOn) {
                        m.conn.send(Packet.of(VERIFY_RESET).build());
                        for (Packet vp : rm.verify.values()) m.conn.send(vp);
                    }
                }
                case MODE_SET -> {
                    if (!admin) { m.error("not admin"); return; }
                    rm.taskMode = p.reader().readBoolean();
                    store.saveRaw(rm.name, "mode", rm.taskMode ? new byte[]{1} : null);
                    rm.broadcast(Packet.of(MODE_STATE).bool(rm.taskMode).build(), null);
                }
                case TASK_SET -> {
                    if (!admin) { m.error("not admin"); return; }
                    TaskRecord t = TaskRecord.read(p.reader());
                    if (t.title().isBlank()) { m.error("empty title"); return; }
                    String id = t.id();
                    if (id.isEmpty() || !rm.tasks.containsKey(id)) {
                        if (rm.tasks.size() >= MAX_TASKS) { m.error("too many tasks"); return; }
                        id = Long.toString(System.nanoTime(), 36);
                    }
                    rm.tasks.put(id, t.withId(id).withBy(m.name));
                    saveTasks(rm);
                    rm.broadcast(tasksPacket(rm), null);
                }
                case TASK_DELETE -> {
                    if (!admin) { m.error("not admin"); return; }
                    if (rm.tasks.remove(Packet.readStr(p.reader())) != null) {
                        saveTasks(rm);
                        rm.broadcast(tasksPacket(rm), null);
                    }
                }
                case TASK_STATUS -> {
                    DataInputStream r = p.reader();
                    TaskRecord t = rm.tasks.get(Packet.readStr(r));
                    int status = r.readInt();
                    if (t == null || status < 0 || status > 2) return;
                    if (!admin && !t.assignees().contains(m.uuid)) { m.error("not assigned"); return; }
                    rm.tasks.put(t.id(), t.withStatus(status, m.name));
                    saveTasks(rm);
                    rm.broadcast(tasksPacket(rm), null);
                }
                case LAYER_SET -> {
                    if (!admin) { m.error("not admin"); return; }
                    DataInputStream r = p.reader();
                    rm.layerOn = r.readBoolean();
                    String json = Packet.readStr(r);
                    if (json.length() > 2048) { m.error("bad layer"); return; }
                    rm.layerJson = json;
                    rm.broadcast(Packet.of(LAYER_STATE).bool(rm.layerOn).str(rm.layerJson).build(), null);
                }
                case VERIFY_SET -> {
                    if (!admin) { m.error("not admin"); return; }
                    rm.verifyOn = p.reader().readBoolean();
                    if (!rm.verifyOn) clearVerify(rm);
                    rm.broadcast(Packet.of(VERIFY_STATE).bool(rm.verifyOn).build(), null);
                }
                case VERIFY_REPORT -> {
                    DataInputStream r = p.reader();
                    String sha = Packet.readStr(r), pk = Packet.readStr(r);
                    if (!rm.verifyOn || !rm.sharing || !sha.equals(rm.fileSha) || !pk.equals(rm.placeKey) || p.body.length > 64 * 1024) return;
                    long chunk = ((long) r.readInt() << 32) | (r.readInt() & 0xFFFFFFFFL);
                    if (r.readInt() != 1) return; // 每个包只含一个子区域
                    String vkey = chunk + "|" + Packet.readStr(r); // 按 区块+子区域 保存，不同子区域的上报互不覆盖
                    if (rm.verify.size() >= 400_000 && !rm.verify.containsKey(vkey)) return;
                    Packet out = new Packet(VERIFY_CHUNK, p.body);
                    rm.verify.put(vkey, out);
                    rm.broadcast(out, m);
                }
                case CHEST_ALLOW -> {
                    if (!admin) { m.error("not admin"); return; }
                    DataInputStream r = p.reader();
                    String dim = Packet.readStr(r);
                    int x = r.readInt(), y = r.readInt(), z = r.readInt();
                    boolean allow = r.readBoolean();
                    String key = chestKey(dim, x, y, z);
                    if (dim.length() > 128) { m.error("bad chest"); return; }
                    if (allow) {
                        if (!rm.allowed.containsKey(key) && rm.allowed.size() >= MAX_CHESTS) { m.error("too many chests"); return; }
                        rm.allowed.put(key, new Object[]{dim, x, y, z});
                    } else {
                        rm.allowed.remove(key);
                        if (rm.chests.remove(key) != null) {
                            markChestsDirty(rm);
                            rm.broadcast(Packet.of(CHEST_GONE).str(dim).i32(x).i32(y).i32(z).build(), null);
                        }
                    }
                    saveAllowed(rm);
                    rm.broadcast(allowedPacket(rm), null);
                }
                case CHEST_CLEAR -> {
                    if (!admin) { m.error("not admin"); return; }
                    rm.allowed.clear();
                    rm.chests.clear();
                    saveAllowed(rm);
                    saveChests(rm);
                    rm.broadcast(allowedPacket(rm), null);
                }
                case INVENTORY -> {
                    DataInputStream r = p.reader();
                    int n = r.readInt();
                    if (n < 0 || n > 2048) { m.error("bad inventory"); return; }
                    if (n == 0) {
                        if (m.inv != null) rm.broadcast(Packet.of(INV_GONE).str(m.uuid).build(), m);
                        m.inv = null;
                        return;
                    }
                    Map<String, Integer> inv = new HashMap<>();
                    for (int i = 0; i < n; i++) inv.put(Packet.readStr(r), r.readInt());
                    m.inv = inv;
                    rm.broadcast(invPacket(m), m);
                }
                case CHEST_REMOVE -> {
                    DataInputStream r = p.reader();
                    String dim = Packet.readStr(r);
                    int x = r.readInt(), y = r.readInt(), z = r.readInt();
                    if (rm.chests.remove(dim + "|" + x + "," + y + "," + z) != null) {
                        markChestsDirty(rm);
                        rm.broadcast(Packet.of(CHEST_GONE).str(dim).i32(x).i32(y).i32(z).build(), m);
                    }
                }
                case MATERIALS_REQUEST -> m.conn.send(materialsPacket(rm));
                case CLEAR -> {
                    if (!admin) { m.error("not admin"); return; }
                    rm.lastFile = null;
                    rm.lastPlacement = null;
                    rm.fileSha = "";
                    rm.placeKey = "";
                    clearProgress(rm);
                    savePacket(rm, "file", null);
                    savePacket(rm, "placement", null);
                    rm.broadcast(Packet.of(CLEARED).build(), null);
                }
                case PLACEMENT, FILE -> {
                    if (!admin || !rm.sharing) { m.error("not allowed"); return; }
                    if (p.type == FILE) {
                        DataInputStream r = p.reader();
                        Packet.readStr(r);
                        String sha = Packet.readStr(r);
                        rm.lastFile = p;
                        rm.fileSha = sha;
                        rm.lastPlacement = null; // 新文件到来，旧位置作废，等管理员随后发新的
                        rm.placeKey = "";
                        clearProgress(rm);
                        savePacket(rm, "file", p);
                        savePacket(rm, "placement", null);
                    } else {
                        String sha = placementSha(Packet.readStr(p.reader()));
                        if (!sha.equals(rm.fileSha)) { m.error("placement does not match the shared file"); return; }
                        rm.lastPlacement = p;
                        savePacket(rm, "placement", p);
                        String json = Packet.readStr(p.reader());
                        String pk = placeKey(json);
                        String regions = regionsOf(json);
                        if (!pk.equals(rm.placeKey) || !regions.equals(rm.regionsSig)) { // 投影被移动/旋转或启用的子区域变了，旧的区块进度作废
                            rm.placeKey = pk;
                            rm.regionsSig = regions;
                            clearProgress(rm);
                        }
                    }
                    rm.broadcast(p, m);
                }
                default -> m.error("unknown packet " + p.type);
            }
        }
    }

    private static String regionsOf(String json) {
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("\"regions\"\\s*:\\s*(\\{[^}]*\\})").matcher(json);
        return mt.find() ? mt.group(1) : "";
    }

    private static String placementSha(String json) {
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("\"sha\"\\s*:\\s*\"([0-9a-fA-F]*)\"").matcher(json);
        return mt.find() ? mt.group(1) : "";
    }

    private void leave(Member m) {
        Room rm = m.room;
        if (rm == null) return;
        synchronized (rm) {
            if (rm.members.get(m.uuid) == m) {
                rm.members.remove(m.uuid);
                if (m.inv != null) rm.broadcast(Packet.of(INV_GONE).str(m.uuid).build(), null);
            }
            if (!rm.members.isEmpty()) rm.sendMembers(); // 房间不随人走光而销毁，状态保留在磁盘上
        }
    }
}
