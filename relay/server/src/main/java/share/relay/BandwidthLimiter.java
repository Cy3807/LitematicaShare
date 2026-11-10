package share.relay;

/**
 * 所有通道、所有连接共用的下载限速。按"预约发送时刻"排队，多个传输按块公平交错，不允许突发。
 */
public final class BandwidthLimiter {
    private final double nanosPerByte;
    private long nextFreeNanos = System.nanoTime();

    public BandwidthLimiter(long bytesPerSecond) {
        if (bytesPerSecond <= 0) throw new IllegalArgumentException("rate must be positive");
        this.nanosPerByte = 1_000_000_000.0 / bytesPerSecond;
    }

    public static long mbpsToBytesPerSecond(double mbps) {
        return (long) (mbps * 1_000_000 / 8);
    }

    /** 阻塞到允许发送 bytes 字节为止。 */
    public void acquire(int bytes) throws InterruptedException {
        long start;
        synchronized (this) {
            long now = System.nanoTime();
            start = Math.max(now, nextFreeNanos);
            nextFreeNanos = start + (long) (bytes * nanosPerByte);
        }
        long wait = start - System.nanoTime();
        if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
    }
}
