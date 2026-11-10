package share.mod;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class ShareMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("litematica_share");

    private static ShareConfig config;
    private static ShareSession session;
    private static boolean openGuiRequested;
    /** 命令执行完聊天栏才关闭，所以要弹界面的操作等没有打开界面时再执行。 */
    private static Runnable deferred;

    static void later(Runnable r) { deferred = r; }

    // ---- 供按钮界面使用 ----
    static ShareSession session() { return session; }
    static ShareConfig config() { return config; }

    /** 界面里的「加入」: 地址为空表示沿用已保存的地址。 */
    static void guiJoin(String address, String room, String key) {
        if (!address.isBlank() && setServer(address.trim()) == 0) return;
        if (!config.hasServer()) { say("&c请填写服务器地址 (地址:端口)"); return; }
        if (room.isBlank()) { say("&c请填写房间名"); return; }
        switchTo(room.trim(), key.trim());
    }

    /** 加入/切换到某个房间 (记住它)。同一时间只连一个房间: 先清掉当前房间的投影副本，再连新的，只加载新房间的投影数据。 */
    static void switchTo(String name, String key) {
        if (session != null && !name.equals(config.room)) session.leaveCleanup();
        config.room = name;
        config.key = key;
        config.autoJoin = true;
        config.remember(name, key);
        config.save();
        connect();
    }

    static void forgetRoom(String name) {
        if (name.equals(config.room)) guiLeave();
        config.forget(name);
        if (name.equals(config.room)) { config.room = ""; config.key = ""; }
        config.save();
    }

    static void guiLeave() {
        if (session != null) { session.leaveCleanup(); session.close(); }
        session = null;
        config.autoJoin = false;
        config.save();
        say("&7已离开房间");
    }

    @Override
    public void onInitializeClient() {
        config = ShareConfig.load();
        ChestTracker.registerHooks();
        KeyMapping openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.litematica_share.open",
                InputConstants.Type.KEYSYM, InputConstants.KEY_L, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (session != null) session.onClientTick();
            while (openKey.consumeClick()) openGuiRequested = true;
            if (deferred != null && mc.screen == null && mc.player != null) {
                Runnable r = deferred;
                deferred = null;
                r.run();
            }
            if (openGuiRequested && mc.screen == null && mc.player != null) { // 命令执行后聊天栏才关闭，等下一刻再开界面
                openGuiRequested = false;
                mc.setScreen(new ShareScreen());
            }
        });
        // 房间配置自动保存；进入世界时自动重新加入，离开世界时断开
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(ShareMod::autoJoin));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            if (session != null) session.close();
            session = null;
            reconnectAttempt = 0;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                literal("ls")
                        .executes(c -> { openGuiRequested = true; return 1; })
                        .then(literal("g").executes(c -> { openGuiRequested = true; return 1; }))
                        .then(literal("server")
                                .then(argument("address", StringArgumentType.greedyString()).executes(c -> setServer(StringArgumentType.getString(c, "address")))))
                        .then(literal("join")
                                .executes(c -> {
                                    if (!config.hasServer() || !config.hasRoom()) {
                                        say("&c先设置地址和房间 &f/ls server <地址:端口> &7再 &f/ls join <房间> <密钥>");
                                        return 0;
                                    }
                                    connect();
                                    return 1;
                                })
                                .then(argument("room", StringArgumentType.word())
                                        .then(argument("key", StringArgumentType.word()).executes(c -> {
                                            if (!config.hasServer()) {
                                                say("&c先设置地址 &f/ls server <地址:端口>");
                                                return 0;
                                            }
                                            switchTo(StringArgumentType.getString(c, "room"), StringArgumentType.getString(c, "key"));
                                            return 1;
                                        }))))
                        .then(literal("leave").executes(c -> { guiLeave(); return 1; }))
                        .then(literal("on").executes(c -> withSession(s -> s.setSharing(true))))
                        .then(literal("off").executes(c -> withSession(s -> s.setSharing(false))))
                        .then(literal("push").executes(c -> withSession(ShareSession::push)))
                        .then(literal("unshare").executes(c -> withSession(s -> later(s::requestUnshare))))
                        .then(literal("m").executes(c -> { ShareScreen.selectTab(2); openGuiRequested = true; return 1; }))
                        .then(literal("v").executes(c -> withSession(ShareSession::toggleVerify)))
                        .then(literal("c").then(literal("clear").executes(c -> withSession(ShareSession::clearAllowed))))
                        .then(literal("cm").executes(c -> withSession(x -> x.setChestMode(!x.isChestMode()))))
                        .then(literal("f")
                                .executes(c -> withSession(s -> { s.tracker().clearHighlight(); s.msg("&7已清除高亮"); }))
                                .then(argument("item", StringArgumentType.greedyString()).executes(c -> withSession(s -> s.findChests(StringArgumentType.getString(c, "item"))))))
                        .then(literal("t").executes(c -> withSession(ShareSession::showMyTasks)))
                        .then(literal("take")
                                .executes(c -> withSession(x -> x.setAutoTake(!x.isAutoTake())))
                                .then(literal("on").executes(c -> withSession(x -> x.setAutoTake(true))))
                                .then(literal("off").executes(c -> withSession(x -> x.setAutoTake(false)))))
                        .then(literal("layer").executes(c -> withSession(x -> x.setLayerSync(!x.isLayerSync()))))
                        .then(literal("pushmode")
                                .then(literal("on").executes(c -> withSession(x -> x.setPushMode(true))))
                                .then(literal("off").executes(c -> withSession(x -> x.setPushMode(false)))))
                        .then(literal("admin")
                                .then(literal("add").then(argument("player", StringArgumentType.word()).suggests((c, b) -> suggestMembers(b, false))
                                        .executes(c -> withSession(s -> s.setAdmin(StringArgumentType.getString(c, "player"), true)))))
                                .then(literal("remove").then(argument("player", StringArgumentType.word()).suggests((c, b) -> suggestMembers(b, true))
                                        .executes(c -> withSession(s -> s.setAdmin(StringArgumentType.getString(c, "player"), false))))))
                        .then(literal("update")
                                .executes(c -> { checkUpdate(true); return 1; })
                                .then(literal("on").executes(c -> { config.autoUpdate = true; config.save(); say("&a自动更新开"); return 1; }))
                                .then(literal("off").executes(c -> { config.autoUpdate = false; config.save(); say("&7自动更新关"); return 1; })))
                        .then(literal("status").executes(c -> withSession(s -> {
                            StringBuilder sb = new StringBuilder("&7共享 &f").append(s.isSharing() ? "开" : "关")
                                    .append(" &7· &f").append(s.isAdmin() ? "管理员" : "成员")
                                    .append(s.isAdmin() ? " &7· 推送 &f" + (s.isPushMode() ? "开" : "关") : "")
                                    .append(" &7· 投影 &f").append(s.currentPlacementName()).append(" &7· 成员");
                            s.members().forEach(m -> sb.append(" &f").append(m.name()).append(m.admin() ? "&7(管)" : ""));
                            s.msg(sb.toString());
                        })))));
    }

    /** /ls admin add|remove 的 Tab 补全: 房间里的成员名 (add 补全非管理员，remove 补全管理员)。 */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestMembers(
            com.mojang.brigadier.suggestion.SuggestionsBuilder b, boolean admins) {
        if (session == null) return b.buildFuture();
        return net.minecraft.commands.SharedSuggestionProvider.suggest(session.members().stream()
                .filter(x -> x.admin() == admins).map(ShareSession.Member::name).toList(), b);
    }

    private static void connect() {
        if (session != null) session.close();
        session = new ShareSession(config);
    }

    private static boolean updateChecked;

    /** 手动或自动检查更新；自动检查每次启动游戏只做一次，已是最新时不提示。 */
    static void checkUpdate(boolean manual) {
        if (!config.hasServer()) { if (manual) say("&c先设置服务器地址"); return; }
        if (Updater.busy()) return;
        Updater.check(config, r -> {
            switch (r.state()) {
                case 1 -> say("&a已下载新版本 &f" + r.version() + (r.notes().isEmpty() ? "" : " &7" + r.notes()) + "&7，重启游戏后生效");
                case 2 -> { if (manual) say("&c更新失败 &7" + r.error()); }
                default -> { if (manual) say("&7已是最新版本 &f" + Updater.currentVersion()); }
            }
        });
    }

    private static void autoUpdate() {
        if (updateChecked || !config.autoUpdate || !Updater.enabled()) return;
        updateChecked = true;
        checkUpdate(false);
    }

    private static void autoJoin() {
        autoUpdate();
        if (session == null && config.autoJoin && config.hasServer() && config.hasRoom()) {
            say("&7自动加入 &f" + config.room);
            connect();
        }
    }

    private static void say(String text) {
        Chat.say(text);
    }

    /** 首次连接前由玩家输入服务器地址 (host:port)，保存在本机配置里；模组本身不带任何服务器信息。 */
    private static int setServer(String address) {
        int i = address.lastIndexOf(':');
        try {
            if (i <= 0) throw new NumberFormatException();
            int port = Integer.parseInt(address.substring(i + 1));
            if (port < 1 || port > 65535) throw new NumberFormatException();
            config.host = address.substring(0, i);
            config.port = port;
        } catch (NumberFormatException e) {
            say("&c地址格式: 地址:端口");
            return 0;
        }
        config.save();
        say("&a已保存地址 &7按 &fL &7打开界面加入房间");
        return 1;
    }

    private static int withSession(java.util.function.Consumer<ShareSession> action) {
        if (session == null) {
            say("&c还没有加入房间 &7按 &fL &7打开界面");
            return 0;
        }
        action.accept(session);
        return 1;
    }

    private static int reconnectAttempt;
    private static final java.util.concurrent.ScheduledExecutorService RETRY =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "litematica-share-reconnect");
                t.setDaemon(true);
                return t;
            });

    static void sessionJoined(ShareSession s) {
        if (session == s) reconnectAttempt = 0;
    }

    /** 连接断开: 不是主动离开、也不是版本/密钥问题时，按 2/4/8/16/30 秒的间隔自动重连，直到离开游戏世界。 */
    static void sessionClosed(ShareSession s, boolean retry, String reason) {
        if (session != s) return;
        session = null;
        if (!retry || !config.autoJoin || !config.hasServer() || !config.hasRoom()) {
            say("&7已断开" + (reason == null ? "" : " &c" + reason));
            return;
        }
        if (reconnectAttempt == 0) say("&7连接断开，自动重连中…");
        int delay = Math.min(30, 2 << Math.min(reconnectAttempt++, 4));
        RETRY.schedule(() -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (session == null && config.autoJoin && mc.player != null && mc.level != null) connect();
        }), delay, java.util.concurrent.TimeUnit.SECONDS);
    }
}
