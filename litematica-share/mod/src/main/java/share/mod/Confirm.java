package share.mod;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;

/** 危险操作的确认弹窗 (原版确认界面)；选完回到之前的界面。 */
final class Confirm {
    private static BooleanConsumer pending;

    static void ask(String title, String message, Runnable yes, Runnable no) {
        Minecraft mc = Minecraft.getInstance();
        Screen prev = mc.screen;
        BooleanConsumer cb = ok -> {
            pending = null;
            mc.setScreen(prev);
            if (ok) yes.run(); else if (no != null) no.run();
        };
        pending = cb;
        mc.setScreen(new ConfirmScreen(cb, Chat.parse(title), Chat.parse(message)));
    }

    /** 测试用: 代替玩家点「是/否」。 */
    static boolean answer(boolean ok) {
        BooleanConsumer p = pending;
        if (p == null) return false;
        p.accept(ok);
        return true;
    }

    private Confirm() { }
}
