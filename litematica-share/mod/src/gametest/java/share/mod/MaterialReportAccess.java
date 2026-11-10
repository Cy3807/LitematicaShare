package share.mod;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;

import java.util.Map;
import java.util.TreeMap;

/** 测试用: MaterialReport 是包内可见的，这里在同包下暴露「方块 id -> 总需求」。 */
public final class MaterialReportAccess {
    public static Map<String, Long> totals(SchematicPlacement p) {
        Map<String, Long> out = new TreeMap<>();
        MaterialReport.totals(p).forEach((k, r) -> out.put(k, r.total()));
        return out;
    }

    private MaterialReportAccess() { }
}
