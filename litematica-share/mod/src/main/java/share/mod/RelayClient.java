package share.mod;

import share.protocol.Bulk;
import share.protocol.LitematicaShare;
import share.protocol.Packet;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/** 到转发 jar 的连接。收发在后台线程，收到的包通过 onPacket 回调(调用方负责切回游戏线程)。 */
public final class RelayClient implements AutoCloseable {
    private static final Packet CLOSE = new Packet((byte) 0, new byte[0]);

    private final Socket socket = new Socket();
    private final BlockingQueue<Packet> outQueue = new LinkedBlockingQueue<>();
    private final Consumer<Packet> onPacket;
    private final Consumer<String> onClosed;
    private volatile boolean closed;
    private volatile boolean closedByUs;
    private volatile boolean endedByUs;
    private String reason;
    private volatile long lastRx = System.currentTimeMillis();

    public RelayClient(String host, int port, Packet join, Consumer<Packet> onPacket, Consumer<String> onClosed) {
        this.onPacket = onPacket;
        this.onClosed = onClosed;
        outQueue.add(Packet.of(Packet.HELLO).str(LitematicaShare.CHANNEL).i32(LitematicaShare.VERSION).build());
        outQueue.add(join);
        Thread.ofVirtual().name("litematica-share-net").start(() -> run(host, port));
    }

    private void run(String host, int port) {
        try (socket) {
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(host, port), 8000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Packet p = outQueue.take();
                        if (p == CLOSE) return;
                        p.write(out);
                    }
                } catch (IOException | InterruptedException e) {
                    close();
                }
            });
            Thread.ofVirtual().name("litematica-share-ping").start(this::heartbeat);
            Packet hello = Packet.read(in);
            if (hello.type != Packet.HELLO) throw new IOException("bad handshake");
            String refused = Packet.readStr(hello.reader());
            if (!refused.isEmpty()) throw new IOException("relay refused: " + refused);
            Bulk.Assembler assembler = new Bulk.Assembler();
            while (!closed) {
                Packet raw = Packet.read(in);
                lastRx = System.currentTimeMillis();
                Packet p = assembler.accept(raw); // 大文件由转发服务器分块限速下发，这里还原
                if (p != null) onPacket.accept(p);
            }
        } catch (IOException e) {
            if (!closed) ShareMod.LOGGER.warn("relay connection lost: {}", e.toString());
            reason = closedByUs ? null : (e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            endedByUs = closedByUs; // 先记下是不是我们主动关的 (close() 会把 closedByUs 置位)
            close();
            onClosed.accept(reason);
        }
    }

    /** 每 20 秒发一次心跳，防止隧道/NAT 把空闲连接断掉；75 秒没收到任何数据就判定连接已死，主动断开以便自动重连。 */
    private void heartbeat() {
        try {
            while (!closed) {
                Thread.sleep(20_000);
                if (closed) return;
                send(Packet.of(LitematicaShare.PING).build());
                if (System.currentTimeMillis() - lastRx > 75_000) {
                    ShareMod.LOGGER.warn("relay heartbeat timed out");
                    closed = true; // 不是我们主动关的，保持 closedByUs=false 以触发自动重连
                    outQueue.add(CLOSE);
                    try { socket.close(); } catch (IOException ignored) { }
                    return;
                }
            }
        } catch (InterruptedException ignored) { }
    }

    /** 连接结束时是不是我们主动关闭的 (onClosed 回调里使用)。 */
    public boolean closedByUs() { return endedByUs; }

    public void send(Packet p) {
        if (!closed) outQueue.add(p);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closedByUs = true;
        outQueue.add(CLOSE);
        try { socket.close(); } catch (IOException ignored) {}
    }
}
