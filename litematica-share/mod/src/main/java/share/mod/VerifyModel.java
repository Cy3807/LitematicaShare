package share.mod;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import share.protocol.Packet;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;

/** 团队验证: 房间里所有成员上报的逐区块验证结果 (缺失/错误/多余/错误状态)，本机汇总显示并在世界里高亮。 */
final class VerifyModel {
    static final String[] TYPE_NAMES = {"缺失", "错误方块", "多余", "错误状态", "正确"};

    record Group(String region, int type, String expected, String found, int count) {
        /** 不含子区域: 同样的问题跨子区域合并显示，选中高亮也覆盖所有子区域。 */
        String key() { return type + "|" + expected + "|" + found; }
    }

    /** byRegion: 子区域 -> {正确, 缺失, 错误方块, 多余, 错误状态}。 */
    record Chunk(Map<String, int[]> byRegion, List<Group> groups, int[] positions) { }

    /** 筛选: null = 全部子区域。 */
    private static boolean allowed(Set<String> filter, String region) {
        return filter == null || filter.contains(region);
    }

    /** 选中的高亮: type=-1 表示不高亮；groupKey 为空表示这一类全部。 */
    record Selection(int type, String groupKey) { }

    /** 一个区块里一个子区域的验证结果 (按 区块+子区域 保存，不同子区域的上报互不覆盖)。 */
    private record Entry(long chunk, Chunk data) { }

    private final Map<String, Entry> entries = new HashMap<>();
    private Selection selection = new Selection(-1, "");

    boolean isEmpty() { return entries.isEmpty(); }

    /** 最近一次收到验证数据的时间 (毫秒)，界面显示"几秒前更新"。 */
    private long lastUpdate;

    long lastUpdate() { return lastUpdate; }

    int observedChunks() {
        Set<Long> seen = new HashSet<>();
        for (Entry e : entries.values()) seen.add(e.chunk());
        return seen.size();
    }

    void reset() { entries.clear(); }

    Selection selection() { return selection; }

    /** 列表里多选的分组 (key)；非空时世界高亮只显示这些分组，类别按钮的选择不起作用。 */
    private final Set<String> picked = new LinkedHashSet<>();

    /** 点类别按钮: 选这一类 (同时取消分组多选)。 */
    void select(int type, String groupKey) {
        picked.clear();
        selection = new Selection(type, groupKey);
    }

    /** 点列表里的一行: 切换这个分组的选中状态，可以同时选多条。 */
    void togglePick(String groupKey) {
        if (!picked.remove(groupKey)) picked.add(groupKey);
        selection = picked.isEmpty() ? new Selection(-1, "") : new Selection(-3, String.join("\n", picked));
    }

    boolean isPicked(String groupKey) { return picked.contains(groupKey); }

    boolean hasSelection() { return selection.type() >= 0 || !picked.isEmpty(); }

    void clearSelection() {
        picked.clear();
        selection = new Selection(-1, "");
    }

    /** 切换子区域筛选时，多选的分组可能已经不在筛选范围，就取消选择，避免高亮看不见的东西。 */
    void clearSelectionIfFilteredOut() {
        if (!picked.isEmpty()) clearSelection();
    }

    /** c 里只有一个子区域 (每个子区域单独上报)。 */
    void put(long chunk, Chunk c) {
        String region = c.byRegion().isEmpty() ? "" : c.byRegion().keySet().iterator().next();
        entries.put(chunk + "|" + region, new Entry(chunk, c));
        lastUpdate = System.currentTimeMillis();
    }

    /** 解析 VERIFY_REPORT/VERIFY_CHUNK 的正文 (sha、placeKey 由调用方先读掉并校验)。 */
    static long readChunk(DataInputStream r, Chunk[] out) throws IOException {
        long key = ((long) r.readInt() << 32) | (r.readInt() & 0xFFFFFFFFL);
        int nr = r.readInt();
        if (nr < 0 || nr > 512) throw new IOException("bad region count");
        Map<String, int[]> byRegion = new TreeMap<>();
        for (int i = 0; i < nr; i++) {
            String region = Packet.readStr(r);
            byRegion.put(region, new int[]{r.readInt(), r.readInt(), r.readInt(), r.readInt(), r.readInt()});
        }
        int n = r.readInt();
        if (n < 0 || n > 512) throw new IOException("bad group count");
        List<Group> groups = new ArrayList<>(n);
        for (int i = 0; i < n; i++) groups.add(new Group(Packet.readStr(r), r.readInt(), Packet.readStr(r), Packet.readStr(r), r.readInt()));
        int m = r.readInt();
        if (m < 0 || m > 4096) throw new IOException("bad position count");
        int[] pos = new int[m];
        for (int i = 0; i < m; i++) pos[i] = r.readInt();
        out[0] = new Chunk(byRegion, groups, pos);
        return key;
    }

    long correct(Set<String> filter) {
        long n = 0;
        for (Entry en : entries.values()) for (Map.Entry<String, int[]> e : en.data().byRegion().entrySet()) if (allowed(filter, e.getKey())) n += e.getValue()[0];
        return n;
    }

    long correct() { return correct(null); }

    /** 缺失、错误方块、多余、错误状态的总数 (只算筛选的子区域)。 */
    long[] totals(Set<String> filter) {
        long[] t = new long[4];
        for (Entry en : entries.values()) {
            for (Map.Entry<String, int[]> e : en.data().byRegion().entrySet()) {
                if (!allowed(filter, e.getKey())) continue;
                for (int i = 0; i < 4; i++) t[i] += e.getValue()[i + 1];
            }
        }
        return t;
    }

    long[] totals() { return totals(null); }

    /** 全部区块汇总后的分组，数量多的在前 (只含筛选的子区域；相同的方块问题跨子区域合并显示在各自的子区域下)。 */
    List<Group> groups(Set<String> filter) {
        Map<String, Group> sum = new HashMap<>();
        for (Entry en : entries.values()) {
            for (Group g : en.data().groups()) {
                if (!allowed(filter, g.region())) continue;
                sum.merge(g.key(), g, (a, b) -> new Group(a.region(), a.type(), a.expected(), a.found(), a.count() + b.count()));
            }
        }
        List<Group> out = new ArrayList<>(sum.values());
        out.sort(Comparator.comparingInt(Group::count).reversed());
        return out;
    }

    static String blockName(String id) {
        try {
            Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
            return b.getName().getString();
        } catch (RuntimeException e) {
            return id;
        }
    }

    /** 分组的图标: 优先期望方块，期望是空气(多余方块)就用实际方块。 */
    static net.minecraft.world.item.ItemStack iconOf(Group g) {
        for (String id : new String[]{g.expected(), g.found()}) {
            try {
                var item = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)).asItem();
                if (item != net.minecraft.world.item.Items.AIR) return new net.minecraft.world.item.ItemStack(item);
            } catch (RuntimeException ignored) { }
        }
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    static net.minecraft.world.item.ItemStack blockIcon(String blockId) {
        try {
            var item = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId)).asItem();
            return item == net.minecraft.world.item.Items.AIR ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(item);
        } catch (RuntimeException e) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    List<Group> groups() { return groups(null); }

    static String describe(Group g) {
        String exp = blockName(g.expected()), found = blockName(g.found());
        return switch (g.type()) {
            case 0 -> "缺失 " + exp;
            case 1 -> "错误 " + found + " → " + exp;
            case 2 -> "多余 " + found;
            case 4 -> "正确 " + exp;
            default -> "状态错误 " + exp;
        };
    }

    private List<SchematicVerifier.MismatchRenderPos> renderList = List.of();
    private List<BlockPos> renderPositions = List.of();

    List<SchematicVerifier.MismatchRenderPos> renderList() { return renderList; }

    List<BlockPos> renderPositions() { return renderPositions; }

    /** 团队类型 -> Litematica 的 MismatchType (颜色来自玩家在 Litematica 里的配置)。 */
    static SchematicVerifier.MismatchType litematicaType(int type) {
        return switch (type) {
            case 0 -> SchematicVerifier.MismatchType.MISSING;
            case 1 -> SchematicVerifier.MismatchType.WRONG_BLOCK;
            case 2 -> SchematicVerifier.MismatchType.EXTRA;
            case 4 -> SchematicVerifier.MismatchType.CORRECT_STATE;
            default -> SchematicVerifier.MismatchType.WRONG_STATE;
        };
    }

    /** 这一类在 Litematica 里的颜色 (RGB，界面文字也用它，和投影模组保持一致)。 */
    static int colorOf(int type) {
        return litematicaType(type).getColor().intValue & 0xFFFFFF;
    }

    /** 文字里用的颜色标记，如 &#FF5555。 */
    static String tag(int type) {
        return String.format("&#%06X", colorOf(type));
    }

    /** 按当前选择重新算「要交给 Litematica 渲染器高亮的位置」(只算玩家附近、最多 2000 个)，由会话每隔几 tick 调用。 */
    void refreshRender(BlockPos player, Set<String> filter) {
        if (!hasSelection() || entries.isEmpty()) {
            renderList = List.of();
            renderPositions = List.of();
            return;
        }
        List<SchematicVerifier.MismatchRenderPos> list = new ArrayList<>();
        List<BlockPos> plain = new ArrayList<>();
        outer:
        for (Entry e : entries.values()) {
            int cx = (int) (e.chunk() >> 32), cz = (int) e.chunk();
            if (Math.abs((cx << 4) - player.getX()) > 96 || Math.abs((cz << 4) - player.getZ()) > 96) continue;
            Chunk c = e.data();
            for (int p : c.positions()) {
                int gi = p >>> 24;
                if (gi >= c.groups().size()) continue;
                Group g = c.groups().get(gi);
                if (!allowed(filter, g.region())) continue;
                if (!picked.isEmpty()) {
                    if (!picked.contains(g.key())) continue; // 列表里多选了分组: 只高亮这些
                } else {
                    if (g.type() != selection.type() && (selection.type() != 99 || g.type() == 4)) continue; // 「全部」只含问题类，不含正确
                    if (!selection.groupKey().isEmpty() && !g.key().equals(selection.groupKey())) continue;
                }
                BlockPos pos = new BlockPos((cx << 4) + ((p >> 4) & 15), ((p >> 8) & 0xFFF) - 64, (cz << 4) + (p & 15));
                list.add(new SchematicVerifier.MismatchRenderPos(litematicaType(g.type()), pos));
                plain.add(pos);
                if (list.size() >= 2000) break outer;
            }
        }
        renderList = list;
        renderPositions = plain;
    }
}
