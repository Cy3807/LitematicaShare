package share.mod;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.IntBoundingBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import fi.dy.masa.litematica.materials.MaterialCache;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import share.protocol.LitematicaShare;
import share.protocol.Packet;

import java.util.*;

/**
 * 材料统计的客户端侧: 把已加载区块里「原理图需要什么、世界里已经摆对了什么」逐区块数出来上报给房间。
 * 游戏服务器会把视距内所有方块发给客户端，所以不用跑图，别人摆的方块也能数到。
 * 同一个区块连续两次扫描结果一致才上报(避开 Litematica 还没填完原理图区块的瞬间)，每 5 tick 只扫一个区块避免卡顿。
 */
final class ProgressScanner {
    private static final int SCAN_INTERVAL_TICKS = 5;

    private final Minecraft mc = Minecraft.getInstance();
    private final ShareSession session;
    private boolean enabled = true;
    private int tick;

    private String key = "";
    private List<ChunkPos> order = List.of();
    private int cursor;
    private final Map<Long, String> reported = new HashMap<>();
    private final Map<Long, String> pending = new HashMap<>();

    ProgressScanner(ShareSession session) { this.session = session; }

    boolean isEnabled() { return enabled; }

    void setEnabled(boolean on) { enabled = on; }

    /** 团队验证开启时，扫描额外记录逐方块的缺失/错误/多余/错误状态；开关变化后所有区块重新评估。 */
    private boolean verify;
    private VerifyModel.Chunk lastVerify;
    private static final int MAX_GROUPS = 96, MAX_CORRECT_GROUPS = 40, MAX_POS_PER_TYPE = 64;
    /** 这些属性经常和原理图不一致但并不算"摆错" (树叶距离、栅栏/红石连接、水含等)，状态比较时忽略。 */
    private static final Set<String> NOISY = Set.of("distance", "persistent", "north", "south", "east", "west", "up", "down",
            "shape", "waterlogged", "powered", "power", "lit", "bites", "age", "stage", "snowy", "triggered", "open", "attached", "in_wall");

    /** 手动刷新: 本机所有区块重新扫描并重新上报。 */
    void rescan() {
        reported.clear();
        pending.clear();
        order = List.of(); // 下一次从头排一轮区块 (规则可能变了，如首次验证模式)
        cursor = 0;
    }

    void setVerify(boolean on) {
        if (verify == on) return;
        verify = on;
        reported.clear();
        pending.clear();
    }

    private static boolean statesDiffer(BlockState want, BlockState have) {
        for (Property<?> prop : want.getProperties()) {
            if (NOISY.contains(prop.getName())) continue;
            if (!have.hasProperty(prop) || !want.getValue(prop).equals(have.getValue(prop))) return true;
        }
        return false;
    }

    static String placeKey(SchematicPlacement p) {
        BlockPos o = p.getOrigin();
        return LitematicaShare.placeKey(o.getX(), o.getY(), o.getZ(), p.getRotation().name(), p.getMirror().name());
    }

    /** 摆这个方块需要的物品 (和 Litematica 材料列表同一套规则: 台阶 2 个、床等需要多种物品)。 */
    static List<ItemStack> itemsFor(BlockState s) {
        MaterialCache cache = MaterialCache.getInstance();
        if (cache.requiresMultipleItems(s)) return cache.getItems(s);
        ItemStack one = cache.getRequiredBuildItemForState(s);
        return one == null || one.isEmpty() ? List.of() : List.of(one);
    }

    static String itemKey(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    void onTick(SchematicPlacement p, String sha) {
        ClientLevel level = mc.level;
        WorldSchematic sw = SchematicWorldHandler.getSchematicWorld();
        if (!enabled || p == null || sha.isEmpty() || level == null || sw == null || !session.isSettled() || ++tick % SCAN_INTERVAL_TICKS != 0) return;

        String k = sha + "|" + placeKey(p) + "|" + ShareSession.regionsSig(p);
        if (!k.equals(key)) { // 换了文件或投影被移动: 之前的结果全部作废
            key = k;
            reported.clear();
            pending.clear();
            order = List.of();
        }
        if (cursor >= order.size()) { // 一轮扫完，重新按离玩家的远近排序
            cursor = 0;
            order = new ArrayList<>(p.getTouchedChunks(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED));
            if (mc.player != null) {
                int px = mc.player.blockPosition().getX() >> 4, pz = mc.player.blockPosition().getZ() >> 4;
                int radius = Math.max(1, ShareMod.config().scanRadius);
                // 首次验证 (房间里这份投影还没有任何验证数据): 不限距离，所有已加载 (含缓存) 的区块都扫，缓存区块不影响验证，免得一开始要大家跑遍整个投影。
                // 之后只扫玩家附近的区块：服务器只发附近几个区块，更远的可能是缓存的旧数据，不能当作真实状态上报
                if (!session.isFirstVerification()) order.removeIf(c -> Math.max(Math.abs(c.x - px), Math.abs(c.z - pz)) > radius);
                order.sort(Comparator.comparingInt(c -> (c.x - px) * (c.x - px) + (c.z - pz) * (c.z - pz)));
            }
        }
        if (order.isEmpty()) return;
        ChunkPos c = order.get(cursor++);
        if (!level.getChunkSource().hasChunk(c.x, c.z)) return;
        if (!sw.getChunkSource().getChunkState(c.x, c.z).atLeast(ChunkSchematicState.FILLED)) return;

        String sig = scan(p, level, sw, c);
        long id = ((long) c.x << 32) | (c.z & 0xFFFFFFFFL);
        String prev = reported.getOrDefault(id, "");
        if (sig.equals(prev)) {
            pending.remove(id);
        } else if (sig.equals(pending.get(id))) { // 连续两次一致，上报
            pending.remove(id);
            reported.put(id, sig);
            report(sha, p, c, sig);
        } else {
            pending.put(id, sig);
        }
    }

    /** 最近一次扫描的材料数据 (子区域+物品 -> {需要, 已摆})，上报时用。 */
    private Map<String, int[]> lastCounts = Map.of();
    /** 最近一次扫描覆盖的子区域 (本机显示的、参与的)；转发端只替换这些子区域的旧数据。 */
    private List<String> lastRegions = List.of();

    /** 返回用来比较"有没有变化"的签名串；材料数据放在 lastCounts，验证数据放在 lastVerify。 */
    private String scan(SchematicPlacement p, ClientLevel level, WorldSchematic sw, ChunkPos c) {
        Map<String, int[]> counts = new TreeMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        lastRegions = new ArrayList<>();
        Map<String, int[]> regionTotals = new TreeMap<>(); // 子区域 -> {正确, 缺失, 错误方块, 多余, 错误状态}
        int[] perType = new int[5];
        Map<String, Integer> groupIndex = new LinkedHashMap<>();
        List<VerifyModel.Group> groupList = new ArrayList<>();
        int[] groupCounts = new int[MAX_GROUPS];
        List<Integer> positions = new ArrayList<>();
        for (Map.Entry<String, IntBoundingBox> be : p.getBoxesWithinChunk(c.x, c.z, SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).entrySet()) {
            String region = be.getKey();
            IntBoundingBox box = be.getValue();
            lastRegions.add(region);
            int[] rt = regionTotals.computeIfAbsent(region, k -> new int[5]);
            Map<net.minecraft.world.level.block.Block, Integer> correctGroup = new HashMap<>(); // 正确的方块按种类分组，不用每个位置都拼字符串
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockState want = sw.getBlockState(pos);
                        if (want.isAir()) {
                            if (verify) {
                                BlockState h = level.getBlockState(pos);
                                if (!h.isAir() && !(h.getBlock() instanceof LiquidBlock)) {
                                    rt[3]++;
                                    int gi = group(groupIndex, groupList, groupCounts, 2, region, "minecraft:air", h);
                                    if (gi >= 0 && perType[2]++ < MAX_POS_PER_TYPE) positions.add(pack(gi, pos));
                                }
                            }
                            continue;
                        }
                        BlockState have = level.getBlockState(pos);
                        if (verify) {
                            int type;
                            if (have.isAir() || (have.getBlock() instanceof LiquidBlock && want.getFluidState().isEmpty())) type = 0;
                            else if (!have.is(want.getBlock())) type = 1;
                            else if (statesDiffer(want, have)) type = 3;
                            else type = -1;
                            if (type < 0) {
                                rt[0]++;
                                Integer ci = correctGroup.get(want.getBlock());
                                if (ci == null) {
                                    ci = correctGroups(groupIndex, groupList, region, want);
                                    correctGroup.put(want.getBlock(), ci);
                                }
                                if (ci >= 0) {
                                    groupCounts[ci]++;
                                    if (perType[4]++ < MAX_POS_PER_TYPE) positions.add(pack(ci, pos));
                                }
                            } else {
                                rt[type + 1]++;
                                int gi = group(groupIndex, groupList, groupCounts, type, region, BuiltInRegistries.BLOCK.getKey(want.getBlock()).toString(), have);
                                if (gi >= 0 && perType[type]++ < MAX_POS_PER_TYPE) positions.add(pack(gi, pos));
                            }
                        }
                        boolean same = have.is(want.getBlock()); // 方块种类对了就算摆好(不看朝向)
                        List<ItemStack> haveItems = same ? itemsFor(have) : List.of();
                        for (ItemStack w : itemsFor(want)) {
                            String key = itemKey(w);
                            int[] n = counts.computeIfAbsent(LitematicaShare.regionKey(region, key), b -> new int[2]);
                            n[0] += w.getCount();
                            if (same) {
                                int hc = 0;
                                for (ItemStack h : haveItems) if (itemKey(h).equals(key)) hc += h.getCount();
                                n[1] += Math.min(w.getCount(), hc);
                            }
                        }
                    }
                }
            }
        }
        lastCounts = counts;
        StringBuilder sb = new StringBuilder();
        counts.forEach((b, n) -> sb.append(b).append(':').append(n[0]).append(':').append(n[1]).append(';'));
        if (verify) {
            List<VerifyModel.Group> groups = new ArrayList<>();
            for (int i = 0; i < groupList.size(); i++) {
                VerifyModel.Group g = groupList.get(i);
                groups.add(new VerifyModel.Group(g.region(), g.type(), g.expected(), g.found(), groupCounts[i]));
            }
            int[] pos2 = positions.stream().mapToInt(Integer::intValue).toArray();
            lastVerify = new VerifyModel.Chunk(regionTotals, groups, pos2);
            sb.append('#');
            regionTotals.forEach((r, t) -> sb.append(r).append(Arrays.toString(t)));
            sb.append(groups).append(Arrays.hashCode(pos2));
        } else {
            lastVerify = null;
        }
        return sb.toString();
    }

    /** 取(或新建)分组序号；分组满了返回 -1 (只计入总数，不再记位置)。 */
    private static int group(Map<String, Integer> index, List<VerifyModel.Group> list, int[] counts, int type, String region, String expected, BlockState have) {
        String found = BuiltInRegistries.BLOCK.getKey(have.getBlock()).toString();
        String key = type + "|" + region + "|" + expected + "|" + found;
        Integer i = index.get(key);
        if (i == null) {
            if (list.size() >= MAX_GROUPS) return -1;
            i = list.size();
            index.put(key, i);
            list.add(new VerifyModel.Group(region, type, expected, found, 0));
        }
        counts[i]++;
        return i;
    }

    /** 正确方块的分组 (最多 40 种，避免挤掉错误/缺失的分组)；满了返回 -1。 */
    private static int correctGroups(Map<String, Integer> index, List<VerifyModel.Group> list, String region, BlockState want) {
        String id = BuiltInRegistries.BLOCK.getKey(want.getBlock()).toString();
        String key = "4|" + region + "|" + id + "|" + id;
        Integer i = index.get(key);
        if (i != null) return i;
        long nCorrect = list.stream().filter(g -> g.type() == 4).count();
        if (list.size() >= MAX_GROUPS || nCorrect >= MAX_CORRECT_GROUPS) return -1;
        i = list.size();
        index.put(key, i);
        list.add(new VerifyModel.Group(region, 4, id, id, 0));
        return i;
    }

    private static int pack(int groupIdx, BlockPos pos) {
        return (groupIdx << 24) | (((pos.getY() + 64) & 0xFFF) << 8) | ((pos.getX() & 15) << 4) | (pos.getZ() & 15);
    }

    private void report(String sha, SchematicPlacement p, ChunkPos c, String fullSig) {
        long chunkKey = ((long) c.x << 32) | (c.z & 0xFFFFFFFFL);
        if (verify && lastVerify != null) {
            // 每个子区域单独一个包，转发端按 区块+子区域 保存 (本机没显示的子区域不会被覆盖掉)
            VerifyModel.Chunk v = lastVerify;
            for (Map.Entry<String, int[]> re : v.byRegion().entrySet()) {
                String region = re.getKey();
                Map<Integer, Integer> remap = new HashMap<>();
                List<VerifyModel.Group> groups = new ArrayList<>();
                for (int i = 0; i < v.groups().size(); i++) {
                    VerifyModel.Group g = v.groups().get(i);
                    if (!g.region().equals(region)) continue;
                    remap.put(i, groups.size());
                    groups.add(g);
                }
                List<Integer> positions = new ArrayList<>();
                for (int pos : v.positions()) {
                    Integer ni = remap.get(pos >>> 24);
                    if (ni != null) positions.add((ni << 24) | (pos & 0xFFFFFF));
                }
                int[] pos2 = positions.stream().mapToInt(Integer::intValue).toArray();
                Packet.Builder vb = Packet.of(LitematicaShare.VERIFY_REPORT).str(sha).str(placeKey(p)).i32(c.x).i32(c.z).i32(1).str(region);
                for (int n : re.getValue()) vb.i32(n);
                vb.i32(groups.size());
                for (VerifyModel.Group g : groups) vb.str(g.region()).i32(g.type()).str(g.expected()).str(g.found()).i32(g.count());
                vb.i32(pos2.length);
                for (int pos : pos2) vb.i32(pos);
                session.sendPacket(vb.build());
                session.onOwnVerify(chunkKey, new VerifyModel.Chunk(Map.of(region, re.getValue()), groups, pos2));
            }
        }
        Packet.Builder b = Packet.of(LitematicaShare.PROGRESS).str(sha).str(placeKey(p)).i32(c.x).i32(c.z).i32(lastRegions.size());
        for (String region : lastRegions) b.str(region);
        b.i32(lastCounts.size());
        lastCounts.forEach((key, n) -> b.str(key).i32(n[0]).i32(n[1]));
        session.sendPacket(b.build());
    }
}
