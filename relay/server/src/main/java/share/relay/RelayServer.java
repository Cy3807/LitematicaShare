package share.relay;

import share.protocol.Packet;
import share.relay.channels.LitematicaShareChannel;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统一转发 jar。所有模组共用一个端口，连接后用 HELLO 里的通道 id 路由到对应模组的处理器。
 * 用法: java -jar minecraft-relay.jar [端口,默认 25570] [下载总上限 Mbps,默认 80]
 * 服务器总带宽 110Mbps，所有通道的大数据下载共用 80Mbps 上限，留 30Mbps 给其他信息转发。
 */
public final class RelayServer {
    public static final int DEFAULT_PORT = 25570;
    public static final double DEFAULT_BULK_MBPS = 80;

    /** 新模组在这里加一行。 */
    static List<Channel> channels(Path dataDir) throws IOException {
        return List.of(new LitematicaShareChannel(dataDir), new share.relay.channels.UpdateChannel(dataDir));
    }

    private final Map<String, Channel> byId;
    /** 每条连接占一个线程；连接数很少，缓存线程池足够 (面板上是 Java 17，没有虚拟线程)。 */
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "relay-conn");
        t.setDaemon(true);
        return t;
    });

    private final BandwidthLimiter limiter;
    private ServerSocket server;

    /** 房间等需要持久化的数据放在这里 (默认 jar 所在目录下的 relay-data，可用 -Drelay.data=路径 修改)。 */
    static Path defaultDataDir() { return Path.of(System.getProperty("relay.data", "relay-data")); }

    public RelayServer() throws IOException { this(DEFAULT_BULK_MBPS); }

    public RelayServer(double bulkMbps) throws IOException {
        this(new BandwidthLimiter(BandwidthLimiter.mbpsToBytesPerSecond(bulkMbps)), defaultDataDir());
    }

    RelayServer(BandwidthLimiter limiter, Path dataDir) throws IOException {
        this.limiter = limiter;
        this.byId = channels(dataDir).stream().collect(Collectors.toMap(Channel::id, Function.identity()));
    }

    /** 子进程用这个退出码告诉监督进程"重启我"。 */
    static final int RESTART_CODE = 75;

    /**
     * 控制台命令: s = 重启 (先落盘，再由监督进程用磁盘上最新的 jar 重新启动，上传新 jar 后输入 s 即可更新)；
     * stop/exit = 真正退出 (面板的停止按钮发的是 stop，所以必须是退出，否则面板会认为停不掉而强制关闭)。面板强制停止(杀进程)时也会先落盘。
     */
    public static void main(String[] args) throws Exception {
        java.nio.file.Path jar = currentJar();
        if (System.getProperty("relay.child") == null && jar != null) {
            supervise(jar, args);
            return;
        }
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        RelayServer s = new RelayServer(args.length > 1 ? Double.parseDouble(args[1]) : DEFAULT_BULK_MBPS);
        s.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(s::flushAll, "relay-flush"));
        System.out.println("relay listening on " + s.port() + ", channels: " + s.byId.keySet() + ", bulk limit " + (args.length > 1 ? args[1] : DEFAULT_BULK_MBPS) + " Mbps");
        System.out.println("console: s = restart, stop/exit = shut down");
        Thread console = new Thread(() -> {
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String cmd = line.trim().toLowerCase();
                    if (cmd.equals("s") || cmd.equals("restart")) {
                        System.out.println("restarting...");
                        System.exit(RESTART_CODE);
                    } else if (cmd.equals("stop") || cmd.equals("exit") || cmd.equals("quit")) {
                        System.out.println("shutting down...");
                        System.exit(0);
                    } else if (!cmd.isEmpty()) {
                        System.out.println("unknown command: " + cmd + " (s = restart, stop = shut down)");
                    }
                }
            } catch (IOException ignored) { }
        }, "relay-console");
        console.setDaemon(true);
        console.start();
    }

    /** 本 jar 的路径；不是从 jar 文件启动(如测试)时返回 null，此时不做监督。 */
    private static java.nio.file.Path currentJar() {
        try {
            java.nio.file.Path p = Path.of(RelayServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return java.nio.file.Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 监督进程: 启动真正的转发服务子进程；子进程以 RESTART_CODE 退出就用磁盘上最新的 jar 重新启动，其他退出码原样退出。 */
    private static void supervise(java.nio.file.Path jar, String[] args) throws Exception {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        while (true) {
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add(javaBin);
            for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (a.startsWith("-D") || a.startsWith("-Xm") || a.startsWith("-Xs")) cmd.add(a);
            }
            cmd.add("-Drelay.child=1");
            cmd.add("-cp");
            cmd.add(jar.toString());
            cmd.add(RelayServer.class.getName());
            cmd.addAll(List.of(args));
            Process child = new ProcessBuilder(cmd).inheritIO().start();
            Thread hook = new Thread(child::destroy, "relay-child-kill");
            Runtime.getRuntime().addShutdownHook(hook);
            int code = child.waitFor();
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { }
            if (code != RESTART_CODE) System.exit(code);
            System.out.println("relay restarted");
        }
    }

    /** 把所有通道还没落盘的数据写到磁盘。 */
    void flushAll() {
        for (Channel c : byId.values()) {
            try { c.flush(); } catch (RuntimeException e) { System.err.println("flush failed: " + e); }
        }
    }

    public void start(int port) throws IOException {
        server = new ServerSocket(port);
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket sock = server.accept();
                    WORKERS.execute(() -> serve(sock));
                } catch (IOException e) {
                    return;
                }
            }
        }, "relay-accept");
        acceptor.start();
    }

    public int port() { return server.getLocalPort(); }

    public void stop() throws IOException { server.close(); }

    private void serve(Socket sock) {
        try (sock) {
            Connection conn = new Connection(sock, limiter);
            conn.setReadTimeout(30_000);
            Packet hello = conn.read();
            if (hello.type != Packet.HELLO) return;
            DataInputStream r = hello.reader();
            String id = Packet.readStr(r);
            int version = r.readInt();
            Channel ch = byId.get(id);
            if (ch == null || ch.version() != version) {
                conn.send(Packet.of(Packet.HELLO).str("unsupported channel " + id + " v" + version).build());
                return;
            }
            conn.send(Packet.of(Packet.HELLO).str("").build());
            conn.setReadTimeout(60_000);
            ch.handle(conn);
        } catch (IOException ignored) {
        }
    }
}
