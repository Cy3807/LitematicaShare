package share.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/litematica-share.json。模组不内置任何服务器信息，地址由玩家用 /lshare server 命令设置。 */
public final class ShareConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String host = "";
    public int port = 0;
    public String room = "";
    public String key = "";
    /** 进入世界时自动重新加入上次的房间；/lshare leave 后关闭。 */
    public boolean autoJoin = true;
    /** 把自己背包里的物品共享给房间(材料列表的「已有」会算上)；/ls inv off 关闭。 */
    public boolean shareInventory = true;
    /** 搜索/点材料高亮期间，打开箱子自动把选中的物品拿出来。 */
    public boolean autoTake = true;
    /** 材料列表的「已有」是否计入背包 (我的背包 + 别人共享的背包)；关了就只算允许上传的箱子。 */
    public boolean countInventory = true;
    /** 只扫描玩家附近这么多区块半径内的区块 (服务器只发玩家附近几个区块，更远的可能是缓存/过期数据，不能拿来验证)。 */
    public int scanRadius = 3;
    /** 启动游戏进入世界时自动检查并下载新版本 (需要已设置转发服务器地址)；/ls update on|off。 */
    public boolean autoUpdate = true;
    /** 本机是否显示共享投影 (投影页的「投影」开关)；关闭时不显示、不扫描、不加载材料和验证列表。 */
    public boolean showProjection = true;
    /** 关闭投影显示前，Litematica「渲染原理图」开关的原值 (还原用；null = 没改过)。 */
    public Boolean renderingBackup = null;

    /** 本机保存的房间 (可以加入多个，但同一时间只连接并加载一个房间的投影数据)。 */
    public static final class Saved {
        public String name = "";
        public String key = "";
    }

    public java.util.List<Saved> rooms = new java.util.ArrayList<>();

    /** 每个房间本机已经有的投影文件 (房间名 -> sha 和路径)，加入时告诉转发端，同一个文件就不用重新下载。 */
    public static final class RoomFile {
        public String sha = "";
        public String path = "";
    }

    public java.util.Map<String, RoomFile> roomFiles = new java.util.HashMap<>();

    /** 本机已有的该房间投影文件 (文件还在才算)。 */
    public RoomFile cachedFile(String room) {
        RoomFile f = roomFiles == null ? null : roomFiles.get(room);
        return f != null && !f.sha.isEmpty() && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(f.path)) ? f : null;
    }

    public void rememberFile(String room, String sha, java.nio.file.Path path) {
        if (roomFiles == null) roomFiles = new java.util.HashMap<>();
        RoomFile f = new RoomFile();
        f.sha = sha;
        f.path = path.toString();
        roomFiles.put(room, f);
        save();
    }

    public void remember(String name, String key) {
        for (Saved r : rooms) {
            if (r.name.equals(name)) { r.key = key; return; }
        }
        Saved s = new Saved();
        s.name = name;
        s.key = key;
        rooms.add(s);
    }

    public void forget(String name) {
        rooms.removeIf(r -> r.name.equals(name));
    }

    public boolean hasRoom() { return !room.isBlank(); }

    public boolean hasServer() { return !host.isBlank() && port > 0 && port <= 65535; }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("litematica-share.json");
    }

    public static ShareConfig load() {
        try {
            if (Files.exists(path())) {
                ShareConfig c = GSON.fromJson(Files.readString(path()), ShareConfig.class);
                if (c.rooms == null) c.rooms = new java.util.ArrayList<>();
                if (c.roomFiles == null) c.roomFiles = new java.util.HashMap<>();
                if (c.hasRoom()) c.remember(c.room, c.key); // 升级前只存了一个房间，并进列表
                return c;
            }
        } catch (IOException | RuntimeException e) {
            ShareMod.LOGGER.warn("failed to read config, using defaults", e);
        }
        ShareConfig c = new ShareConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            ShareMod.LOGGER.warn("failed to save config", e);
        }
    }
}
