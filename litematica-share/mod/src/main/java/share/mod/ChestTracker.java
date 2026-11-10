package share.mod;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import share.protocol.ChestRecord;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 共享仓库的客户端部分: 只有管理员允许的容器才会上报内容；打开允许的箱子/木桶/潜影盒时读取内容并上报；
 * 管理员的「箱子模式」下左键允许、右键不允许；定期清理已被拆掉的容器；高亮搜索结果。
 */
final class ChestTracker {
    /** 一个可记录的容器: 大箱子归一到较小一半的坐标。 */
    record Target(String dim, BlockPos pos, String kind) {
        String key() { return dim + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
        ChestIndex.Pos asPos() { return new ChestIndex.Pos(dim, pos.getX(), pos.getY(), pos.getZ()); }
    }

    private static final int STABLE_TICKS = 10;   // 内容稳定这么久(服务端把格子发完)才上报
    private static final int SWEEP_INTERVAL = 40;
    private static final long HIGHLIGHT_MS = 90_000;

    private static volatile BlockPos lastUsed;
    private static volatile ChestTracker active;
    private static boolean hooksRegistered;
    private static final Set<Object> SLOT_HOOKED = Collections.newSetFromMap(new WeakHashMap<>());

    private final Minecraft mc = Minecraft.getInstance();
    private final ChestIndex index;
    private final Consumer<ChestRecord> report;
    private final Consumer<ChestRecord> gone;
    private final BiConsumer<Target, Boolean> allowSender;
    private final Consumer<String> say;
    private final String selfName;

    private ChestRecord pending;       // 当前打开的容器的最新内容
    private String pendingSig = "";
    private int stable;
    private String reportedSig = "";
    private boolean screenWasOpen;
    private int sweepTick, sweepCursor;
    private boolean chestMode;

    // 选中要找的物品: 高亮期间打开箱子，这些物品在箱子界面里高亮，并自动拿出来
    private Set<String> wanted = Set.of();
    private boolean autoTake = ShareMod.config() == null || ShareMod.config().autoTake;
    private int takeContainer = -1, takeWait, takeCursor;
    private long takenCount;
    private String takenName = "";

    // 高亮
    private Set<String> highlightKeys = Set.of();
    private long highlightUntil;

    ChestTracker(ChestIndex index, String selfName, Consumer<ChestRecord> report, Consumer<ChestRecord> gone,
                 BiConsumer<Target, Boolean> allowSender, Consumer<String> say) {
        this.index = index;
        this.selfName = selfName;
        this.report = report;
        this.gone = gone;
        this.allowSender = allowSender;
        this.say = say;
        active = this;
    }

    void dispose() {
        if (active == this) active = null;
    }

    /** 模组初始化时注册一次: 记录最近右键的方块、箱子模式下的左右键。 */
    static void registerHooks() {
        if (hooksRegistered) return;
        hooksRegistered = true;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> cs) || !SLOT_HOOKED.add(screen)) return;
            ScreenEvents.afterRender(screen).register((scr, g, mx, my, d) -> {
                ChestTracker t = active;
                if (t != null) t.drawWanted(cs, g);
            });
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            lastUsed = hit.getBlockPos();
            ChestTracker t = active;
            if (t != null && t.chestMode && world instanceof ClientLevel level) {
                Target tg = targetAt(level, hit.getBlockPos());
                if (tg != null) { // 箱子模式: 右键 = 不允许，并且不打开箱子
                    t.setAllowed(tg, false);
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            ChestTracker t = active;
            if (t != null && t.chestMode && world instanceof ClientLevel level) {
                Target tg = targetAt(level, pos);
                if (tg != null) { // 箱子模式: 左键 = 允许，并且不破坏箱子
                    t.setAllowed(tg, true);
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });
    }

    // ---------------- 容器识别 ----------------

    /** pos 处如果是可记录的容器(箱子/大箱子/木桶/潜影盒，不含末影箱)，返回归一后的目标。 */
    static Target targetAt(ClientLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        Block b = st.getBlock();
        String dim = dimOf(level);
        if (b instanceof ChestBlock) {
            if (st.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return new Target(dim, pos, "chest");
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(st));
            boolean otherFirst = other.getX() < pos.getX() || (other.getX() == pos.getX() && other.getZ() < pos.getZ());
            return new Target(dim, otherFirst ? other : pos, "double_chest");
        }
        if (b instanceof BarrelBlock) return new Target(dim, pos, "barrel");
        if (b instanceof ShulkerBoxBlock) return new Target(dim, pos, "shulker");
        return null;
    }

    // ---------------- 允许 / 箱子模式 ----------------

    boolean chestMode() { return chestMode; }

    void setChestMode(boolean on) { chestMode = on; }

    /** 管理员允许/不允许一个容器上传 (本机先生效，转发端广播后所有人一致)。 */
    void setAllowed(Target t, boolean allow) {
        boolean now = index.isAllowed(t.key());
        if (now == allow) return;
        if (allow) index.allow(t.asPos()); else index.disallow(t.key());
        allowSender.accept(t, allow);
        say.accept((allow ? "&a允许 " : "&7取消 ") + "&f" + kindName(t.kind()) + " " + t.pos().getX() + "," + t.pos().getY() + "," + t.pos().getZ()
                + " &7共 " + index.allowedCount());
    }

    static String kindName(String kind) {
        return switch (kind) {
            case "double_chest" -> "大箱子";
            case "barrel" -> "木桶";
            case "shulker" -> "潜影盒";
            default -> "箱子";
        };
    }

    // ---------------- 高亮 ----------------

    void highlight(Collection<ChestRecord> recs) {
        Set<String> keys = new HashSet<>();
        for (ChestRecord c : recs) keys.add(c.key());
        highlightKeys = keys;
        highlightUntil = System.currentTimeMillis() + HIGHLIGHT_MS;
    }

    /** 当前高亮的箱子数 (测试用)。 */
    int highlightCount() { return hasHighlight() ? highlightKeys.size() : 0; }

    void clearHighlight() {
        highlightKeys = Set.of();
        wanted = Set.of();
    }

    void setWanted(Set<String> ids) { wanted = ids; }

    /** 当前要找的物品 (高亮过期后为空)。 */
    Set<String> wanted() { return hasHighlight() ? wanted : Set.of(); }

    boolean autoTake() { return autoTake; }

    void setAutoTake(boolean on) { autoTake = on; }

    /** 在容器界面里把要找的物品所在的格子标成金色。 */
    private void drawWanted(AbstractContainerScreen<?> cs, GuiGraphics g) {
        Set<String> w = wanted();
        if (w.isEmpty()) return;
        for (Slot s : cs.getMenu().slots) {
            ItemStack st = s.getItem();
            if (st.isEmpty() || !w.contains(ProgressScanner.itemKey(st))) continue;
            g.fill(cs.leftPos + s.x, cs.topPos + s.y, cs.leftPos + s.x + 16, cs.topPos + s.y + 16, 0x99FFFFFF);
        }
    }

    /** 打开箱子后(格子内容到齐)，把要找的物品一格一格 shift 点击拿出来 (两 tick 一格，避免被服务器当成刷包)。 */
    private void autoTake(ClientLevel level, AbstractContainerMenu menu) {
        if (!autoTake || (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu))) return;
        Set<String> w = wanted();
        if (w.isEmpty() || mc.gameMode == null || mc.player == null) return;
        if (menu.containerId != takeContainer) {
            takeContainer = menu.containerId;
            takeWait = 0;
            takeCursor = 0;
            takenCount = 0;
            takenName = "";
        }
        int slots = menu.slots.size() - 36;
        if (takeCursor > slots) return;
        if (++takeWait < 8 || takeWait % 2 != 0) return;
        if (takeCursor >= slots) {
            takeCursor = slots + 1;
            if (takenCount > 0) say.accept("&a已取出 &f" + takenName + " ×" + takenCount);
            return;
        }
        Slot s = menu.slots.get(takeCursor++);
        ItemStack st = s.getItem();
        if (st.isEmpty() || !w.contains(ProgressScanner.itemKey(st))) return;
        int n = st.getCount();
        String name = st.getHoverName().getString();
        mc.gameMode.handleInventoryMouseClick(menu.containerId, s.index, 0, ClickType.QUICK_MOVE, mc.player);
        takenCount += n;
        takenName = takenName.isEmpty() || takenName.equals(name) ? name : "物品";
    }

    boolean hasHighlight() { return !highlightKeys.isEmpty() && System.currentTimeMillis() < highlightUntil; }

    private static AABB boxOf(ClientLevel level, BlockPos pos) {
        AABB box = new AABB(pos);
        BlockState st = level.getBlockState(pos);
        if (st.getBlock() instanceof ChestBlock && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            box = box.minmax(new AABB(pos.relative(ChestBlock.getConnectedDirection(st))));
        }
        return box.inflate(0.02);
    }

    /** 每 tick 发一次图形；引擎的"每 tick 调试图形"按 persistForMillis 保留，所以每个 tick 的图形撑到下一个 tick。 */
    private void emitGizmos() {
        ClientLevel level = mc.level;
        if (level == null) return;
        String dim = dimOf(level);
        try {
            if (hasHighlight()) {
                GizmoStyle white = GizmoStyle.strokeAndFill(0xFFFFFFFF, 3f, 0x55FFFFFF);
                // 所有命中的箱子都画；为了不卡，只画玩家 96 格内最近的 400 个
                var me = mc.player == null ? null : mc.player.blockPosition();
                List<ChestRecord> shown = new ArrayList<>();
                for (ChestRecord c : index.all()) {
                    if (!highlightKeys.contains(c.key()) || !c.dim().equals(dim)) continue;
                    if (me != null && me.distSqr(new BlockPos(c.x(), c.y(), c.z())) > 96 * 96) continue;
                    shown.add(c);
                }
                if (me != null && shown.size() > 400) shown.sort(java.util.Comparator.comparingDouble(c -> me.distSqr(new BlockPos(c.x(), c.y(), c.z()))));
                for (ChestRecord c : shown.subList(0, Math.min(400, shown.size()))) {
                    Gizmos.cuboid(boxOf(level, new BlockPos(c.x(), c.y(), c.z())), white).setAlwaysOnTop().persistForMillis(70);
                }
            }
            if (chestMode && mc.player != null) { // 箱子模式: 已允许的箱子画绿框，方便看哪些没允许
                GizmoStyle green = GizmoStyle.strokeAndFill(0xFF44FF44, 2f, 0x4044FF44);
                for (ChestIndex.Pos p : index.allowedAll()) {
                    if (!p.dim().equals(dim)) continue;
                    BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
                    if (mc.player.blockPosition().distSqr(pos) > 48 * 48) continue;
                    Gizmos.cuboid(boxOf(level, pos), green).setAlwaysOnTop().persistForMillis(70);
                }
            }
        } catch (RuntimeException e) {
            ShareMod.LOGGER.warn("chest gizmos failed: {}", e.toString());
            highlightKeys = Set.of();
            chestMode = false;
        }
    }

    // ---------------- 每 tick ----------------

    void onTick() {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        var screen = mc.screen;
        if (screen instanceof AbstractContainerScreen<?> cs) {
            screenWasOpen = true;
            capture(level, cs.getMenu());
            autoTake(level, cs.getMenu());
        } else if (screenWasOpen) { // 关掉容器界面: 把最后的内容补报一次
            screenWasOpen = false;
            flush();
        }
        emitGizmos();
        if (++sweepTick % SWEEP_INTERVAL == 0) sweep(level);
    }

    private void capture(ClientLevel level, AbstractContainerMenu menu) {
        if (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu)) return;
        BlockPos pos = lastUsed;
        if (pos == null || mc.player.blockPosition().distSqr(pos) > 64) return;
        Target t = targetAt(level, pos);
        if (t == null) return; // 末影箱、箱子矿车等不记录
        boolean shulker = t.kind().equals("shulker");
        if (shulker != (menu instanceof ShulkerBoxMenu)) return;
        if (!index.isAllowed(t.key())) return; // 没被管理员允许的容器不上传

        int slots = menu.slots.size() - 36; // 最后 36 格是玩家背包
        if (slots <= 0) return;
        Map<String, Integer> items = new TreeMap<>();
        for (int i = 0; i < slots; i++) addStack(items, menu.slots.get(i).getItem());
        String sig = items.toString();
        if (!sig.equals(pendingSig) || pending == null) {
            pendingSig = sig;
            pending = new ChestRecord(t.dim(), t.pos().getX(), t.pos().getY(), t.pos().getZ(), t.kind(), selfName, 0, items);
            stable = 0;
            return;
        }
        if (++stable == STABLE_TICKS) flush();
    }

    /** 物品计数；潜影盒里的东西展开计入 (潜影盒本身也算一个)。 */
    static void addStack(Map<String, Integer> items, ItemStack st) {
        if (st.isEmpty()) return;
        items.merge(ProgressScanner.itemKey(st), st.getCount(), Integer::sum);
        ItemContainerContents inner = st.get(DataComponents.CONTAINER);
        if (inner != null) inner.nonEmptyItems().forEach(s -> items.merge(ProgressScanner.itemKey(s), s.getCount() * st.getCount(), Integer::sum));
    }

    private void flush() {
        if (pending == null) return;
        String mark = pending.key() + "@" + pendingSig;
        if (mark.equals(reportedSig)) return;
        if (!index.isAllowed(pending.key())) return;
        reportedSig = mark;
        ChestRecord c = pending.withTime(selfName, System.currentTimeMillis());
        index.put(c, false);
        report.accept(c);
    }

    /** 轮流检查已记录的容器: 区块已加载但方块不再是容器 = 被拆了，通知房间删除。 */
    private void sweep(ClientLevel level) {
        String dim = dimOf(level);
        List<ChestRecord> all = index.all();
        if (all.isEmpty()) return;
        for (int tries = 0; tries < 5 && tries < all.size(); tries++) {
            ChestRecord c = all.get(Math.floorMod(sweepCursor++, all.size()));
            if (!c.dim().equals(dim)) continue;
            BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
            if (!level.isLoaded(pos)) continue;
            if (targetAt(level, pos) != null) continue;
            index.remove(c.dim(), c.x(), c.y(), c.z());
            gone.accept(c);
        }
    }

    static String dimOf(ClientLevel level) {
        return level.dimension().identifier().toString();
    }
}
