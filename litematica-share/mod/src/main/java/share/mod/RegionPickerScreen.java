package share.mod;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 区域选择列表 (从界面导航栏的「区域」按钮打开): 全部 / 任务区域 / 各子区域(可多选)。
 * 选择同时决定材料、验证的统计范围和本机渲染哪些子区域。
 */
final class RegionPickerScreen extends Screen {
    private record Opt(String label, String right, boolean on, Runnable click) { }

    private static final int ROW_H = 20, W = 320;

    private final Screen parent;
    private int scroll;

    RegionPickerScreen(Screen parent) {
        super(Component.literal("选择区域"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Chat.parse("&f完成"), b -> onClose()).bounds((width - 120) / 2, height - 28, 120, 20).build());
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private List<Opt> options() {
        ShareSession s = ShareMod.session();
        List<Opt> out = new ArrayList<>();
        if (s == null) return out;
        int mode = s.filterModeNow();
        out.add(new Opt("&f全部", "&7整个投影", mode == 0, s::setFilterAll));
        var mine = s.myRegions();
        out.add(new Opt("&f任务区域", mine.isEmpty() ? "&7你没有任务区域" : "&7" + String.join("、", mine), mode == 1, s::setFilterMine));
        for (Map.Entry<String, Boolean> e : s.regionList()) {
            String name = e.getKey();
            out.add(new Opt("&f" + name, "&7" + s.regionSize(name) + (e.getValue() ? "" : " 不参与"), s.isFilterRegion(name), () -> s.toggleFilterRegion(name)));
        }
        return out;
    }

    private int listX() { return (width - W) / 2; }

    private int listY() { return 44; }

    private int listH() { return height - listY() - 40; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(font, Chat.parse("&f选择区域 &7· 材料、验证和渲染都按所选区域（可多选）").getVisualOrderText(), width / 2, 20, 0xFFFFFFFF);
        int x = listX(), y = listY(), h = listH();
        g.fill(x, y, x + W, y + h, 0x80000000);
        g.enableScissor(x, y, x + W, y + h);
        int ry = y + 2 - scroll;
        for (Opt o : options()) {
            if (ry + ROW_H >= y && ry <= y + h) {
                boolean hover = mouseX >= x && mouseX < x + W && mouseY >= ry && mouseY < ry + ROW_H;
                if (o.on()) g.fill(x + 1, ry, x + W - 1, ry + ROW_H, 0x66FFD700);
                else if (hover) g.fill(x + 1, ry, x + W - 1, ry + ROW_H, 0x33FFFFFF);
                g.drawString(font, Chat.parse((o.on() ? "&a[x] " : "&7[ ] ") + o.label()).getVisualOrderText(), x + 6, ry + 6, 0xFFFFFFFF);
                int rw = font.width(Chat.parse(o.right()).getVisualOrderText());
                g.drawString(font, Chat.parse(o.right()).getVisualOrderText(), x + W - rw - 6, ry + 6, 0xFFFFFFFF);
            }
            ry += ROW_H;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int x = listX(), y = listY();
        if (event.button() == 0 && event.x() >= x && event.x() < x + W && event.y() >= y && event.y() < y + listH()) {
            int idx = (int) ((event.y() - y - 2 + scroll) / ROW_H);
            List<Opt> opts = options();
            if (idx >= 0 && idx < opts.size()) {
                opts.get(idx).click().run();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, options().size() * ROW_H + 4 - listH());
        scroll = (int) Math.max(0, Math.min(max, scroll - scrollY * ROW_H * 2));
        return true;
    }
}
