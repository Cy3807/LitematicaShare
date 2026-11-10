package share.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** 所有模组共用的帧格式: int 长度(不含自身) | byte 类型 | 正文。所有字符串为 UTF-8 并带 int 长度前缀。 */
public final class Packet {
    /** 连接后客户端发的第一帧: channel(字符串, 即模组 id), int 协议版本。其余类型由各通道自己定义。 */
    public static final byte HELLO = 127;
    /** 大数据(下载)的分块帧，见 {@link Bulk}。发送方走限速，接收方由 Bulk.Assembler 透明还原成原包。 */
    public static final byte BULK = 126;

    public static final int MAX_FRAME = 64 * 1024 * 1024;

    public final byte type;
    public final byte[] body;

    public Packet(byte type, byte[] body) {
        this.type = type;
        this.body = body;
    }

    public static Packet read(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 1 || len > MAX_FRAME) throw new IOException("bad frame length " + len);
        byte type = in.readByte();
        byte[] body = new byte[len - 1];
        in.readFully(body);
        return new Packet(type, body);
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(body.length + 1);
        out.writeByte(type);
        out.write(body);
        out.flush();
    }

    public DataInputStream reader() {
        return new DataInputStream(new ByteArrayInputStream(body));
    }

    public static Builder of(byte type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final byte type;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(buf);

        private Builder(byte type) { this.type = type; }

        public Builder str(String s) {
            try {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                out.writeInt(b.length);
                out.write(b);
            } catch (IOException e) { throw new UncheckedIOException(e); }
            return this;
        }

        public Builder bool(boolean v) {
            try { out.writeBoolean(v); } catch (IOException e) { throw new UncheckedIOException(e); }
            return this;
        }

        public Builder i32(int v) {
            try { out.writeInt(v); } catch (IOException e) { throw new UncheckedIOException(e); }
            return this;
        }

        public Builder i64(long v) {
            try { out.writeLong(v); } catch (IOException e) { throw new UncheckedIOException(e); }
            return this;
        }

        public Builder bytes(byte[] b) {
            try { out.writeInt(b.length); out.write(b); } catch (IOException e) { throw new UncheckedIOException(e); }
            return this;
        }

        public Packet build() { return new Packet(type, buf.toByteArray()); }
    }

    public static String readStr(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_FRAME) throw new IOException("bad string length");
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public static byte[] readBytes(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_FRAME) throw new IOException("bad bytes length");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }
}
