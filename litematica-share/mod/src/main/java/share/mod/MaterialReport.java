package share.mod;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette;
import fi.dy.masa.litematica.schematic.container.LitematicaBitArray;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import share.protocol.LitematicaShare;

import java.util.*;

/** 把房间汇总的进度和原理图的总需求合起来: 生成 Litematica 材料列表条目和文字清单。统计单位是物品(和 Litematica 材料列表一致)。 */
final class MaterialReport {
    record Row(String item, String name, ItemStack sample, long total, long observedNeed, long placed) {
        long missingObserved() { return Math.max(0, observedNeed - placed); }
        long unobserved() { return Math.max(0, total - observedNeed); }
        long missing() { return missingObserved() + unobserved(); }
    }

    /** 每个启用的子区域里每种物品的总需求 (空气不算): 子区域 -> 物品 id -> 行。 */
    static Map<String, Map<String, Row>> totalsByRegion(SchematicPlacement placement) {
        Map<String, Map<String, Row>> out = new TreeMap<>();
        LitematicaSchematic schematic = placement.getSchematic();
        for (String region : placement.getEnabledRelativeSubRegionPlacements().keySet()) {
            LitematicaBlockStateContainer c = schematic.getSubRegionContainer(region);
            if (c == null) continue;
            // getBlockCounts() 只有部分来源的原理图才有，这里直接数调色板编号，和来源无关
            ILitematicaBlockStatePalette pal = c.getPalette();
            LitematicaBitArray arr = c.getArray();
            long[] counts = new long[pal.getPaletteSize()];
            for (long i = 0, n = arr.size(); i < n; i++) {
                int id = arr.getAt(i);
                if (id >= 0 && id < counts.length) counts[id]++;
            }
            Map<String, long[]> total = new TreeMap<>();
            Map<String, ItemStack> samples = new HashMap<>();
            for (int i = 0; i < counts.length; i++) {
                BlockState s = pal.getBlockState(i);
                if (s == null || s.isAir() || counts[i] <= 0) continue;
                for (ItemStack st : ProgressScanner.itemsFor(s)) {
                    String key = ProgressScanner.itemKey(st);
                    total.computeIfAbsent(key, k -> new long[1])[0] += counts[i] * st.getCount();
                    samples.putIfAbsent(key, st.copyWithCount(1));
                }
            }
            Map<String, Row> rows = new TreeMap<>();
            total.forEach((key, t) -> rows.put(key, new Row(key, samples.get(key).getHoverName().getString(), samples.get(key), t[0], 0, 0)));
            out.put(region, rows);
        }
        return out;
    }

    /** 整个投影(所有启用的子区域)每种物品的总需求，key=物品 id。 */
    static Map<String, Row> totals(SchematicPlacement placement) {
        return flatten(totalsByRegion(placement), null);
    }

    /** 把各子区域的总需求加起来 (filter 为 null 表示全部子区域)。 */
    static Map<String, Row> flatten(Map<String, Map<String, Row>> byRegion, Set<String> filter) {
        Map<String, Row> rows = new TreeMap<>();
        byRegion.forEach((region, items) -> {
            if (filter != null && !filter.contains(region)) return;
            items.forEach((id, r) -> rows.merge(id, r, (a, b) -> new Row(a.item(), a.name(), a.sample(), a.total() + b.total(), 0, 0)));
        });
        return rows;
    }

    /**
     * 合并房间汇总(观测区内 需要/已摆)后的行，缺口大的在前。observed 的 key 是「子区域+分隔符+物品 id」；
     * 没带子区域的旧数据(key 里没有分隔符)只在 filter 为 null 时计入。
     */
    static List<Row> merge(Map<String, Map<String, Row>> byRegion, Map<String, long[]> observed, Set<String> filter) {
        Map<String, Row> rows = flatten(byRegion, filter);
        Map<String, long[]> obs = new HashMap<>();
        observed.forEach((key, v) -> {
            int k = key.indexOf(LitematicaShare.REGION_SEP);
            String region = k < 0 ? "" : key.substring(0, k);
            String item = k < 0 ? key : key.substring(k + 1);
            if (filter != null && (k < 0 || !filter.contains(region))) return;
            long[] t = obs.computeIfAbsent(item, x -> new long[2]);
            t[0] += v[0];
            t[1] += v[1];
        });
        rows.replaceAll((id, r) -> {
            long[] o = obs.get(id);
            return o == null ? r : new Row(r.item(), r.name(), r.sample(), r.total(), o[0], o[1]);
        });
        List<Row> sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparingLong(Row::missing).reversed());
        return sorted;
    }

    /** 兼容: 不筛选。 */
    static List<Row> merge(SchematicPlacement placement, Map<String, long[]> observed) {
        return merge(totalsByRegion(placement), observed, null);
    }

    private MaterialReport() { }
}
