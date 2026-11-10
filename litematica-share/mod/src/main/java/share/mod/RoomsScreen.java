package share.mod;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 房间列表: 左边是本机保存的房间 (点名字切换、删=忘掉) 和添加表单，右边是整个服务器的房间列表。
 * 点服务器上的房间：已经保存过密钥就直接切换，否则填进左边的表单，输入密码后加入。
 * 可以加入多个房间，但同一时间只连一个、只加载一个房间的投影数据。
 */
final class RoomsScreen extends Screen {
    private static final int ROW_H = 20;

    private final Screen parent;
    private String newRoom = "", newKey = "";
    private EditBox keyBox;
    private List<RoomFetcher.Info> server = List.of();
    private String status = "";
    private int scroll;

    RoomsScreen(Screen parent) {
        super(Component.literal("房间列表"));
        this.parent = parent;
        refresh();
    }

    private void refresh() {
        ShareConfig cfg = ShareMod.config();
        if (!cfg.hasServer()) { status = "&c先在房间页填好服务器地址"; return; }
        status = "&7加载中…";
        RoomFetcher.fetch(cfg.host, cfg.port).whenComplete((list, err) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            if (err != null) {
                status = "&c获取失败：" + (err.getMessage() == null ? err.toString() : err.getMessage());
            } else {
                server = list;
                status = list.isEmpty() ? "&7服务器上还没有房间" : "";
            }
        }));
    }

    private int leftW() { return Math.max(200, Math.min(340, width / 3)); }

    private int leftX() { return Math.max(10, (width - Math.min(width - 20, 1100)) / 2); }

    private int rightX() { return leftX() + leftW() + 14; }

    private int rightW() { return Math.max(120, Math.min(width - 10, leftX() + Math.min(width - 20, 1100)) - rightX()); }

    @Override
    protected void init() {
        ShareConfig cfg = ShareMod.config();
        int x = leftX(), w = leftW(), y = 36;
        List<ShareConfig.Saved> rooms = new ArrayList<>(cfg.rooms);
        int maxRows = Math.max(1, (height - 160) / 24);
        for (int i = 0; i < Math.min(maxRows, rooms.size()); i++) {
            ShareConfig.Saved r = rooms.get(i);
            ShareSession cur = ShareMod.session();
            boolean current = cur != null && r.name.equals(cfg.room);
            Button join = Button.builder(Chat.parse((current ? "&a● " : "&f") + r.name + (current ? " &7(当前)" : "")), b -> {
                        if (!current) ShareMod.switchTo(r.name, r.key);
                        onClose();
                    }).bounds(x, y + i * 24, w - 44, 20)
                    .tooltip(Tooltip.create(Chat.parse(current ? "当前房间" : "切换到这个房间（只加载它的投影数据）"))).build();
            join.active = !current;
            addRenderableWidget(join);
            addRenderableWidget(Button.builder(Chat.parse("&c删"), b -> {
                        ShareMod.forgetRoom(r.name);
                        rebuildWidgets();
                    }).bounds(x + w - 40, y + i * 24, 40, 20).tooltip(Tooltip.create(Chat.parse("忘掉这个房间（不影响房间本身）"))).build());
        }
        int fy = Math.min(y + Math.min(maxRows, rooms.size()) * 24 + 22, height - 84);
        EditBox nr = new EditBox(font, x, fy, w, 20, Component.literal("房间名"));
        nr.setHint(Component.literal("房间名（可点右边的列表选）"));
        nr.setValue(newRoom);
        nr.setResponder(v -> newRoom = v);
        addRenderableWidget(nr);
        keyBox = new EditBox(font, x, fy + 24, w, 20, Component.literal("密钥"));
        keyBox.setHint(Component.literal("密码"));
        keyBox.setValue(newKey);
        keyBox.setResponder(v -> newKey = v);
        addRenderableWidget(keyBox);
        addRenderableWidget(Button.builder(Chat.parse("&f添加并加入"), b -> {
                    if (newRoom.isBlank()) return;
                    ShareMod.switchTo(newRoom.trim(), newKey.trim());
                    onClose();
                }).bounds(x, fy + 48, w, 20).tooltip(Tooltip.create(Chat.parse("保存这个房间并切换过去；没有的房间名会新建一个房间，你是主管理员"))).build());
        addRenderableWidget(Button.builder(Chat.parse("&7返回"), b -> onClose()).bounds(x, height - 28, w, 20).build());
        addRenderableWidget(Button.builder(Chat.parse("&f刷新"), b -> refresh()).bounds(rightX() + rightW() - 70, 14, 70, 20)
                .tooltip(Tooltip.create(Chat.parse("重新获取服务器上的房间列表"))).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int listY() { return 40; }

    private int listH() { return height - listY() - 36; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawString(font, Chat.parse("&f我的房间 &7· 同一时间只加载一个").getVisualOrderText(), leftX(), 20, 0xFFFFFFFF);
        g.drawString(font, Chat.parse("&f服务器上的房间 &7· 点一个，输入密码加入").getVisualOrderText(), rightX(), 20, 0xFFFFFFFF);
        int x = rightX(), y = listY(), w = rightW(), h = listH();
        g.fill(x, y, x + w, y + h, 0x80000000);
        if (!status.isEmpty()) g.drawString(font, Chat.parse(status).getVisualOrderText(), x + 6, y + 6, 0xFFFFFFFF);
        g.enableScissor(x, y, x + w, y + h);
        int ry = y + 2 - scroll;
        ShareConfig cfg = ShareMod.config();
        for (RoomFetcher.Info r : server) {
            if (ry + ROW_H >= y && ry <= y + h) {
                boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H;
                boolean saved = cfg.rooms.stream().anyMatch(s -> s.name.equals(r.name()));
                if (hover) g.fill(x + 1, ry, x + w - 1, ry + ROW_H, 0x33FFFFFF);
                g.drawString(font, Chat.parse("&f" + r.name() + (saved ? " &a(已保存)" : "")).getVisualOrderText(), x + 6, ry + 6, 0xFFFFFFFF);
                String right = "&7在线 &f" + r.online() + (r.sharing() ? " &7· 投影 &f" + (r.projection().isEmpty() ? "有" : r.projection()) : " &7· 无投影") + (r.taskMode() ? " &7· &a任务" : "");
                var rc = Chat.parse(right).getVisualOrderText();
                g.drawString(font, rc, x + w - font.width(rc) - 6, ry + 6, 0xFFFFFFFF);
            }
            ry += ROW_H;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int x = rightX(), y = listY(), w = rightW();
        if (event.button() == 0 && event.x() >= x && event.x() < x + w && event.y() >= y && event.y() < y + listH()) {
            int idx = (int) ((event.y() - y - 2 + scroll) / ROW_H);
            if (idx >= 0 && idx < server.size()) {
                String name = server.get(idx).name();
                ShareConfig.Saved saved = ShareMod.config().rooms.stream().filter(s -> s.name.equals(name)).findFirst().orElse(null);
                if (saved != null) { // 已经保存过密钥: 直接切换
                    if (!name.equals(ShareMod.config().room) || ShareMod.session() == null) ShareMod.switchTo(saved.name, saved.key);
                    onClose();
                } else { // 没保存过: 填进表单，输入密码后加入
                    newRoom = name;
                    newKey = "";
                    rebuildWidgets();
                    setFocused(keyBox);
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, server.size() * ROW_H + 4 - listH());
        scroll = (int) Math.max(0, Math.min(max, scroll - scrollY * ROW_H * 2));
        return true;
    }
}
