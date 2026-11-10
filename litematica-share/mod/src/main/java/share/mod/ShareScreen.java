package share.mod;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按钮界面 (按 O 或输入 /ls 打开): 房间、投影、材料、仓库、验证五页，所有功能不用再敲命令。
 * 颜色约定: &7 灰为主色，&f 白为重点，&a 正向，&c 反向。仓库页和验证页是左右两栏，右栏是可点击的列表。
 */
final class ShareScreen extends Screen {
    private record Line(String text, int x, int y) { }

    /** 成员头像 (按 UUID 取皮肤，皮肤异步加载好前显示默认皮肤)。 */
    private record Face(String uuid, int x, int y) { }

    /** 右栏列表的一行。 */
    /** 右栏列表的一行: left=名字列，cells=右边各固定宽度的列，tip=悬停提示(单位换算等)。 */
    /** done: 这一行已经满足 (材料「有」够了)，排序时沉底，文字由调用方染成绿色。 */
    private record Row(String left, String[] cells, boolean selected, Runnable onClick, ItemStack icon, ItemStack icon2, long sort, String name, List<String> tip, boolean done) {
        Row(String left, String[] cells, boolean selected, Runnable onClick, ItemStack icon, ItemStack icon2, long sort, String name, List<String> tip) {
            this(left, cells, selected, onClick, icon, icon2, sort, name, tip, false);
        }
    }

    private static final int W = 320;
    /** 房间、投影、材料(含仓库)、验证、任务。简单模式隐藏「任务」页，只有被分配了任务的玩家例外 (要能看到自己的任务)。 */
    private static final String[] TABS_ALL = {"房间", "投影", "材料", "验证", "任务"};
    private static final String[] TABS_NO_TASK = {"房间", "投影", "材料", "验证"};

    private static String[] tabs(ShareSession s) {
        boolean tasks = s != null && (s.isTaskMode() || s.tasks().stream().anyMatch(s::isMine));
        return tasks ? TABS_ALL : TABS_NO_TASK;
    }
    private static final int WHITE = 0xFFFFFFFF;
    private static final int ROW_H = 18;
    private static final String[] SORT_NAMES = {"数量↓", "数量↑", "名称"};

    // 界面关掉再开也记得的输入/选择
    private static int tab;
    private static String addrText, roomText, keyText = "", searchText = "", verifyFilter = "", selectedMat = "";
    /** 右栏列表排序: 0 数量从多到少，1 数量从少到多，2 名称；仓库页右栏显示 0 材料清单 / 1 全部仓库物品。 */
    private static int sortMode, matMode, verifyType = -1; // matMode: 0 缺的材料 1 全部材料 2 仓库里的物品
    // 任务页的表单: formId 为空表示新建；选中右栏的任务就把它载入表单
    private static String formId = "", formTitle = "", formRegion = "", formNote = "";
    private static final java.util.Set<String> formAssignees = new java.util.LinkedHashSet<>();
    private static boolean onlyMine = true; // 任务列表默认只显示我的
    private static List<ChestIndex.Hit> results = List.of();
    /** 材料列表里多选的物品 (点一行切换，可同时选多条)；selectedMat 是最后点的那个，左栏显示它的详情。 */
    private static final Set<String> pickedMats = new java.util.LinkedHashSet<>();

    private final List<Line> lines = new ArrayList<>();
    private final List<Face> faces = new ArrayList<>();
    private String lastState = "";

    // 右栏列表
    private List<Row> pane = List.of();
    private int paneX, paneY, paneW, paneH, paneTitleY;
    /** 右栏固定列宽 (名字列之后从左到右) 和表头；翻页。 */
    private int[] paneCols = {};
    private String[] paneHeaders = {};
    private static int page;
    private boolean paneVisible;
    private String paneTitle = "";

    ShareScreen() {
        super(Component.literal("投影共享"));
    }

    static void selectTab(int t) { tab = t; }

    @Override
    protected void init() {
        build();
    }

    @Override
    public void tick() {
        String st = stateKey();
        if (!st.equals(lastState)) rebuildWidgets();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private String stateKey() {
        ShareSession s = ShareMod.session();
        if (s == null) return "none";
        return System.identityHashCode(s) + "|" + s.isJoined() + "|" + s.isAdmin() + "|" + s.isSharing() + "|" + s.isPushMode()
                + "|" + s.scanner().isEnabled() + "|" + s.members() + "|" + s.chests().size() + "|" + s.currentPlacementName()
                + "|" + s.isProjectionOn() + "|" + s.isChestMode() + "|" + s.isShareInventory() + "|" + s.chests().allowedCount()
                + "|" + s.isLayerSync() + "|" + s.isCountInventory() + "|" + s.isAutoTake() + "|" + s.regionList() + "|" + s.tasks() + "|" + s.isTaskMode() + "|" + s.filterLabel() + "|" + s.isVerifyOn() + "|" + s.verifyModel().observedChunks() + "|" + s.materialRows().size()
                + (tab == 2 ? "|" + s.progressLine() : "");
    }

    /** 按钮文字尽量短，鼠标移上去再显示具体功能 (tip 为空则不显示)。 */
    private Button button(String label, String tip, int x, int y, int w, Runnable action) {
        Button.Builder bb = Button.builder(Chat.parse(label), btn -> action.run()).bounds(x, y, w, 20);
        if (tip != null && !tip.isEmpty()) bb.tooltip(Tooltip.create(Chat.parse(tip)));
        Button b = bb.build();
        addRenderableWidget(b);
        return b;
    }

    private EditBox box(int x, int y, int w, String value, String hint, java.util.function.Consumer<String> onChange) {
        EditBox e = new EditBox(font, x, y, w, 20, Component.literal(hint));
        e.setMaxLength(128);
        e.setHint(Component.literal(hint));
        e.setValue(value == null ? "" : value);
        e.setResponder(onChange);
        addRenderableWidget(e);
        return e;
    }

    private void text(String amp, int x, int y) {
        lines.add(new Line(amp, x, y));
    }

    private void build() {
        lines.clear();
        faces.clear();
        pane = List.of();
        paneVisible = false;
        paneCols = new int[0];
        paneHeaders = new String[0];
        lastState = stateKey();
        ShareSession ses = ShareMod.session();
        String[] TABS = tabs(ses);
        if (tab >= TABS.length) tab = 0;
        boolean regionNav = ses != null && ses.isJoined() && ses.isTaskMode(); // 区域选择在导航栏，多个页面共用
        int navW = regionNav ? 104 : 0;
        int tabW = Math.min(80, (width - 20 - navW) / TABS.length);
        int tx = (width - tabW * TABS.length - navW) / 2;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            Button b = button(TABS[i], null, tx + i * tabW, 14, tabW - 2, () -> { tab = idx; page = 0; rebuildWidgets(); });
            b.active = tab != i;
        }
        String pend = Updater.pendingVersion();
        button(pend != null ? "&av" + Updater.currentVersion() + " →" : "&7v" + Updater.currentVersion(),
                pend != null ? "已下载新版本 " + pend + "，重启游戏后生效" : "当前版本；点击检查更新（自动更新：" + (ShareMod.config().autoUpdate ? "开" : "关，/ls update on 开启") + "）",
                4, height - 22, 60, () -> ShareMod.checkUpdate(true));
        if (regionNav) {
            String label = ses.filterLabel();
            if (label.length() > 8) label = label.substring(0, 7) + "…";
            button("&f区域：" + label, "选择区域（全部 / 任务区域 / 某些子区域）：材料、验证的统计范围和本机渲染哪些子区域都按它，点击打开列表", tx + tabW * TABS.length + 4, 14, navW - 4,
                    () -> minecraft.setScreen(new RegionPickerScreen(this)));
        }
        int cy = 44;
        ShareSession s = ShareMod.session();
        int x = (width - W) / 2;
        switch (tab) {
            case 0 -> roomTab(s, x, cy);
            case 1 -> twoColumns(cy, (lx, colW, ry, rw) -> projectionTab(s, lx, cy, colW, ry, rw));
            case 2 -> twoColumns(cy, (lx, colW, ry, rw) -> materialsTab(s, lx, cy, colW, ry, rw));
            case 3 -> twoColumns(cy, (lx, colW, ry, rw) -> verifyTab(s, lx, cy, colW, ry, rw));
            default -> twoColumns(cy, (lx, colW, ry, rw) -> taskTab(s, lx, cy, colW, ry, rw));
        }
        if (paneVisible) addPager();
    }

    /** 右栏上方的排序按钮。 */
    private void sortButton(int x, int y, int w) {
        button("&f排序：" + SORT_NAMES[sortMode], "切换列表排序：数量从多到少 / 从少到多 / 按名称", x, y, w, () -> {
            sortMode = (sortMode + 1) % SORT_NAMES.length;
            rebuildWidgets();
        });
    }

    private static List<Row> sorted(List<Row> rows) {
        List<Row> out = new ArrayList<>(rows);
        switch (sortMode) {
            case 0 -> out.sort(java.util.Comparator.comparingLong(Row::sort).reversed());
            case 1 -> out.sort(java.util.Comparator.comparingLong(Row::sort));
            default -> out.sort(java.util.Comparator.comparing(Row::name));
        }
        out.sort(java.util.Comparator.comparing(Row::done)); // 稳定排序: 已经够的行沉到底部，其余顺序不变
        return out;
    }

    private static final int HEADER_H = 14, FOOTER_H = 22;

    private int rowsPerPage() {
        return Math.max(1, (paneH - (paneHeaders.length > 0 ? HEADER_H : 0) - FOOTER_H - 2) / ROW_H);
    }

    private int pageCount() { return Math.max(1, (pane.size() + rowsPerPage() - 1) / rowsPerPage()); }

    /** 列表底部的翻页按钮 (左右翻页)。 */
    private void addPager() {
        int pages = pageCount();
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        int by = paneY + paneH - FOOTER_H + 1;
        Button prev = button("&f<", "上一页", paneX + 2, by, 34, () -> { page--; rebuildWidgets(); });
        Button next = button("&f>", "下一页（也可以用鼠标滚轮翻页）", paneX + paneW - 36, by, 34, () -> { page++; rebuildWidgets(); });
        prev.active = page > 0;
        next.active = page < pages - 1;
    }

    /** 提示文字 (带 & 颜色码的多行) 转成 Component 列表。 */
    /** 列表里的数量: 个数，或切换成 14桶3组7个。 */
    private static String withUnits(String color, long n, int stack) {
        return color + (unitMode ? Units.fmt(n, stack) : String.valueOf(n));
    }

    /** 列表里的数量显示方式: false 只显示个数，true 显示 桶+组+个 (材料列表右上角的按钮切换)。 */
    private static boolean unitMode;

    private static List<Component> tipLines(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(Chat.parse(l));
        return out;
    }

    private interface TwoCol { void layout(int leftX, int colW, int rightX, int rightW); }

    private void twoColumns(int y, TwoCol c) {
        int total = Math.min(width - 20, 1400);
        int colW = Math.max(150, total / 3 - 6); // 左栏按钮缩小，右栏列表占 2/3
        int rightW = total - colW - 10;
        int lx = (width - total) / 2;
        paneX = lx + colW + 10;
        paneW = rightW;
        paneVisible = true; // 列表框始终显示，没有内容就是空的
        paneTitle = switch (tab) {
            case 1 -> "&7投影范围（子区域）";
            case 2 -> "&7材料清单";
            case 4 -> "&7任务";
            default -> "&7方块问题";
        };
        paneY = tab == 1 ? y + 16 : y + 40; // 投影页没有排序等按钮，列表直接往上提
        paneTitleY = paneY - (tab == 1 ? 14 : 38);
        paneH = height - paneY - 12;
        c.layout(lx, colW, paneX, rightW);
    }

    // ---------------- 房间 ----------------

    private void roomTab(ShareSession s, int x, int y) {
        ShareConfig cfg = ShareMod.config();
        if (s == null) {
            if (addrText == null) addrText = cfg.hasServer() ? cfg.host + ":" + cfg.port : "";
            if (roomText == null) roomText = cfg.room;
            if (keyText.isEmpty()) keyText = cfg.key;
            text("&7转发服务器 (地址:端口，只存在你本机)", x, y);
            box(x, y + 12, W, addrText, "地址:端口", v -> addrText = v);
            text("&7房间名", x, y + 44);
            box(x, y + 56, W, roomText, "房间名", v -> roomText = v);
            text("&7密钥 (第一个进入的人创建房间)", x, y + 88);
            box(x, y + 100, W, keyText, "密钥", v -> keyText = v);
            button("&f加入", "用上面的地址、房间和密钥加入房间；下次进游戏自动加入", x, y + 132, W,
                    () -> ShareMod.guiJoin(addrText == null ? "" : addrText, roomText == null ? "" : roomText, keyText));
            text("&c未连接", x, y + 162);
            button("&f房间列表", "已保存的房间：可以加入多个，但同一时间只连一个、只加载一个房间的投影数据", x, y + 178, W, () -> minecraft.setScreen(new RoomsScreen(this)));
            return;
        }
        text("&7房间 &f" + s.roomName() + (s.isJoined() ? "" : " &7(连接中…)"), x, y);
        if (s.isJoined()) text("&f" + (s.isAdmin() ? "管理员" : "成员") + " &7· 共享 " + (s.isSharing() ? "&a开" : "&7关"), x, y + 12);
        int row = y + 30;
        for (ShareSession.Member m : s.members()) {
            if (row > height - 60) break;
            faces.add(new Face(m.uuid(), x, row + 2));
            text("&f" + m.name() + (m.admin() ? " &7[管理员]" : ""), x + 22, row + 6);
            // 身份靠 Mojang 账号 UUID (改名不影响)，名字只用来显示
            if (s.isAdmin() && !m.uuid().equals(minecraft.getUser().getProfileId().toString())) {
                button(m.admin() ? "&7取消" : "&f设管理", m.admin() ? "取消 " + m.name() + " 的管理员" : "设 " + m.name() + " 为管理员", x + W - 70, row, 70,
                        () -> s.setAdmin(m.name(), !m.admin()));
            }
            row += 24;
        }
        boolean modeBtn = s.isAdmin() && s.isJoined();
        int by = Math.min(row + 8, height - (modeBtn ? 76 : 52));
        button("&f房间列表", "已保存的房间：可以加入多个，切换时只加载新房间的投影数据", x, by, W, () -> minecraft.setScreen(new RoomsScreen(this)));
        by += 24;
        if (modeBtn) {
            button(s.isTaskMode() ? "&a模式：任务" : "&7模式：简单", "简单模式：整个投影文件，所有人看到一样的材料和验证；任务模式：可建任务分配给玩家，按子区域筛选材料和验证，成员默认只看自己的子区域（切回简单模式会让所有子区域都参与）", x, by, W,
                    () -> s.setTaskMode(!s.isTaskMode()));
            by += 24;
        }
        button("&c离开", "离开房间，下次进入游戏不会自动加入", x, by, W, ShareMod::guiLeave);
    }

    // ---------------- 投影 (左: 共享/推送/渲染层; 右: 投影范围=子区域) ----------------

    private void projectionTab(ShareSession s, int lx, int y, int colW, int rx, int rw) {
        if (s == null || !s.isJoined()) {
            text("&c先在「房间」页加入房间", lx, y);
            text("&7加入房间后显示", rx + 4, y + 46);
            return;
        }
        boolean admin = s.isAdmin();
        text("&7共享投影 &f" + s.currentPlacementName(), lx, y);
        text(admin ? "&7管理员：可上传、移动，改动同步给所有人" : "&7成员：共享投影自动出现，不能改动", lx, y + 12);
        button(s.isProjectionOn() ? "&a投影：开" : "&7投影：关", "只影响你本机：关闭后不画投影方块（材料、验证、仓库照常工作并继续上报）；会关掉 Litematica 的渲染开关，所以你本机其他投影也一起不显示；不影响房间里其他人", lx, y + 30, colW, () -> {
            s.setProjectionOn(!s.isProjectionOn());
            rebuildWidgets();
        });
        if (admin) {
            int bw = (colW - 4) / 2;
            int ay = y + 54;
            button(s.isSharing() ? "&a同步：开" : "&7同步：关", "开关房间的投影同步；开启时会把 Litematica 里选中的投影上传给房间", lx, ay, bw, () -> s.setSharing(!s.isSharing()));
            button("&f上传", "把 Litematica 里选中的投影上传/替换为房间的共享投影", lx + bw + 4, ay, bw, s::push);
            button("&c删除", "删除房间的共享投影（所有人的副本消失，你本机的保留），会先确认", lx, ay + 24, bw, s::requestUnshare);
            button(s.isPushMode() ? "&a修改：开" : "&c修改：关", "开：你的改动（移动/旋转/镜像等）同步给所有人；关：你怎么改都不同步，别人的改动也先不应用", lx + bw + 4, ay + 24, bw, () -> s.setPushMode(!s.isPushMode()));
            button(s.isLayerSync() ? "&a渲染层同步：开" : "&7渲染层同步：关", "开：所有人的 Litematica 渲染层（只显示某一层/某个范围）跟着你，成员改了会还原", lx, ay + 48, colW, () -> s.setLayerSync(!s.isLayerSync()));
            layerEditor(s, lx, ay + 74, colW);
        } else if (s.isLayerSync()) {
            text("&7渲染层由管理员控制：&f" + s.layerModeName() + " " + s.layerAxisName(), lx, y + 56);
        }
        paneTitle = admin ? "&7投影范围（子区域） &f点击切换" : "&7投影范围（子区域，管理员选择）";
        List<Row> rows = new ArrayList<>();
        paneCols = new int[]{90};
        paneHeaders = new String[]{"子区域", "状态"};
        for (Map.Entry<String, Boolean> e : s.isTaskMode() ? s.regionList() : List.<Map.Entry<String, Boolean>>of()) {
            rows.add(new Row("&f" + e.getKey() + " &7" + s.regionSize(e.getKey()), new String[]{e.getValue() ? "&a参与" : "&7不参与"}, false,
                    () -> { s.toggleRegion(e.getKey()); rebuildWidgets(); }, ItemStack.EMPTY, ItemStack.EMPTY, 0, e.getKey(), List.of()));
        }
        pane = rows;
        if (rows.isEmpty()) text(s.isTaskMode() ? "&7还没有共享投影" : "&7简单模式：整个投影文件都参与", rx + 4, y + 46);
        if (!s.isTaskMode()) return;
        // 子区域说明: 一个 .litematic 文件可以由多块（子区域）组成
        int ny = paneY + paneH - FOOTER_H - 38; // 说明放在翻页栏上面，不盖住页码
        text("&7子区域：投影文件可由多块组成(如 A B C)", rx + 4, ny);
        text("&7管理员可让其中几块参与摆放，", rx + 4, ny + 11);
        text("&7不参与的块不显示、不统计、不验证", rx + 4, ny + 22);
    }

    /** 渲染层规则 (管理员): 模式、轴、层号，开了「渲染层同步」就会同步给所有人。 */
    private void layerEditor(ShareSession s, int lx, int y, int colW) {
        text("&7渲染层 &f" + s.layerModeName() + " " + s.layerAxisName() + (s.isLayerSync() ? " &a同步中" : " &7(未同步)"), lx, y);
        int bw = (colW - 4) / 2;
        button("&f模式：" + s.layerModeName(), "切换显示范围：全部 / 单层 / 范围 / 以上 / 以下", lx, y + 12, bw, () -> { s.cycleLayerMode(); rebuildWidgets(); });
        button("&f轴：" + s.layerAxisName(), "切换分层的方向轴 X / Y / Z", lx + bw + 4, y + 12, bw, () -> { s.cycleLayerAxis(); rebuildWidgets(); });
        for (int i = 0; i < s.layerValueCount(); i++) {
            final int which = i;
            int ry = y + 36 + i * 24;
            EditBox eb = box(lx, ry, 52, String.valueOf(s.layerValue(which)), "层", v -> {
                try { s.setLayerValue(which, Integer.parseInt(v.trim())); } catch (NumberFormatException ignored) { }
            });
            eb.setCanLoseFocus(true);
            String label = s.layerValueCount() == 2 ? (which == 0 ? "最低" : "最高") : "层";
            button("&f-", label + " -1", lx + 56, ry, 24, () -> { s.setLayerValue(which, s.layerValue(which) - 1); rebuildWidgets(); });
            button("&f+", label + " +1", lx + 84, ry, 24, () -> { s.setLayerValue(which, s.layerValue(which) + 1); rebuildWidgets(); });
            if (i == 0) button("&f到我", "把层号设到我脚下的位置", lx + 112, ry, colW - 112, () -> { s.layerToPlayer(); rebuildWidgets(); });
            else text("&7" + label, lx + 112, ry + 6);
        }
    }

    // ---------------- 材料 (材料 + 仓库合在一页: 左=开关/箱子管理/搜索/选中详情，右=清单) ----------------

    private static final String[] MAT_MODES = {"缺的", "全部", "仓库"};

    private void materialsTab(ShareSession s, int lx, int y, int colW, int rx, int rw) {
        if (s == null || !s.isJoined()) {
            text("&c先在「房间」页加入房间", lx, y);
            text("&7加入房间后显示", rx + 4, y + 46);
            return;
        }
        String[] prog = s.progressLine().isEmpty() ? new String[0] : s.progressLine().split(" \\| ", 2); // 进度和观测/更新时间分两行，免得盖住右栏
        text(prog.length == 0 ? "&7还没有团队进度" : "&f" + prog[0], lx, y);
        if (prog.length > 1) text("&7" + prog[1].replace(" | ", " · "), lx, y + 11);
        int ly = y + 26;
        int bw = (colW - 4) / 2;
        button(s.isAutoTake() ? "&a自动拿取：开" : "&7自动拿取：关", "高亮期间打开箱子，自动把选中的物品拿出来（箱子界面里这些格子也会高亮）", lx, ly, colW, () -> {
            s.setAutoTake(!s.isAutoTake());
            rebuildWidgets();
        });
        ly += 22;
        if (s.isAdmin()) {
            button(s.isChestMode() ? "&a箱子模式：开" : "&7箱子模式：关", "开启后按 Esc 回到游戏：左键箱子=允许上传（绿框），右键箱子=不允许（不会打开）", lx, ly, bw, () -> {
                s.setChestMode(!s.isChestMode());
                rebuildWidgets();
            });
            button("&c清空箱子", "清空所有允许上传的箱子和它们的记录，会先确认", lx + bw + 4, ly, bw, () -> Confirm.ask("&c清空所有允许的箱子？", "&7已允许的箱子和它们的记录都会删除", () -> {
                s.clearAllowed();
                results = List.of();
            }, null));
            ly += 22;
        }
        ly += 2;
        EditBox q = box(lx, ly, colW - 52, searchText, "搜箱子里的物品，如 铁锭", v -> searchText = v);
        q.setCanLoseFocus(true);
        button("&f搜", "搜索并高亮装有这个物品的箱子", lx + colW - 50, ly, 50, () -> {
            selectedMat = "";
            pickedMats.clear();
            results = s.pick(searchText, s.chests().matchIds(searchText), s.chests().find(searchText));
            rebuildWidgets();
        });
        ly += 24;
        text("&7允许 &f" + s.chests().allowedCount() + " &7个箱子 · 已记录 &f" + s.chests().size() + " &7个", lx, ly);
        ly += 13;

        // 选中的物品: 需求/仓库/背包 + 箱子位置
        MaterialReport.Row sel = s.materialRows().stream().filter(r -> r.item().equals(selectedMat)).findFirst().orElse(null);
        int room = Math.max(0, (height - ly - 30) / 11); // 剩余可放几行文字
        if (sel != null && room > 0) {
            text("&f" + sel.name() + " &7需要 &f" + sel.total() + " &7· 已摆 &a" + sel.placed() + " &7· 缺 &c" + sel.missing(), lx, ly);
            ly += 11;
            room--;
        }
        if (pickedMats.size() > 1 && room > 0) {
            text("&7已选 &f" + pickedMats.size() + " &7项（点列表里的行可增减）", lx, ly);
            ly += 11;
            room--;
        }
        if (!selectedMat.isEmpty() && room > 0) {
            text("&7仓库 &a" + s.chests().total(selectedMat) + " &7· 包 &b" + s.inventoryOf(selectedMat) + (results.isEmpty() ? " &7· 箱子里没有" : ""), lx, ly);
            ly += 11;
            room--;
        }
        if (!results.isEmpty() && room > 0) {
            long total = results.stream().mapToLong(ChestIndex.Hit::count).sum();
            text("&f" + total + " &7个 · &f" + results.size() + " &7个箱子（已高亮）", lx, ly);
            ly += 11;
            room--;
            for (int i = 0; i < Math.min(results.size(), Math.max(0, room - 1)); i++) {
                text("&f" + ChestIndex.describe(results.get(i)), lx, ly);
                ly += 11;
            }
            button("&7清除", "清除高亮，并取消自动拿取的物品", lx + colW - 50, ly - 11 * Math.min(results.size(), Math.max(0, room - 1)) - 13, 50, () -> {
                s.tracker().clearHighlight();
                results = List.of();
                selectedMat = "";
                pickedMats.clear();
                rebuildWidgets();
            });
        } else if (results.isEmpty() && !searchText.isBlank() && selectedMat.isEmpty()) {
            text("&c没有结果", lx, ly);
        }

        // 右栏: 材料清单 (缺的 / 全部) 或仓库里的所有物品，点一行 = 选中并查箱子
        sortButton(rx, y + 14, 90);
        button("&f显示：" + MAT_MODES[matMode], "切换右栏：只看缺的材料 / 全部材料 / 仓库里所有物品", rx + 94, y + 14, rw - 188, () -> {
            matMode = (matMode + 1) % MAT_MODES.length;
            rebuildWidgets();
        });
        button(unitMode ? "&f单位：桶组" : "&f单位：个数", "切换数量显示：只显示个数 / 换算成 桶+组+个（1组=该物品堆叠上限，1桶=54组）", rx + rw - 90, y + 14, 90, () -> {
            unitMode = !unitMode;
            rebuildWidgets();
        });
        List<Row> rows = new ArrayList<>();
        if (matMode == 2) {
            paneCols = new int[]{1};
            paneHeaders = new String[]{"名字", "仓库"};
            for (Map.Entry<String, Long> e : s.chests().allTotals().entrySet()) {
                String name = s.chests().nameOf(e.getKey());
                ItemStack icon = ChestIndex.iconOf(e.getKey());
                int stack = icon.isEmpty() ? 64 : icon.getMaxStackSize();
                rows.add(new Row("&f" + name, new String[]{withUnits("&a", e.getValue(), stack)}, pickedMats.contains(e.getKey()), () -> pickItem(s, e.getKey(), name),
                        icon, ItemStack.EMPTY, e.getValue(), name,
                        List.of("&f" + name, "&a仓库 " + e.getValue() + " &7(" + Units.fmt(e.getValue(), stack) + ")", "&71组=" + stack + "个 · 1桶=54组")));
            }
        } else {
            // 名字 1/5，总 / 缺 / 有 各占一份；「有」只显示总数，悬停才看仓+包
            paneCols = new int[]{1, 1, 1};
            paneHeaders = new String[]{"名字", "总", "缺", "有"};
            for (MaterialReport.Row r : s.materialRows()) {
                if (matMode == 0 && r.missing() <= 0) continue;
                long chest = s.chests().total(r.item());
                long bag = s.isCountInventory() ? s.inventoryOf(r.item()) : 0;
                long have = chest + bag;
                int stack = r.sample().isEmpty() ? 64 : r.sample().getMaxStackSize();
                boolean done = have >= r.missing(); // 有的已经够把缺的补齐: 沉底并变绿
                String[] cells = {withUnits("&f", r.total(), stack), withUnits(done ? "&a" : "&c", r.missing(), stack), (done ? "&a" : "&f") + have};
                List<String> tip = List.of("&f" + r.name() + " &7(1组=" + stack + "个 · 1桶=54组)",
                        "&7总 &f" + r.total() + " &7(" + Units.fmt(r.total(), stack) + ")",
                        "&c缺 " + r.missing() + " &7(" + Units.fmt(r.missing(), stack) + ")",
                        "&a仓 " + chest + " &7(" + Units.fmt(chest, stack) + ")",
                        "&b包 " + bag + " &7(" + Units.fmt(bag, stack) + ")",
                        "&f有 " + have + " &7= &a仓 " + chest + " &7+ &b包 " + bag);
                rows.add(new Row((done ? "&a" : "&f") + r.name(), cells, pickedMats.contains(r.item()), () -> pickItem(s, r.item(), r.name()),
                        r.sample(), ItemStack.EMPTY, r.missing(), r.name(), tip, done));
            }
        }
        pane = sorted(rows);
        if (rows.isEmpty()) {
            text(matMode == 2 ? "&7仓库还是空的（先允许箱子并打开一次）"
                    : s.materialRows().isEmpty() ? (s.isTaskMode() && s.effectiveFilter() != null && s.effectiveFilter().isEmpty() ? "&7没有分配给你的任务区域" : "&7还没有材料清单")
                    : "&a没有缺的材料", rx + 4, y + 46);
        }
    }

    /** 选中一个物品: 记下、在仓库里查它的箱子并高亮 (自动拿取也针对它)。 */
    private void pickItem(ShareSession s, String itemId, String name) {
        if (!pickedMats.remove(itemId)) pickedMats.add(itemId);
        selectedMat = pickedMats.contains(itemId) ? itemId : (pickedMats.isEmpty() ? "" : pickedMats.stream().reduce((a, b) -> b).get());
        searchText = pickedMats.size() == 1 ? name : "";
        if (pickedMats.isEmpty()) {
            s.tracker().clearHighlight();
            results = List.of();
        } else {
            // 所有选中的物品: 同一个箱子里的数量合并，数量多的在前；高亮和自动拿取都针对全部选中的物品
            Map<String, ChestIndex.Hit> merged = new java.util.LinkedHashMap<>();
            for (String id : pickedMats) {
                for (ChestIndex.Hit h : s.chests().findItem(id)) merged.merge(h.rec().key(), h, (a, b) -> new ChestIndex.Hit(a.rec(), a.count() + b.count()));
            }
            List<ChestIndex.Hit> hits = new ArrayList<>(merged.values());
            hits.sort(java.util.Comparator.comparingInt(ChestIndex.Hit::count).reversed());
            results = s.pick(name, Set.copyOf(pickedMats), hits);
        }
        rebuildWidgets();
    }

    // ---------------- 验证 (团队原理图验证器) ----------------

    private void verifyTab(ShareSession s, int lx, int y, int colW, int rx, int rw) {
        if (s == null || !s.isJoined()) {
            text("&c先在「房间」页加入房间", lx, y);
            text("&7加入房间后显示", rx + 4, y + 46);
            return;
        }
        VerifyModel m = s.verifyModel();
        int refreshW = 62;
        if (s.isAdmin()) {
            button(s.isVerifyOn() ? "&a团队验证：开" : "&7团队验证：关", "开启后所有人的扫描会同步缺失/错误/多余/正确的方块，别人摆的拆的几秒内就能看到", lx, y, s.isVerifyOn() ? colW - refreshW - 4 : colW, s::toggleVerify);
        } else {
            text(s.isVerifyOn() ? "&a团队验证：开" : "&7团队验证：关（管理员可开启）", lx, y + 6);
        }
        if (s.isVerifyOn()) {
            button("&f刷新", "重新扫描并从服务器拉取所有区块的最新结果（每分钟也会自动刷新一次）", lx + colW - refreshW, y, refreshW, s::refreshVerify);
        }
        int cy = y + 30;
        if (s.isVerifyOn()) {
            Set<String> f = s.effectiveFilter();
            long[] t = m.totals(f);
            text(VerifyModel.tag(4) + "正确 &f" + m.correct(f) + "  " + VerifyModel.tag(0) + "缺失 &f" + t[0] + "  " + VerifyModel.tag(1) + "错误 &f" + t[1], lx, cy);
            text(VerifyModel.tag(2) + "多余 &f" + t[2] + "  " + VerifyModel.tag(3) + "状态错误 &f" + t[3], lx, cy + 12);
            long ago = m.lastUpdate() == 0 ? -1 : (System.currentTimeMillis() - m.lastUpdate()) / 1000;
            text("&7观测 &f" + m.observedChunks() + " &7区块" + (ago >= 0 ? " · 更新于 &f" + ago + " &7秒前" : ""), lx, cy + 24);
            int bw = (colW - 12) / 4;
            VerifyModel.Selection sel = m.selection();
            button(VerifyModel.tag(0) + "缺失", "在世界里高亮所有缺失的方块", lx, cy + 40, bw, () -> { m.select(0, ""); verifyType = 0; rebuildWidgets(); }).active = !(sel.type() == 0 && sel.groupKey().isEmpty());
            button(VerifyModel.tag(1) + "错误", "高亮摆错了方块种类的位置", lx + (bw + 4), cy + 40, bw, () -> { m.select(1, ""); verifyType = 1; rebuildWidgets(); }).active = !(sel.type() == 1 && sel.groupKey().isEmpty());
            button(VerifyModel.tag(2) + "多余", "高亮原理图范围内多出来的方块", lx + 2 * (bw + 4), cy + 40, bw, () -> { m.select(2, ""); verifyType = 2; rebuildWidgets(); }).active = !(sel.type() == 2 && sel.groupKey().isEmpty());
            button(VerifyModel.tag(3) + "状态", "高亮方块种类对但朝向/状态不对的位置", lx + 3 * (bw + 4), cy + 40, bw, () -> { m.select(3, ""); verifyType = 3; rebuildWidgets(); }).active = !(sel.type() == 3 && sel.groupKey().isEmpty());
            button(VerifyModel.tag(4) + "正确", "列出并高亮已经摆对的方块", lx, cy + 64, bw, () -> { m.select(4, ""); verifyType = 4; rebuildWidgets(); }).active = !(sel.type() == 4 && sel.groupKey().isEmpty());
            button("&f全部", "高亮所有问题类别（不含正确）", lx + (bw + 4), cy + 64, bw, () -> { m.select(99, ""); verifyType = -1; rebuildWidgets(); }).active = sel.type() != 99;
            button("&7清除", "取消世界里的高亮，列表恢复显示所有类别", lx + 2 * (bw + 4), cy + 64, bw, () -> { m.clearSelection(); verifyType = -1; rebuildWidgets(); });
            if (s.isManualFirstVerification()) {
                button("&a首次：开", "首次验证模式（仅你本机）：扫描不限制距离，所有已加载的区块（含缓存区块）都扫描上报。点击关闭", lx + 3 * (bw + 4), cy + 64, bw, () -> { s.setManualFirstVerification(false); rebuildWidgets(); });
            } else {
                button("&7首次：关", "手动开启首次验证模式（仅你本机）：扫描不限制距离，所有已加载的区块（含缓存区块）都扫描上报。会先确认", lx + 3 * (bw + 4), cy + 64, bw,
                        () -> Confirm.ask("&c开启首次验证模式？", "&7扫描不再限制玩家附近，缓存区块的旧数据可能被当成真实状态上报", () -> s.setManualFirstVerification(true), null));
            }
            EditBox fb = box(lx, cy + 92, colW, verifyFilter, "按方块名筛选，如 石头", v -> { verifyFilter = v; rebuildWidgets(); });
            fb.setCanLoseFocus(true);
            text("&7高亮只显示玩家附近，每区块每类最多 64 个", lx, cy + 118);
            if (s.touchedChunks() > 0 && m.observedChunks() < s.touchedChunks()) {
                text(s.isFirstVerification() ? "&7首次验证：扫描所有已加载的区块（不限距离）；" : "&7首次需要大家一起跑图把整个投影更新完；", lx, cy + 130);
                text("&7只更新玩家附近 &f" + Math.max(1, ShareMod.config().scanRadius) + " &7区块内的数据（&f" + m.observedChunks() + "/" + s.touchedChunks() + "&7）", lx, cy + 141);
            }
        } else {
            text("&7开启后所有人的扫描会同步缺失/错误/多余方块，", lx, cy);
            text("&7别人摆的、拆的，几秒内就能看到，不用重新验证", lx, cy + 12);
        }
        sortButton(rx, y + 14, 90);
        List<Row> rows = new ArrayList<>();
        int filterType = verifyType;
        String q = verifyFilter.trim().toLowerCase(java.util.Locale.ROOT);
        paneCols = new int[]{190};
        paneHeaders = new String[]{"问题", "数量"};
        for (VerifyModel.Group g : m.groups(s.effectiveFilter())) {
            if (filterType >= 0 && filterType <= 4 && g.type() != filterType) continue; // 上面的类别按钮同时筛选列表
            if (filterType < 0 && g.type() == 4) continue; // 默认不显示「正确」，点「正确」按钮才显示
            String desc = VerifyModel.describe(g);
            if (!q.isEmpty() && !desc.toLowerCase(java.util.Locale.ROOT).contains(q) && !g.expected().contains(q) && !g.found().contains(q)) continue;
            boolean selected = m.isPicked(g.key());
            String color = VerifyModel.tag(g.type()); // 文字和数量同色，一眼看出状态
            // 错误方块: 左图标是应该有的，右图标是实际摆的
            ItemStack left = g.type() == 1 ? VerifyModel.blockIcon(g.expected()) : VerifyModel.iconOf(g);
            ItemStack right = g.type() == 1 ? VerifyModel.blockIcon(g.found()) : ItemStack.EMPTY;
            ItemStack unitIcon = left.isEmpty() ? right : left;
            int stack = unitIcon.isEmpty() ? 64 : unitIcon.getMaxStackSize();
            rows.add(new Row(color + desc, new String[]{withUnits(color, g.count(), stack)}, selected, () -> { m.togglePick(g.key()); rebuildWidgets(); }, // 点一行切换选中，可同时选多条
                    left, right, g.count(), desc,
                    List.of(color + desc, color + g.count() + " &7(" + Units.fmt(g.count(), stack) + ")", "&71组=" + stack + "个 · 1桶=54组")));
        }
        pane = sorted(rows);
        if (rows.isEmpty()) text(s.isVerifyOn() ? "&7没有符合的方块" : "&7开启团队验证后显示", rx + 4, y + 46);
    }

    // ---------------- 任务 (管理员建立并分配给玩家，可对应子区域) ----------------

    private static final String[] STATUS_NAMES = {"待办", "进行中", "完成"};
    private static final String[] STATUS_COLORS = {"&7", "&e", "&a"};

    private void loadForm(share.protocol.TaskRecord t) {
        formId = t.id();
        formTitle = t.title();
        formRegion = t.region();
        formNote = t.note();
        formAssignees.clear();
        formAssignees.addAll(t.assignees());
    }

    private static void clearForm() {
        formId = "";
        formTitle = "";
        formRegion = "";
        formNote = "";
        formAssignees.clear();
    }

    private void taskTab(ShareSession s, int lx, int y, int colW, int rx, int rw) {
        if (s == null || !s.isJoined()) {
            text("&c先在「房间」页加入房间", lx, y);
            text("&7加入房间后显示", rx + 4, y + 46);
            return;
        }
        share.protocol.TaskRecord selected = s.tasks().stream().filter(t -> t.id().equals(formId)).findFirst().orElse(null);
        if (selected == null && !formId.isEmpty()) clearForm(); // 选中的任务被删了
        int bw = (colW - 4) / 2;
        if (s.isAdmin()) {
            text(formId.isEmpty() ? "&7新建任务" : "&7编辑任务", lx, y);
            EditBox tb = box(lx, y + 12, colW, formTitle, "任务名，如 盖东楼", v -> formTitle = v);
            tb.setCanLoseFocus(true);
            List<String> regions = new ArrayList<>();
            regions.add("");
            if (s.isTaskMode()) for (Map.Entry<String, Boolean> e : s.regionList()) regions.add(e.getKey());
            if (s.isTaskMode()) {
                button("&f区域：" + (formRegion.isEmpty() ? "无" : formRegion), "这个任务对应共享投影的哪个子区域（点击切换）", lx, y + 36, bw, () -> {
                    formRegion = regions.get((Math.max(0, regions.indexOf(formRegion)) + 1) % regions.size());
                    rebuildWidgets();
                });
            } else {
                formRegion = ""; // 简单模式：整个投影文件，任务不关联子区域
                text("&7简单模式：任务不关联子区域", lx, y + 42);
            }
            button(formId.isEmpty() ? "&a创建" : "&a保存", formId.isEmpty() ? "创建任务并下发给选中的玩家" : "保存修改（任务名、区域、玩家）", lx + bw + 4, y + 36, bw, () -> {
                s.saveTask(new share.protocol.TaskRecord(formId, formTitle.trim(), formRegion, new ArrayList<>(formAssignees),
                        selected == null ? 0 : selected.status(), formNote, ""));
                if (formId.isEmpty()) clearForm();
                rebuildWidgets();
            });
            text("&7分配给（点击选择）", lx, y + 62);
            int col = 0, row = 0, mw = (colW - 8) / 3;
            for (ShareSession.Member m : s.members()) {
                if (row >= 3) break;
                boolean on = formAssignees.contains(m.uuid());
                button((on ? "&a" : "&7") + m.name(), on ? "取消分配给 " + m.name() : "分配给 " + m.name(), lx + col * (mw + 4), y + 74 + row * 22, mw, () -> {
                    if (!formAssignees.remove(m.uuid())) formAssignees.add(m.uuid());
                    rebuildWidgets();
                });
                if (++col == 3) { col = 0; row++; }
            }
            if (!formId.isEmpty()) button("&7新建", "清空表单，改为新建任务", lx + colW - 50, y + 56, 50, () -> { clearForm(); rebuildWidgets(); });
        } else {
            text("&7管理员可以建立任务并分配给玩家", lx, y);
        }
        // 选中任务: 状态和操作 (被分配的玩家和管理员可以改状态)
        if (selected != null) {
            int dy = s.isAdmin() ? y + 174 : y + 30;
            text("&f" + selected.title() + (selected.region().isEmpty() ? "" : " &7(" + selected.region() + ")"), lx, dy - 14);
            boolean canStatus = s.isAdmin() || s.isMine(selected);
            int sw = (colW - 8) / 3;
            for (int st = 0; st < 3; st++) {
                final int status = st;
                Button b = button(STATUS_COLORS[st] + STATUS_NAMES[st], canStatus ? "把任务标为" + STATUS_NAMES[st] : "只有被分配的玩家和管理员能改状态", lx + st * (sw + 4), dy, sw,
                        () -> s.setTaskStatus(selected.id(), status));
                b.active = canStatus && selected.status() != st;
            }
            if (!selected.region().isEmpty()) {
                boolean locating = s.isLocating(selected.id());
                button(locating ? "&c取消定位" : "&f定位", locating ? "取消定位，并删掉小地图上的路径点" : "把右上角的区域选择改成这个任务的区域，并在 Xaero 小地图上加一个路径点（走到附近或取消定位时自动删除）", lx, dy + 22, bw,
                        () -> { if (locating) s.cancelLocate(true); else s.locate(selected); rebuildWidgets(); });
            }
            if (s.isAdmin()) {
                button("&c删除", "删除这个任务，会先确认", lx + bw + 4, dy + 22, bw,
                        () -> Confirm.ask("&c删除任务？", "&f" + selected.title(), () -> s.deleteTask(selected.id()), null));
            }
        }

        // 右栏: 任务列表
        button(onlyMine ? "&f显示：我的" : "&f显示：全部", "只显示分配给我的任务 / 显示全部任务", rx, y + 14, rw, () -> { onlyMine = !onlyMine; rebuildWidgets(); });
        List<Row> rows = new ArrayList<>();
        paneCols = new int[]{190};
        paneHeaders = new String[]{"任务", "负责人"};
        for (int st = 0; st < 3; st++) {
            for (share.protocol.TaskRecord t : s.tasks()) {
                if (t.status() != st || (onlyMine && !s.isMine(t))) continue;
                StringBuilder who = new StringBuilder();
                for (String u : t.assignees()) who.append(who.length() > 0 ? "、" : "").append(s.nameOf(u));
                String left = STATUS_COLORS[st] + "[" + STATUS_NAMES[st] + "] &f" + t.title() + (t.region().isEmpty() ? "" : " &7" + t.region());
                String right = (s.isMine(t) ? "&a" : "&7") + (who.length() == 0 ? "未分配" : who.toString());
                rows.add(new Row(left, new String[]{right}, t.id().equals(formId), () -> { loadForm(t); rebuildWidgets(); }, ItemStack.EMPTY, ItemStack.EMPTY, st, t.title(), List.of()));
            }
        }
        pane = rows;
        if (rows.isEmpty()) text(s.isAdmin() ? "&7还没有任务，在左边新建" : "&7还没有任务", rx + 4, y + 46);
    }

    // ---------------- 绘制与右栏交互 ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        for (Line l : lines) g.drawString(font, Chat.parse(l.text()).getVisualOrderText(), l.x(), l.y(), WHITE);
        for (Face f : faces) {
            try {
                var info = minecraft.playerSkinRenderCache().getOrDefault(net.minecraft.world.item.component.ResolvableProfile.createUnresolved(java.util.UUID.fromString(f.uuid())));
                net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, info.playerSkin(), f.x(), f.y(), 16);
            } catch (RuntimeException ignored) { }
        }
        if (paneVisible) renderPane(g, mouseX, mouseY);
    }

    /** 右栏: 表头 + 固定列宽的行 + 页脚页码；悬停显示单位换算等提示。 */
    private void renderPane(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Chat.parse(paneTitle).getVisualOrderText(), paneX, paneTitleY, WHITE);
        g.fill(paneX, paneY, paneX + paneW, paneY + paneH, 0x80000000);
        int n = paneCols.length;
        // 名字占 1/5 (只有一个数据列时占 55%)，其余的数据列平分；表头和内容都靠左
        int nameW = n >= 3 ? paneW / 5 : n == 2 ? paneW * 2 / 5 : paneW * 55 / 100;
        int colW = n == 0 ? 0 : (paneW - nameW) / n;
        int[] colLeft = new int[n];
        for (int k = 0; k < n; k++) colLeft[k] = paneX + nameW + k * colW + 4;
        int nameRight = paneX + (n == 0 ? paneW : nameW) - 2; // 名字列占前面的
        int top = paneY + 2;
        if (paneHeaders.length > 0) {
            g.drawString(font, Chat.parse("&7" + paneHeaders[0]).getVisualOrderText(), paneX + 24, top + 2, WHITE);
            for (int k = 0; k < n && k + 1 < paneHeaders.length; k++) {
                g.drawString(font, Chat.parse("&7" + paneHeaders[k + 1]).getVisualOrderText(), colLeft[k], top + 2, WHITE);
            }
            g.fill(paneX + 2, top + HEADER_H - 2, paneX + paneW - 2, top + HEADER_H - 1, 0x55FFFFFF);
            top += HEADER_H;
        }
        int rpp = rowsPerPage();
        int from = page * rpp;
        for (int i = from; i < Math.min(pane.size(), from + rpp); i++) {
            Row r = pane.get(i);
            int y = top + (i - from) * ROW_H;
            boolean hover = mouseX >= paneX && mouseX < paneX + paneW && mouseY >= y && mouseY < y + ROW_H;
            if (r.selected()) g.fill(paneX + 1, y, paneX + paneW - 1, y + ROW_H, 0x66FFFFFF);
            else if (hover) g.fill(paneX + 1, y, paneX + paneW - 1, y + ROW_H, 0x33FFFFFF);
            int tx = paneX + 4;
            if (!r.icon().isEmpty()) {
                g.renderItem(r.icon(), paneX + 3, y + 1);
                tx = paneX + 24;
            }
            if (!r.icon2().isEmpty()) { // 错误方块: 左为正确的，右为实际摆的
                g.renderItem(r.icon2(), paneX + 21, y + 1);
                tx = paneX + 42;
            }
            g.enableScissor(paneX, y, Math.max(paneX + 1, nameRight), y + ROW_H); // 名字太长就截断，不盖住右边的列
            g.drawString(font, Chat.parse(r.left()).getVisualOrderText(), tx, y + 5, WHITE);
            g.disableScissor();
            for (int k = 0; k < n && k < r.cells().length; k++) {
                g.enableScissor(colLeft[k] - 2, y, colLeft[k] + colW - 2, y + ROW_H); // 数字太长不盖住下一列
                g.drawString(font, Chat.parse(r.cells()[k]).getVisualOrderText(), colLeft[k], y + 5, WHITE);
                g.disableScissor();
            }
            if (hover && !r.tip().isEmpty()) g.setComponentTooltipForNextFrame(font, tipLines(r.tip()), mouseX, mouseY);
        }
        var foot = Chat.parse("&7第 &f" + (page + 1) + "&7/" + pageCount() + " 页 · 共 &f" + pane.size() + " &7项").getVisualOrderText();
        g.drawString(font, foot, paneX + (paneW - font.width(foot)) / 2, paneY + paneH - FOOTER_H + 7, WHITE);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (paneVisible && !pane.isEmpty() && event.button() == 0 && event.x() >= paneX && event.x() < paneX + paneW) {
            int top = paneY + 2 + (paneHeaders.length > 0 ? HEADER_H : 0);
            int rpp = rowsPerPage();
            int idx = (int) ((event.y() - top) / ROW_H);
            if (event.y() >= top && idx >= 0 && idx < rpp && page * rpp + idx < pane.size()) {
                pane.get(page * rpp + idx).onClick().run();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (paneVisible && !pane.isEmpty() && mouseX >= paneX && mouseX < paneX + paneW && mouseY >= paneY && mouseY < paneY + paneH) {
            int target = Math.max(0, Math.min(pageCount() - 1, page - (int) Math.signum(scrollY))); // 滚轮翻页
            if (target != page) {
                page = target;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
