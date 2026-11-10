package share.mod;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 提示信息统一风格 (系列模组通用，见 CLAUDE.md「信息提示原则」): 主色灰 &7，重点白 &f，正向 &a，反向 &c；一条一行，能不说就不说。
 * 文本里用 &x 写颜色(&r 重置)；还支持 &#RRGGBB 任意颜色 (验证类别要和 Litematica 配置的颜色一致)。
 */
final class Chat {
    static final String PREFIX = "&7[&fLS&7] ";
    static final String NOT_ADMIN = "&c仅管理员可用";

    static Component parse(String amp) {
        MutableComponent root = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder seg = new StringBuilder();
        for (int i = 0; i < amp.length(); i++) {
            char c = amp.charAt(i);
            if (c == '&' && i + 1 < amp.length()) {
                char n = amp.charAt(i + 1);
                if (n == '#' && i + 7 < amp.length() + 0 && isHex(amp, i + 2)) {
                    flush(root, seg, style);
                    style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(amp.substring(i + 2, i + 8), 16)));
                    i += 7;
                    continue;
                }
                ChatFormatting f = ChatFormatting.getByCode(n);
                if (f != null) {
                    flush(root, seg, style);
                    style = f == ChatFormatting.RESET ? Style.EMPTY : style.applyFormat(f);
                    i++;
                    continue;
                }
            }
            seg.append(c);
        }
        flush(root, seg, style);
        return root;
    }

    private static boolean isHex(String s, int from) {
        if (from + 6 > s.length()) return false;
        for (int k = from; k < from + 6; k++) if (Character.digit(s.charAt(k), 16) < 0) return false;
        return true;
    }

    private static void flush(MutableComponent root, StringBuilder seg, Style style) {
        if (seg.length() == 0) return;
        root.append(Component.literal(seg.toString()).withStyle(style));
        seg.setLength(0);
    }

    static void say(String amp) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(parse(PREFIX + amp), false);
    }

    private Chat() { }
}
