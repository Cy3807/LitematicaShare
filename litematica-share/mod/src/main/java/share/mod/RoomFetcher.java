package share.mod;

import share.protocol.LitematicaShare;
import share.protocol.Packet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 向转发服务器要整个服务器的房间列表 (不加入任何房间，不需要密钥)。 */
final class RoomFetcher {
    record Info(String name, int online, boolean sharing, String projection, boolean taskMode) { }

    static CompletableFuture<List<Info>> fetch(String host, int port) {
        CompletableFuture<List<Info>> f = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 5000);
                s.setSoTimeout(5000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                Packet.of(Packet.HELLO).str(LitematicaShare.CHANNEL).i32(LitematicaShare.VERSION).build().write(out);
                Packet hello = Packet.read(in);
                String refused = Packet.readStr(hello.reader());
                if (!refused.isEmpty()) throw new IOException("relay refused: " + refused);
                Packet.of(LitematicaShare.ROOM_LIST).build().write(out);
                Packet p;
                do { p = Packet.read(in); } while (p.type != LitematicaShare.ROOMS);
                DataInputStream r = p.reader();
                int n = r.readInt();
                List<Info> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(new Info(Packet.readStr(r), r.readInt(), r.readBoolean(), Packet.readStr(r), r.readBoolean()));
                f.complete(list);
            } catch (IOException | RuntimeException e) {
                f.completeExceptionally(e);
            }
        }, "litematica-share-roomlist");
        t.setDaemon(true);
        t.start();
        return f;
    }

    private RoomFetcher() { }
}
