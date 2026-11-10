package share.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import share.protocol.ChestRecord;
import share.protocol.LitematicaShare;
import share.protocol.Packet;
import share.protocol.TaskRecord;

import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** 一次房间连接的状态: 管理员侧检测投影变化并推送，成员侧接收并应用。所有方法在游戏线程调用。 */
public final class ShareSession {
    public record Member(String uuid, String name, boolean admin) {}

    private static final String TAG = "[LS] ";
    /** 旧版本用的共享标，本机遗留的旧投影也要认得 (清理、沿用、去标)。 */
    private static final String LEGACY_TAG = "[共享] ";

    private static boolean tagged(String name) { return name.startsWith(TAG) || name.startsWith(LEGACY_TAG); }

    private static String untag(String name) {
        if (name.startsWith(TAG)) return name.substring(TAG.length());
        if (name.startsWith(LEGACY_TAG)) return name.substring(LEGACY_TAG.length());
        return name;
    }
    private static final int SYNC_INTERVAL_TICKS = 10;
    private static final int WAIT_HINT_TICKS = 200;
    private static final long MAX_FILE_BYTES = 60L * 1024 * 1024;

    /** 应用远端放置信息时不弹 Litematica 的提示。 */
    private static final IMessageConsumer QUIET = new IMessageConsumer() {
        @Override public void addMessage(Message.MessageType type, String key, Object... args) { }
        @Override public void addMessage(Message.MessageType type, int lifeTime, String key, Object... args) { }
    };

    private final Minecraft mc = Minecraft.getInstance();
    private final String room;
    private final String selfUuid = mc.getUser().getProfileId().toString();
    private final RelayClient client;

    private boolean admin;
    private boolean joined;
    private boolean fatal;
    private boolean sharing;
    private List<Member> members = List.of();

    // 已收到的文件 (sha -> 本地路径)
    private final Map<String, Path> receivedFiles = new HashMap<>();

    /**
     * 房间里唯一的共享投影在本机的对应物: 发布者是他自己的原投影(ownsShared)，其他人是自动生成的「[共享]」副本。
     * 任何管理员改动它都会同步给所有人；成员的副本被锁定，被改动会自动还原。
     */
    private SchematicPlacement sharedPlacement;
    private String sharedSha = "";
    private boolean ownsShared;
    /** 本机与房间已一致的状态；与当前状态不同说明本机动过它。 */
    private String lastAppliedKey = "";
    private String lastRemoteJson;
    private boolean publishRequested;
    /** 房间当前共享的投影文件的 sha (转发端保存的那份)，和房间里是否有共享的投影文件；关共享只是暂停，它们保留。 */
    private String roomSha = "";
    private boolean roomHasProjection;
    /** 房间的子任务 (管理员建立、分配给玩家，可对应共享投影的某个子区域)。 */
    private List<TaskRecord> tasks = List.of();
    private boolean tasksSeen;
    private String locateTaskId = "";
    private BlockPos locateTarget;
    private XaeroLink.Handle locateHandle;
    /** 渲染层同步: 管理员的 Litematica 渲染层(只显示某一层/某个范围)同步给所有人；成员改了会被还原。 */
    private boolean layerSync;
    private String lastLayerJson = "";
    /** 团队验证: 开启后所有成员的扫描额外上报逐方块的缺失/错误/多余，这里汇总。 */
    private boolean verifyOn;
    private final VerifyModel verify = new VerifyModel();
    /** 手动请求(/ls m 等)的回复才在聊天栏汇报；房间主动推送的更新静默刷新列表。 */
    private boolean manualMaterials;
    /** 实时同步团队进度: 房间有新进度时自动刷新材料列表，不用重新验证。第一次手动请求后默认开启。 */
    private boolean liveMaterials = true;
    private boolean liveRequested;
    /** 加入房间后头几秒是在接收房间的当前状态 (同步/验证/模式/渲染层)，不是有人刚改了它，这段时间不提示；之后别人改了才提示。 */
    private int quietTicks = 100;

    private void stateMsg(String text) {
        if (quietTicks <= 0) msg(text);
    }
    /** 房间里共享投影的启用状态 (本机隐藏投影时 Litematica 里是关的，但房间状态不变，也不会被当成改动推送)。 */
    private boolean roomEnabledFlag = true; // (已不再用于隐藏投影，保留为房间状态记录)
    private List<MaterialReport.Row> lastRows;
    /** 我自己背包里的物品(每秒刷新) 和其他成员共享的背包。「已有数量」= 我的背包 + 别人共享的背包 + 允许上传的箱子。 */
    private Map<String, Integer> ownInv = new TreeMap<>();
    private Map<String, Integer> lastInvSent;
    private final Map<String, Map<String, Integer>> otherInv = new HashMap<>();
    private int invTick;
    private Map<String, Map<String, MaterialReport.Row>> totalsCache;
    private String totalsCacheKey = "";
    private String progressLine = "";

    /** 管理员的修改模式: 关闭时本机怎么改都不会同步出去，也暂不跟随其他管理员的改动 (开启后再处理)。 */
    private boolean pushMode = true;
    private String heldRemote;

    private Path oversized;
    private String lastHint = "";

    // 文件按限速分块下发，位置信息可能先于文件到达，先存起来等文件到齐再应用
    private String pendingPlacement;

    private final ProgressScanner scanner = new ProgressScanner(this);
    private final ChestIndex chests = new ChestIndex();
    private final ChestTracker tracker;
    private int tick;
    private int waitTicks;

    public ShareSession(ShareConfig cfg) {
        this.room = cfg.room;
        ShareConfig.RoomFile have = cfg.cachedFile(cfg.room);
        Packet join = Packet.of(LitematicaShare.JOIN).str(cfg.room).str(cfg.key)
                .str(selfUuid).str(mc.getUser().getName()).str(have == null ? "" : have.sha).build();
        this.client = new RelayClient(cfg.host, cfg.port, join,
                p -> mc.execute(() -> onPacket(p)),
                reason -> mc.execute(() -> {
                    releaseLock();
                    boolean refused = reason != null && reason.startsWith("relay refused");
                    boolean retry = !closedByUs() && !refused && !fatal; // 版本不符/密钥错误重试也没用
                    if (refused && ShareMod.config().autoUpdate && Updater.enabled()) ShareMod.checkUpdate(false); // 版本不符: 看看有没有新版本可更新
                    ShareMod.sessionClosed(this, retry, reason);
                }));
        this.tracker = new ChestTracker(chests, mc.getUser().getName(),
                c -> client.send(c.write(Packet.of(LitematicaShare.CHEST_REPORT)).build()),
                c -> client.send(Packet.of(LitematicaShare.CHEST_REMOVE).str(c.dim()).i32(c.x()).i32(c.y()).i32(c.z()).build()),
                (t, allow) -> client.send(Packet.of(LitematicaShare.CHEST_ALLOW).str(t.dim()).i32(t.pos().getX()).i32(t.pos().getY()).i32(t.pos().getZ()).bool(allow).build()),
                this::msg);
    }

    private boolean closedByUs() { return client.closedByUs(); }

    public boolean isAdmin() { return admin; }
    public boolean isJoined() { return joined; }
    public String roomName() { return room; }
    public boolean isSharing() { return sharing; }
    public List<Member> members() { return members; }

    /** 切换到别的房间前: 清掉这个房间的共享副本(只加载一个房间的投影数据)，自己发布的原投影去掉共享标并恢复所有子区域。 */
    public void leaveCleanup() {
        cancelLocate(false);
        releaseLock();
        if (sharedPlacement != null && inManager(sharedPlacement)) {
            if (!ownsShared) {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(sharedPlacement);
            } else {
                for (SubRegionPlacement r : new ArrayList<>(sharedPlacement.getAllSubRegionsPlacements())) {
                    if (!r.isEnabled()) sharedPlacement.toggleSubRegionEnabled(r.getName(), QUIET);
                }
                if (tagged(sharedPlacement.getName())) sharedPlacement.setName(untag(sharedPlacement.getName()));
            }
        }
        sharedPlacement = null;
        sharedSha = "";
    }

    public void close() {
        cancelLocate(false);
        tracker.dispose();
        releaseLock();
        restoreRendering(); // 离开房间/世界就把 Litematica 的渲染开关还原
        client.close();
    }

    /** 管理员开启共享，并把当前选中的投影发布为房间的共享投影。 */
    public void setSharing(boolean on) {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        client.send(Packet.of(LitematicaShare.SHARE).bool(on).build());
        if (on && !roomHasProjection) publishRequested = true; // 房间里已经有投影文件就直接开回来，不用重新发布
    }

    /** 管理员把当前选中的投影发布为房间的共享投影 (替换原来的)。 */
    public void push() {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        if (!sharing) client.send(Packet.of(LitematicaShare.SHARE).bool(true).build());
        publishRequested = true;
    }

    void sendPacket(Packet p) { client.send(p); }

    public ProgressScanner scanner() { return scanner; }
    public ChestIndex chests() { return chests; }
    public VerifyModel verifyModel() { return verify; }
    public boolean isVerifyOn() { return verifyOn; }
    public List<MaterialReport.Row> materialRows() { return lastRows == null ? List.of() : lastRows; }

    public boolean isLayerSync() { return layerSync; }

    // ---- 渲染层规则 (管理员在界面里直接改；开了同步就会推给所有人) ----
    private static final LayerMode[] LAYER_MODES = {LayerMode.ALL, LayerMode.SINGLE_LAYER, LayerMode.LAYER_RANGE, LayerMode.ALL_ABOVE, LayerMode.ALL_BELOW};
    private static final String[] LAYER_MODE_NAMES = {"全部", "单层", "范围", "以上", "以下"};

    private static int modeIndex(LayerMode m) {
        for (int i = 0; i < LAYER_MODES.length; i++) if (LAYER_MODES[i] == m) return i;
        return 0;
    }

    public String layerModeName() { return LAYER_MODE_NAMES[modeIndex(DataManager.getRenderLayerRange().getLayerMode())]; }

    public String layerAxisName() { return DataManager.getRenderLayerRange().getAxis().name(); }

    /** 当前模式下需要几个数值: 全部 0 个，范围 2 个，其余 1 个。 */
    public int layerValueCount() {
        LayerMode m = DataManager.getRenderLayerRange().getLayerMode();
        return m == LayerMode.ALL ? 0 : m == LayerMode.LAYER_RANGE ? 2 : 1;
    }

    public int layerValue(int which) {
        LayerRange r = DataManager.getRenderLayerRange();
        return switch (r.getLayerMode()) {
            case SINGLE_LAYER -> r.getLayerSingle();
            case ALL_ABOVE -> r.getLayerAbove();
            case ALL_BELOW -> r.getLayerBelow();
            case LAYER_RANGE -> which == 0 ? r.getLayerRangeMin() : r.getLayerRangeMax();
            default -> 0;
        };
    }

    public void cycleLayerMode() {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        LayerRange r = DataManager.getRenderLayerRange();
        r.setLayerMode(LAYER_MODES[(modeIndex(r.getLayerMode()) + 1) % LAYER_MODES.length], false);
    }

    public void cycleLayerAxis() {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        LayerRange r = DataManager.getRenderLayerRange();
        Direction.Axis[] axes = Direction.Axis.values();
        r.setAxis(axes[(r.getAxis().ordinal() + 1) % axes.length]);
    }

    public void setLayerValue(int which, int v) {
        if (!admin) return;
        LayerRange r = DataManager.getRenderLayerRange();
        switch (r.getLayerMode()) {
            case SINGLE_LAYER -> r.setLayerSingle(v);
            case ALL_ABOVE -> r.setLayerAbove(v);
            case ALL_BELOW -> r.setLayerBelow(v);
            case LAYER_RANGE -> { if (which == 0) r.setLayerRangeMin(v); else r.setLayerRangeMax(v); }
            default -> { }
        }
    }

    /** 把当前模式的层号设到我脚下。 */
    public void layerToPlayer() {
        if (!admin || mc.player == null) return;
        DataManager.getRenderLayerRange().setToPosition(mc.player);
    }

    /** 管理员开关渲染层同步 (开启时把自己当前的渲染层同步给所有人)。 */
    public void setLayerSync(boolean on) {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        lastLayerJson = layerJson();
        client.send(Packet.of(LitematicaShare.LAYER_SET).bool(on).str(lastLayerJson).build());
    }

    private static String layerJson() {
        LayerRange r = DataManager.getRenderLayerRange();
        JsonObject j = new JsonObject();
        j.addProperty("mode", r.getLayerMode().name());
        j.addProperty("axis", r.getAxis().name());
        j.addProperty("single", r.getLayerSingle());
        j.addProperty("above", r.getLayerAbove());
        j.addProperty("below", r.getLayerBelow());
        j.addProperty("min", r.getLayerRangeMin());
        j.addProperty("max", r.getLayerRangeMax());
        return j.toString();
    }

    private static void applyLayer(String json) {
        try {
            JsonObject j = JsonParser.parseString(json).getAsJsonObject();
            LayerRange r = DataManager.getRenderLayerRange();
            r.setAxis(Direction.Axis.valueOf(j.get("axis").getAsString()));
            r.setLayerMode(LayerMode.valueOf(j.get("mode").getAsString()), false);
            r.setLayerSingle(j.get("single").getAsInt());
            r.setLayerAbove(j.get("above").getAsInt());
            r.setLayerBelow(j.get("below").getAsInt());
            r.setLayerRangeMin(j.get("min").getAsInt());
            r.setLayerRangeMax(j.get("max").getAsInt());
        } catch (RuntimeException e) {
            ShareMod.LOGGER.warn("bad layer json {}", json, e);
        }
    }

    /** 每 10 tick: 管理员改了渲染层就推给所有人；成员改了就还原。 */
    private void tickLayer() {
        if (!layerSync) return;
        String now = layerJson();
        if (now.equals(lastLayerJson)) return;
        if (admin) {
            if (!pushMode) return;
            lastLayerJson = now;
            client.send(Packet.of(LitematicaShare.LAYER_SET).bool(true).str(now).build());
        } else if (!lastLayerJson.isEmpty()) {
            applyLayer(lastLayerJson);
        }
    }

    private static Field verifierField;
    private TeamVerifier teamVerifier;
    private VerifyModel.Selection lastSelection = new VerifyModel.Selection(-1, "");
    private int overlayTick;

    private static Object verifierOf(SchematicPlacement p) {
        try {
            if (verifierField == null) {
                verifierField = SchematicPlacement.class.getDeclaredField("verifier");
                verifierField.setAccessible(true);
            }
            return verifierField.get(p);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void setVerifier(SchematicPlacement p, Object v) {
        try {
            verifierOf(p); // 初始化 verifierField
            verifierField.set(p, v);
        } catch (ReflectiveOperationException | RuntimeException e) {
            ShareMod.LOGGER.warn("cannot attach team verifier: {}", e.toString());
        }
    }

    /** 团队验证的高亮交给 Litematica 自己的渲染器: 把团队验证器挂到共享投影上，并选中这个投影；不用时摘掉。 */
    private void tickVerifierOverlay() {
        if (++overlayTick % 5 != 0 || mc.player == null) return;
        boolean want = verifyOn && verify.hasSelection() && sharedPlacement != null && inManager(sharedPlacement);
        if (want) {
            verify.refreshRender(mc.player.blockPosition(), effectiveFilter());
            if (teamVerifier == null) teamVerifier = new TeamVerifier(verify);
            if (verifierOf(sharedPlacement) != teamVerifier) setVerifier(sharedPlacement, teamVerifier);
            SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
            if (!verify.selection().equals(lastSelection) && mgr.getSelectedSchematicPlacement() != sharedPlacement) {
                mgr.setSelectedSchematicPlacement(sharedPlacement); // 投影模组只给选中的投影画验证高亮
            }
        } else if (sharedPlacement != null && teamVerifier != null && verifierOf(sharedPlacement) == teamVerifier) {
            setVerifier(sharedPlacement, null);
        }
        lastSelection = verify.selection();
    }

    /**
     * 首次验证: 加入房间并收完房间当前状态之后，团队验证开着、却一个区块的验证数据都没有 (这份投影还没被验证过，
     * 或投影文件/位置变了数据被作废)。此时扫描不受 scanRadius 限制。
     */
    /** 加入房间后已经收完房间的当前状态 (扫描等到这时才开始，才能判断是不是首次验证)。 */
    boolean isSettled() { return joined && quietTicks <= 0; }

    boolean isFirstVerification() {
        return manualFirstVerification || (joined && quietTicks <= 0 && verifyOn && verify.isEmpty());
    }

    /** 手动开启的首次验证模式 (只在本机、本次连接有效)：不管房间里有没有验证数据，扫描都不限制距离。 */
    private boolean manualFirstVerification;

    public boolean isManualFirstVerification() { return manualFirstVerification; }

    public void setManualFirstVerification(boolean on) {
        manualFirstVerification = on;
        scanner.rescan(); // 立刻按新规则重新排一轮区块
        msg(on ? "&a首次验证模式开 &7扫描不限距离" : "&7首次验证模式关");
    }

    /** 交给 Litematica 渲染器高亮的位置数 (没挂上团队验证器返回 -1)。 */
    public int overlayCount() {
        return sharedPlacement != null && teamVerifier != null && verifierOf(sharedPlacement) == teamVerifier ? verify.renderList().size() : -1;
    }

    // ---------------- 子任务 ----------------

    private void onTasks(DataInputStream r) throws IOException {
        int n = r.readInt();
        List<TaskRecord> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(TaskRecord.read(r));
        Set<String> mineBefore = new HashSet<>();
        for (TaskRecord t : tasks) if (t.assignees().contains(selfUuid)) mineBefore.add(t.id());
        boolean first = !tasksSeen;
        tasksSeen = true;
        tasks = list;
        int open = 0;
        for (TaskRecord t : list) {
            if (!t.assignees().contains(selfUuid) || t.status() == 2) continue;
            open++;
            if (!first && !mineBefore.contains(t.id())) { // 新分配给我的任务
                msg("&a新任务 &f" + t.title() + (t.region().isEmpty() ? "" : " &7(" + t.region() + ")") + " &7/ls t");
            }
        }
        if (first && open > 0) msg("&7你有 &f" + open + " &7个未完成任务 &f/ls t");
    }

    public List<TaskRecord> tasks() { return tasks; }

    public boolean isMine(TaskRecord t) { return t.assignees().contains(selfUuid); }

    /** 成员 UUID -> 显示名 (不在线的显示短 UUID)。 */
    public String nameOf(String uuid) {
        for (Member m : members) if (m.uuid().equals(uuid)) return m.name();
        return uuid.length() > 6 ? uuid.substring(0, 6) : uuid;
    }

    /** 管理员新建/修改任务 (id 为空表示新建)。 */
    public void saveTask(TaskRecord t) {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        if (t.title().isBlank()) { msg("&c请填写任务名"); return; }
        client.send(t.write(Packet.of(LitematicaShare.TASK_SET)).build());
    }

    public void deleteTask(String id) {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        client.send(Packet.of(LitematicaShare.TASK_DELETE).str(id).build());
    }

    /** 被分配的玩家(和管理员)更新任务状态: 0 待办 1 进行中 2 完成。 */
    public void setTaskStatus(String id, int status) {
        client.send(Packet.of(LitematicaShare.TASK_STATUS).str(id).i32(status).build());
    }

    /** 聊天栏列出我的未完成任务 (最多 3 条)。 */
    public void showMyTasks() {
        int shown = 0;
        for (TaskRecord t : tasks) {
            if (!isMine(t) || t.status() == 2) continue;
            if (shown++ >= 3) { msg("&7…还有更多，按 &fL &7打开任务页"); break; }
            msg((t.status() == 1 ? "&e" : "&7") + (t.status() == 1 ? "进行中 " : "待办 ") + "&f" + t.title() + (t.region().isEmpty() ? "" : " &7(" + t.region() + ")"));
        }
        if (shown == 0) msg("&7没有分配给你的未完成任务");
    }

    /**
     * 任务「定位」: 把右上角的区域选择直接改成这个任务的子区域，并在 Xaero 小地图上给它的位置加一个路径点；
     * 走到附近或取消定位时路径点自动删除。
     */
    public void locate(TaskRecord t) {
        if (t.region().isEmpty() || sharedPlacement == null || !inManager(sharedPlacement)) { msg("&c这个任务没有对应的子区域"); return; }
        cancelLocate(false);
        touchFilter(2);
        filterRegions.add(t.region());
        onFilterInputsChanged();
        var box = sharedPlacement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).get(t.region());
        if (box != null && box.getPos1() != null && box.getPos2() != null) {
            BlockPos a = box.getPos1(), b = box.getPos2();
            locateTarget = new BlockPos((a.getX() + b.getX()) / 2, (a.getY() + b.getY()) / 2, (a.getZ() + b.getZ()) / 2);
            locateHandle = XaeroLink.add(locateTarget.getX(), locateTarget.getY(), locateTarget.getZ(), "任务: " + t.title(), t.title().substring(0, 1));
        }
        locateTaskId = t.id();
        msg("&a已定位 &f" + t.region() + (locateHandle != null ? " &7(小地图已加路径点)" : ""));
    }

    public boolean isLocating(String taskId) { return taskId.equals(locateTaskId); }

    /** 取消定位: 删除小地图路径点 (区域选择保持不变)。 */
    public void cancelLocate(boolean message) {
        if (locateTaskId.isEmpty()) return;
        XaeroLink.remove(locateHandle);
        locateHandle = null;
        locateTarget = null;
        locateTaskId = "";
        if (message) msg("&7已取消定位");
    }

    private void tickLocate() {
        if (locateTaskId.isEmpty() || mc.player == null || locateTarget == null) return;
        BlockPos p = mc.player.blockPosition();
        double dx = p.getX() - locateTarget.getX(), dz = p.getZ() - locateTarget.getZ();
        if (dx * dx + dz * dz < 8 * 8) { // 到了: 删掉路径点
            cancelLocate(false);
            msg("&a已到达任务区域");
        }
    }

    /** 手动刷新团队验证: 本机重新扫描并重新上报，同时向转发端要一遍所有区块的最新结果 (每分钟也会自动做一次)。 */
    public void refreshVerify() {
        if (!verifyOn) return;
        verify.reset();
        scanner.rescan();
        client.send(Packet.of(LitematicaShare.VERIFY_REFRESH).build());
        autoRefreshTick = 0;
    }

    private int autoRefreshTick;

    /** 管理员开关团队验证。 */
    public void toggleVerify() {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        if (sharedPlacement == null) { msg("&c还没有共享投影"); return; }
        client.send(Packet.of(LitematicaShare.VERIFY_SET).bool(!verifyOn).build());
    }

    void onOwnVerify(long key, VerifyModel.Chunk c) { verify.put(key, c); }

    // ---------------- 允许上传的箱子 / 箱子模式 / 背包共享 ----------------

    public boolean isChestMode() { return tracker.chestMode(); }

    /** 管理员开关箱子模式: 左键箱子=允许上传，右键箱子=不允许(并且不会打开)。 */
    public void setChestMode(boolean on) {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        tracker.setChestMode(on);
        msg(on ? "&a箱子模式开 &7左键=允许 右键=不允许（绿框=已允许）" : "&7箱子模式关");
    }

    /** 一键删除所有允许的容器和它们的记录。 */
    public void clearAllowed() {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        client.send(Packet.of(LitematicaShare.CHEST_CLEAR).build());
        chests.setAllowed(List.of());
        msg("&7已清空允许的箱子");
    }

    public boolean isAutoTake() { return tracker.autoTake(); }

    public void setAutoTake(boolean on) {
        tracker.setAutoTake(on);
        ShareMod.config().autoTake = on;
        ShareMod.config().save();
        msg(on ? "&a自动拿取开 &7打开箱子时拿出高亮的物品" : "&7自动拿取关");
    }

    /** 背包共享、计入背包、实时同步现在永远开启，没有开关。 */
    public boolean isShareInventory() { return true; }

    /** 材料的「已有数量」: 我的背包 + 其他成员共享的背包 + 允许上传的箱子。 */
    long availableOf(String item) {
        return chests.total(item) + inventoryOf(item);
    }

    /** 背包里的数量: 我的 + 其他成员共享的。 */
    long inventoryOf(String item) {
        long n = ownInv.getOrDefault(item, 0);
        for (Map<String, Integer> m : otherInv.values()) n += m.getOrDefault(item, 0);
        return n;
    }

    public boolean isCountInventory() { return true; }

    private void tickInventory() {
        if (mc.player == null || ++invTick % 20 != 0) return;
        Map<String, Integer> own = new TreeMap<>();
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) ChestTracker.addStack(own, inv.getItem(i));
        ownInv = own;
        if (isShareInventory() && !own.isEmpty() && !own.equals(lastInvSent)) {
            Packet.Builder b = Packet.of(LitematicaShare.INVENTORY).i32(Math.min(own.size(), 2048));
            int n = 0;
            for (Map.Entry<String, Integer> e : own.entrySet()) {
                if (n++ >= 2048) break;
                b.str(e.getKey()).i32(e.getValue());
            }
            client.send(b.build());
            lastInvSent = own;
        }
    }

    public ChestTracker tracker() { return tracker; }

    /** 在共享仓库里搜物品: 聊天栏列出最多的几个容器并高亮。 */
    public void findChests(String query) {
        showHits(query, chests.find(query), chests.matchIds(query), true);
    }

    /** 材料列表里点了某个材料: 精确按物品 id 找箱子。 */
    public void findItem(String itemId, String display) {
        showHits(display, chests.findItem(itemId), Set.of(itemId), true);
    }

    /** 界面里的搜索/点材料: 高亮并记下要自动拿取的物品，不在聊天栏刷屏，返回命中的容器。 */
    public List<ChestIndex.Hit> pick(String query, Set<String> itemIds, List<ChestIndex.Hit> hits) {
        showHits(query, hits, itemIds, false);
        return hits;
    }

    private void showHits(String query, List<ChestIndex.Hit> hits, Set<String> itemIds, boolean chat) {
        tracker.setWanted(hits.isEmpty() ? Set.of() : itemIds);
        if (!chat) {
            tracker.highlight(hits.stream().map(ChestIndex.Hit::rec).toList()); // 所有命中的箱子都高亮 (以前只取数量最多的 20 个，箱子密集时就有的不亮)
            return;
        }
        if (hits.isEmpty()) {
            msg("&c没找到 &f" + query);
            return;
        }
        long total = hits.stream().mapToLong(ChestIndex.Hit::count).sum();
        msg("&f" + query + " &7共 &f" + total + " &7个，&f" + hits.size() + " &7个箱子（已高亮）");
        for (int i = 0; i < Math.min(3, hits.size()); i++) msg("&7 " + ChestIndex.describe(hits.get(i)));
        tracker.highlight(hits.stream().map(ChestIndex.Hit::rec).toList()); // 所有命中的箱子都高亮 (以前只取数量最多的 20 个，箱子密集时就有的不亮)
    }

    /** 向房间请求汇总的材料统计 (界面里的材料清单靠它刷新)。 */
    public void requestMaterials() {
        if (sharedPlacement == null || !inManager(sharedPlacement)) return;
        client.send(Packet.of(LitematicaShare.MATERIALS_REQUEST).build());
    }

    public boolean isLiveMaterials() { return true; }

    /**
     * 本机是否显示投影 (配置 showProjection)。关闭 = 只把 Litematica 的「渲染原理图」总开关关掉 (不画方块)，
     * 投影本身仍然启用，所以扫描、材料列表、验证、仓库照常工作并继续上报；只是本机的显示，不影响房间。
     * 注意它关掉的是 Litematica 的全局渲染开关，本机别的投影也会一起不显示。
     */
    public boolean isProjectionOn() { return ShareMod.config().showProjection; }

    public void setProjectionOn(boolean on) {
        ShareMod.config().showProjection = on;
        applyProjectionVisibility();
        msg(on ? "&a投影已显示" : "&7投影已隐藏 &f材料/验证照常");
    }

    /** 按 showProjection 设置 Litematica 的渲染开关；关闭前的原值记在配置里，开启/离开房间时还原。 */
    void applyProjectionVisibility() {
        ShareConfig cfg = ShareMod.config();
        var flag = fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_SCHEMATIC_RENDERING;
        if (!cfg.showProjection) {
            if (cfg.renderingBackup == null) cfg.renderingBackup = flag.getBooleanValue();
            flag.setBooleanValue(false);
        } else {
            restoreRendering();
        }
        cfg.save();
    }

    /** 把 Litematica 的渲染开关还原成关闭投影之前的值。 */
    void restoreRendering() {
        ShareConfig cfg = ShareMod.config();
        if (cfg.renderingBackup == null) return;
        fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.setBooleanValue(cfg.renderingBackup);
        cfg.renderingBackup = null;
        cfg.save();
    }

    /** 最近一次团队进度的一行摘要 (界面里显示)。 */
    public String progressLine() { return progressLine; }

    public boolean isPushMode() { return pushMode; }

    /** 管理员开关修改模式。关闭: 本机自由调整不影响别人；开启: 本机有改动就推送，没有则追上房间最新状态。 */
    public void setPushMode(boolean on) {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        if (on == pushMode) { msg("&7修改模式已是" + (on ? "开" : "关")); return; }
        pushMode = on;
        if (!on) {
            msg("&7修改模式关 &f/ls pushmode on &7恢复");
            return;
        }
        String held = heldRemote;
        heldRemote = null;
        boolean localChanged = sharedPlacement != null && inManager(sharedPlacement)
                && !stateKey(sharedSha, sharedPlacement).equals(lastAppliedKey);
        if (localChanged) {
            msg("&a修改模式开 &7已同步你的改动");
        } else {
            String json = held != null ? held : (sharedPlacement == null || !inManager(sharedPlacement) ? lastRemoteJson : null);
            if (json != null) onPlacement(json);
            msg("&a修改模式开");
        }
    }

    /** 取消共享投影前先确认；confirmFirst=false 时直接取消。 */
    public void requestUnshare() {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        if (sharedPlacement == null) { msg("&7没有共享投影"); return; }
        Confirm.ask("&c取消房间的共享投影？", "&7所有人的共享副本会消失（你本机的投影保留）", this::unshare, null);
    }

    /** 管理员取消房间的共享投影 (不删除本机投影)。 */
    public void unshare() {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        client.send(Packet.of(LitematicaShare.CLEAR).build());
        publishRequested = false;
    }

    public void setAdmin(String playerName, boolean on) {
        Optional<Member> m = members.stream().filter(x -> x.name().equalsIgnoreCase(playerName)).findFirst();
        if (m.isEmpty()) { msg("&c没有玩家 &f" + playerName); return; }
        client.send(Packet.of(LitematicaShare.SET_ADMIN).str(m.get().uuid()).bool(on).build());
    }

    // ---------------- 收包 ----------------

    private void onPacket(Packet p) {
        try {
            DataInputStream r = p.reader();
            switch (p.type) {
                case LitematicaShare.JOINED -> {
                    joined = true;
                    ShareMod.sessionJoined(this);
                    applyProjectionVisibility();
                    admin = r.readBoolean();
                    boolean created = r.readBoolean();
                    msg("&a已加入 &f" + room + (admin ? " &7(管理员)" : ""));
                }
                case LitematicaShare.ERROR -> {
                    if (!joined) fatal = true; // 加入时被拒 (密钥错误等)，不要自动重试
                    msg("&c" + Packet.readStr(r));
                }
                case LitematicaShare.MEMBERS -> {
                    int n = r.readInt();
                    List<Member> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(new Member(Packet.readStr(r), Packet.readStr(r), r.readBoolean()));
                    announceAdminChanges(members, list);
                    members = list;
                    boolean was = admin;
                    admin = list.stream().anyMatch(m -> m.uuid().equals(selfUuid) && m.admin());
                    if (admin != was) {
                        msg(admin ? "&a你成为了管理员" : "&7你不再是管理员");
                        refreshLock();
                    }
                }
                case LitematicaShare.SHARE_STATE -> {
                    boolean on = r.readBoolean();
                    roomHasProjection = r.readBoolean();
                    if (!roomHasProjection) roomSha = "";
                    if (on != sharing) stateMsg(on ? "&a同步开" : "&7同步关");
                    sharing = on;
                    if (!on) releaseShared();
                }
                case LitematicaShare.FILE_SAME -> onFileSame(Packet.readStr(r));
                case LitematicaShare.CLEARED -> {
                    roomSha = "";
                    roomHasProjection = false;
                    boolean had = sharedPlacement != null;
                    releaseShared();
                    if (had) msg("&7共享投影已取消");
                }
                case LitematicaShare.MATERIALS -> onMaterials(r);
                case LitematicaShare.CHEST_SYNC -> {
                    long now = System.currentTimeMillis();
                    int n = r.readInt();
                    for (int i = 0; i < n; i++) {
                        ChestRecord c = ChestRecord.read(r);
                        chests.put(c.withTime(c.reporter(), now - c.time()), true);
                    }
                }
                case LitematicaShare.CHEST_UPDATE -> {
                    ChestRecord c = ChestRecord.read(r);
                    chests.put(c.withTime(c.reporter(), System.currentTimeMillis() - c.time()), false);
                }
                case LitematicaShare.VERIFY_STATE -> {
                    boolean on = r.readBoolean();
                    if (on != verifyOn) stateMsg(on ? "&a团队验证开" : "&7团队验证关");
                    verifyOn = on;
                    scanner.setVerify(on);
                    if (!on) verify.reset();
                }
                case LitematicaShare.TASKS -> { onTasks(r); onFilterInputsChanged(); }
                case LitematicaShare.MODE_STATE -> {
                    boolean on = r.readBoolean();
                    if (on != taskMode) stateMsg(on ? "&a任务模式" : "&7简单模式（整个投影文件）");
                    taskMode = on;
                    if (!on) filterTouched = false;
                    onFilterInputsChanged();
                }
                case LitematicaShare.VERIFY_RESET -> verify.reset();
                case LitematicaShare.LAYER_STATE -> {
                    boolean on = r.readBoolean();
                    String json = Packet.readStr(r);
                    if (on != layerSync) stateMsg(on ? "&a渲染层同步开" : "&7渲染层同步关");
                    layerSync = on;
                    if (on && !json.isEmpty()) {
                        lastLayerJson = json;
                        if (!admin || !json.equals(layerJson())) applyLayer(json);
                    }
                }
                case LitematicaShare.VERIFY_CHUNK -> {
                    String sha = Packet.readStr(r), pk = Packet.readStr(r);
                    VerifyModel.Chunk[] out = new VerifyModel.Chunk[1];
                    long key = VerifyModel.readChunk(r, out);
                    if (sharedPlacement != null && sha.equals(sharedSha) && pk.equals(ProgressScanner.placeKey(sharedPlacement))) verify.put(key, out[0]);
                }
                case LitematicaShare.CHEST_GONE -> chests.remove(Packet.readStr(r), r.readInt(), r.readInt(), r.readInt());
                case LitematicaShare.CHEST_ALLOWED -> {
                    int n = r.readInt();
                    List<ChestIndex.Pos> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) list.add(new ChestIndex.Pos(Packet.readStr(r), r.readInt(), r.readInt(), r.readInt()));
                    chests.setAllowed(list);
                }
                case LitematicaShare.INV_UPDATE -> {
                    String uuid = Packet.readStr(r);
                    Packet.readStr(r);
                    int n = r.readInt();
                    Map<String, Integer> inv = new HashMap<>();
                    for (int i = 0; i < n; i++) inv.put(Packet.readStr(r), r.readInt());
                    if (!uuid.equals(selfUuid)) otherInv.put(uuid, inv);
                }
                case LitematicaShare.INV_GONE -> otherInv.remove(Packet.readStr(r));
                case LitematicaShare.FILE -> onFile(r);
                case LitematicaShare.PLACEMENT -> onPlacement(Packet.readStr(r));
                default -> { }
            }
        } catch (IOException | RuntimeException e) {
            ShareMod.LOGGER.warn("bad packet type {}", p.type, e);
        }
    }

    /** 其他人被设为/取消管理员时给出提示 (自己的变化由 你现在是管理员 提示)。 */
    private void announceAdminChanges(List<Member> old, List<Member> now) {
        for (Member m : now) {
            if (m.uuid().equals(selfUuid)) continue;
            Optional<Member> before = old.stream().filter(o -> o.uuid().equals(m.uuid())).findFirst();
            if (before.isEmpty() || before.get().admin() == m.admin()) continue;
            msg("&f" + m.name() + (m.admin() ? " &a成为管理员" : " &7不再是管理员"));
        }
    }

    /** 房间的投影文件没变且转发端认为我们已经有了 (或共享关了又开): 用本机缓存的文件，没有就要。 */
    private void onFileSame(String sha) {
        roomSha = sha;
        roomHasProjection = true;
        ShareConfig.RoomFile f = ShareMod.config().cachedFile(room);
        Path path = f != null && f.sha.equals(sha) ? Path.of(f.path) : receivedFiles.get(sha);
        if (path != null && Files.isRegularFile(path)) {
            receivedFiles.put(sha, path);
            if (pendingPlacement != null) {
                String json = pendingPlacement;
                pendingPlacement = null;
                onPlacement(json);
            }
        } else {
            client.send(Packet.of(LitematicaShare.FILE_REQUEST).str(sha).build()); // 本机没有 (被删了/换了电脑): 下载一次
        }
    }

    private void onFile(DataInputStream r) throws IOException {
        String name = Packet.readStr(r), sha = Packet.readStr(r);
        if (!name.endsWith(".litematic") || name.startsWith(".")) { msg("&c收到无效文件"); return; }
        byte[] data = Packet.readBytes(r);
        if (!sha256(data).equals(sha)) { msg("&c文件校验失败"); return; }
        Path dir = schematicDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(safeName(name));
        Files.write(file, data);
        receivedFiles.put(sha, file);
        roomSha = sha;
        roomHasProjection = true;
        ShareMod.config().rememberFile(room, sha, file);
        if (pendingPlacement != null) {
            String json = pendingPlacement;
            pendingPlacement = null;
            onPlacement(json);
        }
    }

    /** 应用房间的共享投影状态 (所有人，包括管理员)。 */
    private void onPlacement(String json) {
        // 切换世界/服务器的头几 tick 里 Litematica 的原理图世界还没建好，这时改投影会在 Litematica 里空指针崩溃 (SchematicPlacementManager.unloadSchematicChunk)；
        // 先记下，等原理图世界好了再应用
        if (!schematicWorldReady()) {
            deferredRemote = json;
            return;
        }
        try {
            applyPlacement(json);
        } catch (NullPointerException e) {
            ShareMod.LOGGER.warn("placement apply deferred: {}", e.toString());
            deferredRemote = json;
        }
    }

    /** 等原理图世界建好再应用的房间投影状态。 */
    private String deferredRemote;

    private static boolean schematicWorldReady() { return SchematicWorldHandler.getSchematicWorld() != null; }

    private void applyPlacement(String json) {
        if (admin && !pushMode) { // 修改模式关闭: 先记下房间的最新状态，开启后再处理
            if (heldRemote == null) msg("&7修改模式关，没应用别人的改动 &f/ls pushmode on");
            heldRemote = json;
            return;
        }
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String sha = o.get("sha").getAsString();
        Path file = receivedFiles.get(sha);
        if (file == null) {
            if (pendingPlacement == null) msg("&7正在下载共享投影…");
            pendingPlacement = json;
            return;
        }
        lastRemoteJson = json;
        BlockPos origin = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
        Rotation rot = Rotation.valueOf(o.get("rotation").getAsString());
        Mirror mirror = Mirror.valueOf(o.get("mirror").getAsString());
        boolean enabled = o.get("enabled").getAsBoolean();

        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        if (sharedPlacement == null || !sharedSha.equals(sha) || !inManager(sharedPlacement)) {
            // 自己发布的原投影保留，只丢弃自动生成的旧副本
            if (sharedPlacement != null && !ownsShared && inManager(sharedPlacement)) mgr.removeSchematicPlacement(sharedPlacement);
            if (sharedPlacement == null) {
                // 本次会话第一次生成副本: 本机遗留的同一份「[共享]」投影(如重连前自己发布的原投影)直接沿用，其余清掉避免重复
                for (SchematicPlacement old : mgr.getAllSchematicsPlacements()) { // 旧版本留下的「[共享] 名称」统一改成「[LS] 名称」
                    if (old.getName().startsWith(LEGACY_TAG)) old.setName(TAG + old.getName().substring(LEGACY_TAG.length()));
                }
                SchematicPlacement own = findLeftover(sha, o.get("name").getAsString());
                for (SchematicPlacement old : new ArrayList<>(mgr.getAllSchematicsPlacements())) {
                    if (old != own && tagged(old.getName())) mgr.removeSchematicPlacement(old);
                }
                if (own != null) {
                    if (!tagged(own.getName())) own.setName(TAG + o.get("name").getAsString()); // 关共享/离开时去掉了共享标，沿用时补回
                    sharedPlacement = own;
                    sharedSha = sha;
                    ownsShared = admin;
                }
            }
        }
        if (sharedPlacement == null || !sharedSha.equals(sha) || !inManager(sharedPlacement)) {
            LitematicaSchematic schematic = LitematicaSchematic.createFromFile(file.getParent(), file.getFileName().toString());
            if (schematic == null) { msg("&c无法加载共享投影"); return; }
            sharedPlacement = SchematicPlacement.createFor(schematic, origin, TAG + o.get("name").getAsString(), true, true);
            sharedSha = sha;
            ownsShared = false;
            sharedPlacement.setShouldBeSaved(false);
            mgr.addSchematicPlacement(sharedPlacement, false);
        }
        if (sharedPlacement.isLocked()) sharedPlacement.toggleLocked(); // 被锁定的投影拒绝改动，应用前先解锁
        sharedPlacement.setOrigin(origin, s -> { });
        sharedPlacement.setRotation(rot, QUIET);
        sharedPlacement.setMirror(mirror, QUIET);
        roomEnabledFlag = enabled;
        sharedPlacement.setEnabled(enabled);
        if (o.has("regions")) { // 房间的投影范围(哪些子区域参与)；本机再按区域选择决定实际显示哪些
            roomRegions.clear();
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("regions").entrySet()) roomRegions.put(e.getKey(), e.getValue().getAsBoolean());
        }
        renderKey = "";
        applyRegionVisibility();
        mgr.markAllPlacementsOfSchematicForRebuild(sharedPlacement.getSchematic());
        refreshLock();
        lastAppliedKey = stateKey(sha, sharedPlacement); // 这是房间的状态，不要回推
    }

    /** 本机遗留的「[共享] 名称」投影，且它的文件内容与房间的共享文件一致才算同一份。 */
    private static SchematicPlacement findLeftover(String sha, String name) {
        for (SchematicPlacement old : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!old.getName().equals(TAG + name) && !old.getName().equals(LEGACY_TAG + name) && !old.getName().equals(name)) continue; // 带共享标的，或关共享/离开时去掉了标的原投影
            try {
                Path f = old.getSchematicFile();
                if (f != null && Files.isRegularFile(f) && Files.size(f) <= MAX_FILE_BYTES
                        && sha256(Files.readAllBytes(f)).equals(sha)) return old;
            } catch (IOException ignored) { }
        }
        return null;
    }

    // ---------------- 材料统计 ----------------

    private void onMaterials(DataInputStream r) throws IOException {
        String sha = Packet.readStr(r);
        int observedChunks = r.readInt();
        int n = r.readInt();
        Map<String, long[]> observed = new TreeMap<>(); // 子区域+物品 -> {已观测区块内需要, 已摆}
        for (int i = 0; i < n; i++) observed.put(Packet.readStr(r), new long[]{r.readLong(), r.readLong()});
        if (sharedPlacement == null || !sharedSha.equals(sha) || !inManager(sharedPlacement)) return;
        lastObserved = observed;
        lastObservedChunks = observedChunks;
        rebuildMaterials();
    }

    /** 用最近一次房间汇总 + 当前子区域筛选重新算材料行 (汇总到达、筛选变化、任务变化时调用)。 */
    private void rebuildMaterials() {
        if (lastObserved == null || sharedPlacement == null || !inManager(sharedPlacement)) return;
        String key = sharedSha + "|" + sharedPlacement.getEnabledRelativeSubRegionPlacements().keySet();
        if (totalsCache == null || !totalsCacheKey.equals(key)) {
            totalsCache = MaterialReport.totalsByRegion(sharedPlacement); // 只和原理图有关，大原理图每次都数会卡
            totalsCacheKey = key;
        }
        List<MaterialReport.Row> rows = MaterialReport.merge(totalsCache, lastObserved, effectiveFilter());
        lastRows = rows;
        long total = rows.stream().mapToLong(MaterialReport.Row::total).sum();
        long placed = rows.stream().mapToLong(MaterialReport.Row::placed).sum();
        int touched = sharedPlacement.getTouchedChunks(fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).size();
        lastTouched = touched;
        progressLine = "团队进度 已摆 " + placed + "/" + total + (total > 0 ? String.format(" (%.1f%%)", 100.0 * placed / total) : "")
                + " | 有人观测过 " + lastObservedChunks + "/" + touched + " 个区块 | 更新于 "
                + java.time.LocalTime.now().withNano(0);
    }

    // ---------------- 任务模式 / 子区域筛选 ----------------

    private boolean taskMode;
    private Map<String, long[]> lastObserved;
    private int lastTouched;

    /** 投影覆盖的区块总数 (最近一次汇总时)。 */
    public int touchedChunks() { return lastTouched; }
    private int lastObservedChunks;
    /** 子区域筛选: 0 全部，1 我的任务的子区域，2 指定一个子区域。没手动选过时，管理员默认全部，成员默认我的。 */
    private int filterMode;
    private final Set<String> filterRegions = new LinkedHashSet<>();
    private boolean filterTouched;

    public boolean isTaskMode() { return taskMode; }

    /** 管理员切换房间模式；切回简单模式时让所有子区域都参与 (整个投影文件)。 */
    public void setTaskMode(boolean on) {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        if (!on && sharedPlacement != null && inManager(sharedPlacement)) {
            for (Map.Entry<String, Boolean> e : regionList()) roomRegions.put(e.getKey(), true); // 简单模式: 整个投影文件
            renderKey = "";
            applyRegionVisibility();
        }
        client.send(Packet.of(LitematicaShare.MODE_SET).bool(on).build());
    }

    /** 分配给我的任务对应的子区域 (优先未完成的)。 */
    public Set<String> myRegions() {
        Set<String> open = new LinkedHashSet<>(), all = new LinkedHashSet<>();
        for (TaskRecord t : tasks) {
            if (!isMine(t) || t.region().isEmpty()) continue;
            all.add(t.region());
            if (t.status() != 2) open.add(t.region());
        }
        return open.isEmpty() ? all : open;
    }

    /** 当前生效的子区域筛选；null 表示整个投影文件 (简单模式或选了全部)。 */
    /** 当前的区域选择模式: 0 全部，1 任务区域，2 指定的若干子区域 (没手动选过时，管理员默认全部，成员默认任务区域)。 */
    public int filterModeNow() { return filterTouched ? filterMode : (admin ? 0 : 1); }

    public Set<String> effectiveFilter() {
        if (!taskMode) return null;
        int mode = filterModeNow();
        if (mode == 1) return myRegions(); // 任务区域；没有任务就是空 (什么都不显示)
        if (mode == 2 && !filterRegions.isEmpty()) return new LinkedHashSet<>(filterRegions);
        return null;
    }

    /** 导航栏按钮上的简短文字。 */
    public String filterLabel() {
        int mode = filterModeNow();
        if (!taskMode || mode == 0) return "全部";
        if (mode == 1) return effectiveFilter().isEmpty() ? "任务区域(无)" : "任务区域";
        return filterRegions.size() == 1 ? filterRegions.iterator().next() : filterRegions.size() + " 个区域";
    }

    private void touchFilter(int mode) {
        filterTouched = true;
        filterMode = mode;
        filterRegions.clear();
    }

    public void setFilterAll() {
        touchFilter(0);
        onFilterInputsChanged();
    }

    public void setFilterMine() {
        touchFilter(1);
        onFilterInputsChanged();
    }

    public boolean isFilterRegion(String name) {
        return filterModeNow() == 2 && filterRegions.contains(name);
    }

    /** 在区域列表里点某个子区域: 加入/移出选择 (多选)；全部取消就回到「全部」。 */
    public void toggleFilterRegion(String name) {
        if (filterModeNow() != 2) touchFilter(2);
        if (!filterRegions.remove(name)) filterRegions.add(name);
        if (filterRegions.isEmpty()) filterMode = 0;
        onFilterInputsChanged();
    }

    private String renderKey = "";

    /** 区域选择变了/房间的投影范围变了才重新应用；平时每秒检查一次。 */
    private void tickRenderRegions() {
        if (sharedPlacement == null || !inManager(sharedPlacement)) return;
        Set<String> f = effectiveFilter();
        String key = sharedSha + "|" + roomRegionsSig() + "|" + (f == null ? "*" : new TreeSet<>(f));
        if (key.equals(renderKey)) return;
        applyRegionVisibility();
    }

    /**
     * 本机实际显示(启用)哪些子区域 = 房间里参与的 ∩ 区域选择。默认成员只显示自己的任务区域。
     * 用 Litematica 的「子区域启用」标志来隐藏 (这才是真正控制方块渲染的开关；「渲染」标志只管轮廓框)，
     * 走 toggleSubRegionEnabled 让 Litematica 自己重算渲染范围。只改本机，房间状态(roomRegions)不变，所以不会被当成改动同步出去。
     */
    private void applyRegionVisibility() {
        if (sharedPlacement == null || !inManager(sharedPlacement) || !schematicWorldReady()) return;
        Set<String> f = effectiveFilter();
        renderKey = sharedSha + "|" + roomRegionsSig() + "|" + (f == null ? "*" : new TreeSet<>(f));
        boolean wasLocked = sharedPlacement.isLocked();
        if (wasLocked) sharedPlacement.toggleLocked(); // 被锁定的投影拒绝改动，应用前先解锁
        for (SubRegionPlacement r : new ArrayList<>(sharedPlacement.getAllSubRegionsPlacements())) {
            boolean want = roomEnabled(r.getName()) && (f == null || f.contains(r.getName()));
            if (r.isEnabled() != want) sharedPlacement.toggleSubRegionEnabled(r.getName(), QUIET);
        }
        if (wasLocked && !sharedPlacement.isLocked()) sharedPlacement.toggleLocked();
        scanner.rescan(); // 显示的子区域变了，扫描覆盖的范围也变了
    }

    private void onFilterInputsChanged() {
        applyRegionVisibility();
        rebuildMaterials();
        verify.clearSelectionIfFilteredOut();
    }

    // ---------------- 共享投影的锁定与清理 ----------------

    private static boolean inManager(SchematicPlacement p) {
        return DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(p);
    }

    /** 成员的「[共享]」副本锁定，管理员和自己发布的原投影不锁。 */
    private void refreshLock() {
        if (sharedPlacement == null) return;
        boolean want = !admin && !ownsShared;
        if (sharedPlacement.isLocked() != want) sharedPlacement.toggleLocked();
    }

    /** 离开房间/断线时解除锁定，免得副本留在本机上改不了。 */
    private void releaseLock() {
        if (sharedPlacement != null && sharedPlacement.isLocked()) sharedPlacement.toggleLocked();
    }

    /** 共享被关闭: 删掉自动生成的副本，自己发布的原投影保留。 */
    private void releaseShared() {
        if (sharedPlacement != null) {
            releaseLock();
            if (!ownsShared && inManager(sharedPlacement)) {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(sharedPlacement);
            } else if (tagged(sharedPlacement.getName())) {
                sharedPlacement.setName(untag(sharedPlacement.getName())); // 取消共享: 去掉共享标
            }
        }
        sharedPlacement = null;
        sharedSha = "";
        ownsShared = false;
        liveRequested = false;
        lastAppliedKey = "";
        lastRemoteJson = null;
        pendingPlacement = null;
        deferredRemote = null;
        heldRemote = null;
    }

    // ---------------- 每 tick: 管理员推送改动 / 成员还原改动 ----------------

    /** 发布时的候选: 选中的投影；没选中但只有一个投影时用它。 */
    private static SchematicPlacement currentPlacement() {
        SchematicPlacementManager mgr = DataManager.getSchematicPlacementManager();
        SchematicPlacement p = mgr.getSelectedSchematicPlacement();
        if (p == null && mgr.getAllSchematicsPlacements().size() == 1) p = mgr.getAllSchematicsPlacements().get(0);
        return p;
    }

    public String currentPlacementName() {
        return sharedPlacement == null ? "无" : sharedPlacement.getName();
    }

    /** 同一条提示只说一次，避免每 0.5 秒刷屏。 */
    private void hint(String text) {
        if (text.equals(lastHint)) return;
        lastHint = text;
        msg(text);
    }

    public void onClientTick() {
        if (deferredRemote != null && schematicWorldReady()) {
            String j = deferredRemote;
            deferredRemote = null;
            onPlacement(j);
        }
        if (joined && quietTicks > 0) quietTicks--;
        if (joined) {
            if (!liveRequested && sharedPlacement != null && inManager(sharedPlacement)) { // 实时同步默认开启: 有共享投影就先要一次团队进度
                liveRequested = true;
                client.send(Packet.of(LitematicaShare.MATERIALS_REQUEST).build());
            }
            tracker.onTick();
            tickInventory();
            tickVerifierOverlay();
            tickLocate();
            if (tick % 20 == 0) tickRenderRegions();
            if (verifyOn && ++autoRefreshTick >= 1200) refreshVerify();
        }
        if (sharing && sharedPlacement != null && inManager(sharedPlacement)) scanner.onTick(sharedPlacement, sharedSha);
        // 共享已开启却一直没有共享的投影: 提示下一步
        if (sharing && sharedPlacement == null && pendingPlacement == null && !publishRequested) {
            if (++waitTicks == WAIT_HINT_TICKS) {
                msg(admin ? "&7还没有共享投影：选中投影后 &f/ls push" : "&7管理员还没发布投影");
            }
        } else {
            waitTicks = 0;
        }
        if (!sharing || ++tick % SYNC_INTERVAL_TICKS != 0) return;
        tickLayer();

        if (!admin) {
            // 成员不能改共享投影: 被改动或被删除就还原
            if (lastRemoteJson != null && sharedPlacement != null
                    && (!inManager(sharedPlacement) || !stateKey(sharedSha, sharedPlacement).equals(lastAppliedKey))) {
                onPlacement(lastRemoteJson);
            }
            return;
        }
        if (publishRequested) {
            tryPublish();
            return;
        }
        if (!pushMode) { // 修改模式关闭: 本机动共享投影不推送，删掉也只是本机脱离
            if (sharedPlacement != null && !inManager(sharedPlacement)) {
                sharedPlacement = null;
                msg("&7本机已脱离共享投影 &f/ls pushmode on &7恢复");
            }
            return;
        }
        if (sharedPlacement != null) {
            if (!inManager(sharedPlacement)) { // 本机的共享投影不见了 (被移除，或切换世界/服务器时 Litematica 重载了放置列表): 不弹框，默认从房间状态恢复
                if (!schematicWorldReady()) return; // 原理图世界还没建好，等它好了再恢复
                sharedPlacement = null;
                if (lastRemoteJson != null) onPlacement(lastRemoteJson);
                return;
            }
            String key = stateKey(sharedSha, sharedPlacement);
            if (!key.equals(lastAppliedKey)) { // 本机动过共享投影，同步给所有人
                lastAppliedKey = key;
                lastRemoteJson = placementJson(sharedSha, sharedPlacement);
                client.send(Packet.of(LitematicaShare.PLACEMENT).str(lastRemoteJson).build());
            }
        }
    }

    private void tryPublish() {
        SchematicPlacement p = currentPlacement();
        if (p == null) {
            int n = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().size();
            hint(n == 0 ? "&c没有可共享的投影" : "&c请先在 Litematica 里选中要共享的投影");
            return;
        }
        try {
            Path file = p.getSchematicFile();
            if (file == null || !Files.isRegularFile(file)) {
                hint("&c投影没有对应的 .litematic 文件");
                return;
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                if (!file.equals(oversized)) msg("&c投影文件超过 " + MAX_FILE_BYTES / 1024 / 1024 + "MB");
                oversized = file;
                return;
            }
            byte[] data = Files.readAllBytes(file);
            String sha = sha256(data);
            String baseName = baseName(p, file);
            p.setName(TAG + baseName); // 发布者自己的原投影也打上共享标
            if (!sha.equals(roomSha)) { // 房间里已经是这个文件就不用再上传
                client.send(Packet.of(LitematicaShare.FILE).str(file.getFileName().toString()).str(sha).bytes(data).build());
            }
            roomSha = sha;
            roomHasProjection = true;
            ShareMod.config().rememberFile(room, sha, file);
            roomRegions.clear();
            for (SubRegionPlacement r : p.getAllSubRegionsPlacements()) roomRegions.put(r.getName(), r.isEnabled()); // 发布时各子区域的当前状态就是房间的投影范围
            String json = placementJson(sha, p);
            client.send(Packet.of(LitematicaShare.PLACEMENT).str(json).build());
            if (sharedPlacement != null && sharedPlacement != p && !ownsShared && inManager(sharedPlacement)) {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(sharedPlacement); // 旧的自动副本
            }
            boolean isCopy = p == sharedPlacement && !ownsShared;
            sharedPlacement = p;
            sharedSha = sha;
            ownsShared = !isCopy;
            receivedFiles.put(sha, file);
            lastAppliedKey = stateKey(sha, p);
            lastRemoteJson = json;
            pendingPlacement = null;
            publishRequested = false;
            lastHint = "";
            msg("&a已共享 &f" + p.getName());
        } catch (IOException e) {
            ShareMod.LOGGER.warn("failed to publish placement", e);
        }
    }

    // ---------------- 工具 ----------------

    /** 各子区域的启用状态，如 A=1;B=0 (按名字排序)。 */
    static String regionsSig(SchematicPlacement p) {
        StringBuilder sb = new StringBuilder();
        p.getAllSubRegionsPlacements().stream().sorted(Comparator.comparing(SubRegionPlacement::getName))
                .forEach(r -> sb.append(r.getName()).append('=').append(r.isEnabled() ? 1 : 0).append(';'));
        return sb.toString();
    }

    /** 房间的投影范围: 子区域 -> 是否参与 (管理员设置，同步给所有人)。和本机实际显示哪些是两回事，显示还要看区域选择。 */
    private final Map<String, Boolean> roomRegions = new TreeMap<>();

    private boolean roomEnabled(String name) { return roomRegions.getOrDefault(name, true); }

    private String roomRegionsSig() {
        StringBuilder sb = new StringBuilder();
        roomRegions.forEach((n, on) -> sb.append(n).append('=').append(on ? 1 : 0).append(';'));
        return sb.toString();
    }

    /** 共享投影的子区域 (名字 -> 房间里是否参与)，界面里管理员据此选择投影范围。 */
    public List<Map.Entry<String, Boolean>> regionList() {
        if (sharedPlacement == null || !inManager(sharedPlacement)) return List.of();
        List<Map.Entry<String, Boolean>> out = new ArrayList<>();
        sharedPlacement.getAllSubRegionsPlacements().stream().sorted(Comparator.comparing(SubRegionPlacement::getName))
                .forEach(r -> out.add(Map.entry(r.getName(), roomEnabled(r.getName()))));
        return out;
    }

    /** 子区域的大小，如 12×8×20。 */
    public String regionSize(String name) {
        try {
            var size = sharedPlacement.getSchematic().getAreaSize(name);
            return Math.abs(size.getX()) + "×" + Math.abs(size.getY()) + "×" + Math.abs(size.getZ());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 管理员切换某个子区域是否参与投影 (改动会同步给所有人，材料/验证也只算启用的子区域)。 */
    public void toggleRegion(String name) {
        if (!admin) { msg(Chat.NOT_ADMIN); return; }
        if (sharedPlacement == null || !inManager(sharedPlacement)) { msg("&c还没有共享投影"); return; }
        roomRegions.put(name, !roomEnabled(name));
        renderKey = "";
        applyRegionVisibility(); // 房间状态变了，下一个 tick 的 stateKey 比较会把它推给所有人
    }

    private String stateKey(String sha, SchematicPlacement p) {
        BlockPos o = p.getOrigin();
        return sha + "|" + o.getX() + "," + o.getY() + "," + o.getZ() + "|" + p.getRotation() + "|" + p.getMirror() + "|" + p.isEnabled()
                + "|" + roomRegionsSig(); // 房间的投影范围，不是本机实际的启用标志(本机的显示由区域选择控制，不能被当成"改动")
    }

    private static String baseName(SchematicPlacement p, Path file) {
        String n = untag(p.getName());
        if (n.isBlank() || n.equals("Unnamed")) {
            n = file.getFileName().toString();
            if (n.endsWith(".litematic")) n = n.substring(0, n.length() - ".litematic".length());
        }
        return n;
    }

    private String placementJson(String sha, SchematicPlacement p) {
        BlockPos o = p.getOrigin();
        JsonObject j = new JsonObject();
        j.addProperty("sha", sha);
        j.addProperty("name", untag(p.getName()));
        j.addProperty("x", o.getX());
        j.addProperty("y", o.getY());
        j.addProperty("z", o.getZ());
        j.addProperty("rotation", p.getRotation().name());
        j.addProperty("mirror", p.getMirror().name());
        j.addProperty("enabled", p.isEnabled());
        JsonObject regions = new JsonObject();
        for (SubRegionPlacement r : p.getAllSubRegionsPlacements()) regions.addProperty(r.getName(), roomEnabled(r.getName()));
        j.add("regions", regions);
        return j.toString();
    }

    private Path schematicDir() {
        return DataManager.getSchematicsBaseDirectory().resolve("litematica-share").resolve(safeName(room));
    }

    private static String safeName(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void msg(String text) {
        Chat.say(text);
    }
}
