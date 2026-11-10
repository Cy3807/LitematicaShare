package share.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大包分块: 转发 jar 把需要下发的大数据(如同步文件)拆成小块，限速逐块发送，
 * 块之间可以穿插其他小包。BULK 帧正文: int 传输 id, byte 原类型, int 总长, 余下为这一块的数据。
 */
public final class Bulk {
    public static final int CHUNK_SIZE = 16 * 1024;
    /** 超过这个大小的包一律按大数据处理 (限速)。 */
    public static final int THRESHOLD = 32 * 1024;

    private Bulk() { }

    public static List<Packet> split(Packet p, int id) {
        List<Packet> out = new ArrayList<>();
        int total = p.body.length;
        for (int off = 0; off < total || out.isEmpty(); off += CHUNK_SIZE) {
            int n = Math.min(CHUNK_SIZE, total - off);
            byte[] body = new byte[9 + n];
            java.nio.ByteBuffer.wrap(body).putInt(id).put(p.type).putInt(total).put(p.body, off, n);
            out.add(new Packet(Packet.BULK, body));
        }
        return out;
    }

    /** 接收端: 喂入每个收到的帧，返回要交给上层的包(普通包原样返回，BULK 块在凑齐后还原，否则返回 null)。 */
    public static final class Assembler {
        private static final class Partial {
            final byte type;
            final int total;
            final ByteArrayOutputStream data;

            Partial(byte type, int total) {
                this.type = type;
                this.total = total;
                this.data = new ByteArrayOutputStream(total);
            }
        }

        private final Map<Integer, Partial> partials = new HashMap<>();

        public Packet accept(Packet p) throws IOException {
            if (p.type != Packet.BULK) return p;
            DataInputStream in = p.reader();
            int id = in.readInt();
            byte type = in.readByte();
            int total = in.readInt();
            if (total < 0 || total > Packet.MAX_FRAME) throw new IOException("bad bulk size");
            Partial part = partials.computeIfAbsent(id, k -> new Partial(type, total));
            part.data.write(p.body, 9, p.body.length - 9);
            if (part.data.size() > part.total) throw new IOException("bulk overflow");
            if (part.data.size() < part.total) return null;
            partials.remove(id);
            return new Packet(part.type, part.data.toByteArray());
        }
    }
}
