package share.protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 共享箱子库存索引里的一条记录: 一个容器(箱子/大箱子/木桶/潜影盒)在某个维度某个坐标的内容摘要。
 * 大箱子用较小的那一半的坐标作为坐标。
 * time: 客户端上报时填 0；转发端存的是绝对时间(毫秒)，下发给客户端时换成"多久之前"(毫秒)，避免两边时钟不一致。
 */
public record ChestRecord(String dim, int x, int y, int z, String kind, String reporter, long time, Map<String, Integer> items) {
    public static final int MAX_ITEMS = 512;

    public String key() {
        return dim + "|" + x + "," + y + "," + z;
    }

    public ChestRecord withTime(String reporter, long time) {
        return new ChestRecord(dim, x, y, z, kind, reporter, time, items);
    }

    public Packet.Builder write(Packet.Builder b) {
        b.str(dim).i32(x).i32(y).i32(z).str(kind).str(reporter).i64(time).i32(items.size());
        items.forEach((id, n) -> b.str(id).i32(n));
        return b;
    }

    public static ChestRecord read(DataInputStream r) throws IOException {
        String dim = Packet.readStr(r);
        int x = r.readInt(), y = r.readInt(), z = r.readInt();
        String kind = Packet.readStr(r), reporter = Packet.readStr(r);
        long time = r.readLong();
        int n = r.readInt();
        if (n < 0 || n > MAX_ITEMS) throw new IOException("bad item count " + n);
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) items.put(Packet.readStr(r), r.readInt());
        return new ChestRecord(dim, x, y, z, kind, reporter, time, items);
    }
}
