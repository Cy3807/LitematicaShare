package share.mod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import share.protocol.ChestRecord;

import java.util.*;

/** 房间里所有人上报的容器内容，本机完整保存一份，搜索/高亮/材料提示都在本机算。record.time 在这里是本机的绝对时间(毫秒)。 */
final class ChestIndex {
    record Hit(ChestRecord rec, int count) { }

    /** 允许上传的容器位置 (大箱子用较小一半的坐标)。 */
    record Pos(String dim, int x, int y, int z) {
        String key() { return dim + "|" + x + "," + y + "," + z; }
    }

    private final Map<String, ChestRecord> map = new LinkedHashMap<>();
    private final Map<String, Pos> allowed = new LinkedHashMap<>();
    private final Map<String, String> names = new HashMap<>();

    int size() { return map.size(); }

    static String dimName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "主世界";
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            default -> dim;
        };
    }

    static String ago(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) return "刚刚";
        if (s < 3600) return s / 60 + "分钟前";
        if (s < 86400) return s / 3600 + "小时前";
        return s / 86400 + "天前";
    }

    /** 一条搜索结果的一行描述。 */
    static String describe(Hit h) {
        ChestRecord c = h.rec();
        String kind = switch (c.kind()) {
            case "double_chest" -> "大箱子";
            case "barrel" -> "木桶";
            case "shulker" -> "潜影盒";
            default -> "箱子";
        };
        return h.count() + " 个 | " + kind + " " + c.x() + "," + c.y() + "," + c.z() + " | " + dimName(c.dim())
                + " | " + ago(System.currentTimeMillis() - c.time()) + " " + c.reporter();
    }

    List<ChestRecord> all() { return new ArrayList<>(map.values()); }

    /** 放入记录；onlyIfNewer 用于加入房间时的全量同步(可能晚于后到的增量更新)，不能把新数据盖掉。 */
    void put(ChestRecord c, boolean onlyIfNewer) {
        if (!allowed.containsKey(c.key())) return; // 没被管理员允许的容器不收
        ChestRecord old = map.get(c.key());
        if (onlyIfNewer && old != null && old.time() >= c.time()) return;
        map.put(c.key(), c);
    }

    void remove(String dim, int x, int y, int z) {
        map.remove(dim + "|" + x + "," + y + "," + z);
    }

    void clear() { map.clear(); }

    boolean isAllowed(String key) { return allowed.containsKey(key); }

    int allowedCount() { return allowed.size(); }

    List<Pos> allowedAll() { return new ArrayList<>(allowed.values()); }

    void allow(Pos p) { allowed.put(p.key(), p); }

    void disallow(String key) {
        allowed.remove(key);
        map.remove(key);
    }

    /** 用房间下发的全量白名单替换，并丢掉不在白名单里的记录。 */
    void setAllowed(List<Pos> list) {
        allowed.clear();
        for (Pos p : list) allowed.put(p.key(), p);
        map.keySet().removeIf(k -> !allowed.containsKey(k));
    }

    /** 物品显示名 (本机语言)，没有这个物品就返回 id。 */
    String nameOf(String id) {
        return names.computeIfAbsent(id, k -> {
            try {
                var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(k));
                return new ItemStack(item).getHoverName().getString();
            } catch (RuntimeException e) {
                return k;
            }
        });
    }

    /** 某个物品 id 在所有容器里的总数。 */
    long total(String itemId) {
        long t = 0;
        for (ChestRecord c : map.values()) t += c.items().getOrDefault(itemId, 0);
        return t;
    }

    /** 离给定位置最近的、装有该物品的容器 (同维度优先)。 */
    ChestRecord nearest(String itemId, String dim, int x, int y, int z) {
        ChestRecord best = null;
        double bestD = Double.MAX_VALUE;
        for (ChestRecord c : map.values()) {
            if (c.items().getOrDefault(itemId, 0) <= 0) continue;
            double d = c.dim().equals(dim) ? Math.pow(c.x() - x, 2) + Math.pow(c.y() - y, 2) + Math.pow(c.z() - z, 2) : 1e18;
            if (d < bestD) { bestD = d; best = c; }
        }
        return best;
    }

    /** 模糊搜索命中的物品 id 集合 (按物品 id 路径或显示名)。 */
    Set<String> matchIds(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        Set<String> matched = new HashSet<>();
        if (q.isEmpty()) return matched;
        for (ChestRecord c : map.values()) {
            for (String id : c.items().keySet()) {
                String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                if (path.toLowerCase(Locale.ROOT).contains(q) || nameOf(id).toLowerCase(Locale.ROOT).contains(q)) matched.add(id);
            }
        }
        return matched;
    }

    /** 所有允许的箱子里每种物品的总数 (物品 id -> 数量)。 */
    Map<String, Long> allTotals() {
        Map<String, Long> t = new HashMap<>();
        for (ChestRecord c : map.values()) c.items().forEach((id, n) -> t.merge(id, (long) n, Long::sum));
        return t;
    }

    /** 物品 id 对应的图标。 */
    static ItemStack iconOf(String id) {
        try {
            return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id)));
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /** 精确按物品 id 找装有它的容器，数量多的在前。 */
    List<Hit> findItem(String itemId) {
        List<Hit> hits = new ArrayList<>();
        for (ChestRecord c : map.values()) {
            int n = c.items().getOrDefault(itemId, 0);
            if (n > 0) hits.add(new Hit(c, n));
        }
        hits.sort(Comparator.comparingInt(Hit::count).reversed());
        return hits;
    }

    /** 按物品 id 的路径或显示名(不分大小写)模糊搜索，数量多的在前；返回 (物品id, 命中的容器) 的扁平列表。 */
    List<Hit> find(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        if (q.isEmpty()) return hits;
        Set<String> ids = new HashSet<>();
        for (ChestRecord c : map.values()) ids.addAll(c.items().keySet());
        Set<String> matched = new HashSet<>();
        for (String id : ids) {
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            if (path.toLowerCase(Locale.ROOT).contains(q) || nameOf(id).toLowerCase(Locale.ROOT).contains(q)) matched.add(id);
        }
        for (ChestRecord c : map.values()) {
            int n = 0;
            for (String id : matched) n += c.items().getOrDefault(id, 0);
            if (n > 0) hits.add(new Hit(c, n));
        }
        hits.sort(Comparator.comparingInt(Hit::count).reversed());
        return hits;
    }
}
