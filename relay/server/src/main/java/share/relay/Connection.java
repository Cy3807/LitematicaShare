package share.relay;

import share.protocol.Bulk;
import share.protocol.Packet;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一条客户端连接。send 线程安全，read 只由该连接的线程调用。
 * 大于 {@link Bulk#THRESHOLD} 的包会自动走限速的分块下发(异步，不阻塞调用方)，所有通道无需关心。
 */
public final class Connection {
    private static final AtomicInteger BULK_IDS = new AtomicInteger();

    private final Socket socket;
    private final BandwidthLimiter limiter;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ConcurrentLinkedQueue<Packet> bulkQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private volatile boolean closed;

    Connection(Socket socket, BandwidthLimiter limiter) throws IOException {
        this.socket = socket;
        this.limiter = limiter;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public Packet read() throws IOException { return Packet.read(in); }

    public void send(Packet p) {
        if (p.body.length > Bulk.THRESHOLD) {
            bulkQueue.add(p);
            startDrain();
        } else {
            sendNow(p);
        }
    }

    private synchronized void sendNow(Packet p) {
        if (closed) return;
        try { p.write(out); } catch (IOException e) { close(); }
    }

    private void startDrain() {
        if (!draining.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                do {
                    Packet p;
                    while (!closed && (p = bulkQueue.poll()) != null) sendBulk(p);
                    draining.set(false);
                } while (!closed && !bulkQueue.isEmpty() && draining.compareAndSet(false, true));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "relay-bulk");
        t.setDaemon(true);
        t.start();
    }

    private void sendBulk(Packet p) throws InterruptedException {
        for (Packet chunk : Bulk.split(p, BULK_IDS.incrementAndGet())) {
            if (closed) return;
            limiter.acquire(chunk.body.length + 5);
            sendNow(chunk);
        }
    }

    public void setReadTimeout(int millis) throws IOException { socket.setSoTimeout(millis); }

    public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
