package share.test;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import share.protocol.LitematicaShare;
import share.protocol.Packet;

import java.io.*;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 真实客户端测试: 假管理员(本测试里的 socket)先建房并推送投影，游戏客户端作为成员用 /lshare 命令加入，
 * 检查成员端是否自动生成并更新「[LS]」投影。需要本机转发服务监听 127.0.0.1:20359。
 */
public class ShareClientTest implements FabricClientGameTest {
    static final String SERVER = "127.0.0.1:20359", ROOM = "gametest", KEY = "k";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        byte[] file;
        try (InputStream in = getClass().getResourceAsStream("/dbgtest.litematic")) {
            file = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String sha = sha256(file);

        try (Socket admin = new Socket("127.0.0.1", 20359)) {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(admin.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(admin.getInputStream()));
            Packet.of(Packet.HELLO).str(LitematicaShare.CHANNEL).i32(LitematicaShare.VERSION).build().write(out);
            Packet.read(in);
            Packet.of(LitematicaShare.JOIN).str(ROOM).str(KEY).str("admin-uuid").str("fakeadmin").str("").build().write(out);
            Packet.of(LitematicaShare.SHARE).bool(true).build().write(out);
            Packet.of(LitematicaShare.FILE).str("dbgtest.litematic").str(sha).bytes(file).build().write(out);
            Packet.of(LitematicaShare.PLACEMENT).str(placement(sha, 10, 70, 10, "NONE")).build().write(out);

            try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
                sp.getServer().runCommand("gamemode creative @p"); // 创造模式: 测试里反复 tp 不会摔死
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls server " + SERVER));
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls join " + ROOM + " " + KEY));

                ctx.waitFor(c -> shared().isPresent(), 600);
                SchematicPlacement p = shared().get();
                System.out.println("[TEST] member got placement " + p.getName() + " origin=" + p.getOrigin());
                check(p.getOrigin().getX() == 10 && p.getOrigin().getY() == 70 && p.getOrigin().getZ() == 10, "origin");

                // 材料统计: 玩家附近摆几个方块(模拟别人摆的)，客户端扫描后上报，房间汇总应正确
                sp.getServer().runCommand("tp @p 12 100 12");
                sp.getClientWorld().waitForChunksRender();
                sp.getServer().runCommand("fill 10 70 10 13 71 13 air");
                for (String c2 : new String[]{"setblock 11 70 10 stone", "setblock 12 70 10 stone", "setblock 10 70 11 stone",
                        "setblock 10 71 10 oak_planks", "setblock 13 70 13 dirt"}) { // 最后一个是原理图不需要的方块
                    sp.getServer().runCommand(c2);
                }
                admin.setSoTimeout(3000);
                long stoneNeed = -1, stonePlaced = -1, planksNeed = -1, planksPlaced = -1;
                for (int i = 0; i < 20 && stonePlaced != 3; i++) {
                    ctx.waitTicks(20);
                    Packet.of(LitematicaShare.MATERIALS_REQUEST).build().write(out);
                    Packet mp;
                    do { mp = Packet.read(in); } while (mp.type != LitematicaShare.MATERIALS);
                    DataInputStream mr = mp.reader();
                    Packet.readStr(mr);
                    int chunks = mr.readInt();
                    int cnt = mr.readInt();
                    for (int j = 0; j < cnt; j++) {
                        String blk = Packet.readStr(mr);
                        long need = mr.readLong(), placed = mr.readLong();
                        String item = blk.substring(blk.indexOf('\u0001') + 1); // 键是「子区域 + 分隔符 + 物品」
                        if (item.equals("minecraft:stone")) { stoneNeed = need; stonePlaced = placed; }
                        if (item.equals("minecraft:oak_planks")) { planksNeed = need; planksPlaced = placed; }
                    }
                    System.out.println("[TEST] materials poll " + i + ": chunks=" + chunks + " stone=" + stonePlaced + "/" + stoneNeed + " planks=" + planksPlaced + "/" + planksNeed);
                }
                check(stoneNeed == 10 && stonePlaced == 3 && planksNeed == 11 && planksPlaced == 1, "material stats");
                ctx.runOnClient(c -> {
                    var totals = share.mod.MaterialReportAccess.totals(shared().get());
                    System.out.println("[TEST] local totals: " + totals);
                    check(totals.get("minecraft:stone") == 10 && totals.get("minecraft:oak_planks") == 11, "local totals");
                });
                // 材料清单 (界面用的行): 实时同步默认开，不用手动请求
                ctx.waitFor(c -> share.mod.ChestAccess.rowTotal("minecraft:stone") == 10 && share.mod.ChestAccess.rowMissing("minecraft:stone") == 7
                        && share.mod.ChestAccess.rowMissing("minecraft:oak_planks") == 10, 600);
                System.out.println("[TEST] material stats OK");

                // 实时同步: 另一位成员(假管理员)上报别的区块的进度，本机不用重新验证/请求，材料列表自动变化
                Packet.of(LitematicaShare.PROGRESS).str(sha).str(LitematicaShare.placeKey(10, 70, 10, "NONE", "NONE"))
                        .i32(40).i32(40).i32(1).str("").i32(1).str("minecraft:stone").i32(2).i32(0).build().write(out);
                ctx.waitFor(c -> share.mod.ChestAccess.rowMissing("minecraft:stone") == 9, 400);
                System.out.println("[TEST] live material push OK (stone missing 7 -> 9 without re-request)");

                // 「已有数量」实时包含别人共享的背包
                Packet.of(LitematicaShare.INVENTORY).i32(1).str("minecraft:stone").i32(25).build().write(out);
                ctx.waitFor(c -> share.mod.ChestAccess.available("minecraft:stone") == 25, 400);
                System.out.println("[TEST] available includes other player's shared inventory");

                // 子任务 (简单模式下也能下发和看到): 管理员(假管理员)建一个对应子区域的任务分配给本机玩家 -> 本机收到；本机标为完成 -> 转发给别人
                String myUuid = ctx.computeOnClient(c -> c.getUser().getProfileId().toString());
                new share.protocol.TaskRecord("", "盖东楼", "main", java.util.List.of(myUuid), 0, "", "")
                        .write(Packet.of(LitematicaShare.TASK_SET)).build().write(out);
                ctx.waitFor(c -> share.mod.ChestAccess.taskCount() == 1, 400);
                String taskId = share.mod.ChestAccess.firstTaskId();
                check(!share.mod.ChestAccess.taskMode(), "still simple mode");
                ctx.runOnClient(c -> share.mod.ChestAccess.openGui(4));
                ctx.waitTicks(10);
                ctx.takeScreenshot("share-gui-tasks");
                ctx.runOnClient(c -> share.mod.ChestAccess.setTaskStatus(taskId, 2));
                ctx.waitTicks(20);
                admin.setSoTimeout(8000);
                boolean done = false;
                while (!done) {
                    Packet tp = Packet.read(in);
                    if (tp.type != LitematicaShare.TASKS) continue;
                    DataInputStream tr = tp.reader();
                    int tn = tr.readInt();
                    for (int ti = 0; ti < tn; ti++) if (share.protocol.TaskRecord.read(tr).status() == 2) done = true;
                }
                System.out.println("[TEST] task assigned to me arrived; my status update reached the room");
                // 任务模式 (管理员开启，整个房间的模式): 成员默认只看自己任务的子区域，并且默认只渲染这个子区域
                Packet.of(LitematicaShare.MODE_SET).bool(true).build().write(out);
                ctx.waitFor(c -> share.mod.ChestAccess.taskMode(), 200);
                check(share.mod.ChestAccess.filterSize() == 1, "member defaults to own task region: " + share.mod.ChestAccess.filterSize());
                System.out.println("[TEST] member's materials/verify default to the assigned sub-region");
                // 任务定位: 区域选择直接变成这个任务的子区域 (没装 Xaero 时只改区域选择)；再点取消
                ctx.runOnClient(c -> share.mod.ChestAccess.toggleFilterRegion("zzz"));
                ctx.runOnClient(c -> share.mod.ChestAccess.locateFirstTask());
                check(share.mod.ChestAccess.locating(), "locating");
                check(share.mod.ChestAccess.filterLabel().equals("main"), "filter became the task region: " + share.mod.ChestAccess.filterLabel());
                ctx.runOnClient(c -> share.mod.ChestAccess.cancelLocate());
                check(!share.mod.ChestAccess.locating(), "locate cancelled");
                ctx.runOnClient(c -> share.mod.ChestAccess.filterMine()); // 回到默认(任务区域)
                System.out.println("[TEST] task locate switches the region filter and can be cancelled");
                ctx.waitTicks(30);
                check(share.mod.ChestAccess.regionRendered("main"), "assigned region is rendered");
                // 区域选择 (导航栏): 选了别的子区域，main 就不渲染；选回全部/任务区域又渲染
                int partsBefore = share.mod.ChestAccess.renderingParts();
                ctx.runOnClient(c -> share.mod.ChestAccess.toggleFilterRegion("zzz"));
                ctx.waitFor(c -> !share.mod.ChestAccess.regionRendered("main"), 400);
                ctx.waitTicks(20);
                System.out.println("[TEST] rendering parts before=" + partsBefore + " after=" + share.mod.ChestAccess.renderingParts());
                check(share.mod.ChestAccess.renderingParts() < partsBefore, "manager's rendering volumes shrink when the region is hidden");
                ctx.runOnClient(c -> c.setScreen(null));
                sp.getServer().runCommand("fill 8 69 8 15 69 15 stone"); // 铺一块地面方便看
                sp.getServer().runCommand("tp @p 11.5 78 11.5 0 90"); // 站到投影正上方往下看
                sp.getClientWorld().waitForChunksRender();
                ctx.waitTicks(60);
                ctx.takeScreenshot("share-region-hidden");
                ctx.runOnClient(c -> share.mod.ChestAccess.openGui(2));
                ctx.runOnClient(c -> share.mod.ChestAccess.openRegionPicker());
                ctx.waitTicks(10);
                ctx.takeScreenshot("share-region-picker");
                ctx.runOnClient(c -> c.setScreen(null));
                ctx.runOnClient(c -> share.mod.ChestAccess.filterAll());
                ctx.waitFor(c -> share.mod.ChestAccess.regionRendered("main"), 400);
                sp.getClientWorld().waitForChunksRender();
                ctx.waitTicks(80);
                ctx.takeScreenshot("share-region-shown");
                sp.getServer().runCommand("tp @p 12 100 12");
                ctx.runOnClient(c -> share.mod.ChestAccess.filterMine());
                ctx.waitFor(c -> share.mod.ChestAccess.regionRendered("main"), 400);
                System.out.println("[TEST] region selection drives which sub-regions are rendered");
                ctx.runOnClient(c -> c.setScreen(null));

                // 团队验证: 管理员开启后，本机扫描上报逐方块结果(含多余的泥土)，别人上报的区块也汇总进来
                Packet.of(LitematicaShare.VERIFY_SET).bool(true).build().write(out);
                ctx.waitFor(c -> share.mod.ChestAccess.verifyOn(), 200);
                ctx.waitTicks(80); // 先让游戏 tick 扫描并上报，再阻塞读 socket (读的时候游戏不 tick)
                admin.setSoTimeout(15000);
                int[] vt = null;
                for (int guard = 0; guard < 400 && vt == null; guard++) {
                    ctx.waitTicks(2);
                    Packet vp = Packet.read(in);
                    if (vp.type != LitematicaShare.VERIFY_CHUNK) continue;
                    DataInputStream vr = vp.reader();
                    Packet.readStr(vr); Packet.readStr(vr); vr.readInt(); vr.readInt();
                    int nreg = vr.readInt();
                    int[] sum = new int[5];
                    for (int ri = 0; ri < nreg; ri++) {
                        Packet.readStr(vr);
                        for (int k = 0; k < 5; k++) sum[k] += vr.readInt();
                    }
                    vt = sum;
                }
                check(vt != null && vt[0] + vt[1] + vt[2] + vt[3] + vt[4] > 0, "client reported verify chunk");
                System.out.println("[TEST] team verify: client reported chunk correct=" + vt[0] + " missing=" + vt[1] + " wrong=" + vt[2] + " extra=" + vt[3] + " state=" + vt[4]);
                Packet.of(LitematicaShare.VERIFY_REPORT).str(sha).str(LitematicaShare.placeKey(10, 70, 10, "NONE", "NONE"))
                        .i32(40).i32(40).i32(1).str("main").i32(0).i32(0).i32(0).i32(5).i32(0)
                        .i32(1).str("main").i32(2).str("minecraft:air").str("minecraft:dirt").i32(5).i32(0).build().write(out);
                ctx.waitFor(c -> share.mod.ChestAccess.verifyExtra() >= 5, 400);
                System.out.println("[TEST] team verify: other member's chunk merged (extra >= 5)");
                ctx.runOnClient(c -> share.mod.ChestAccess.selectVerify(0));
                ctx.waitFor(c -> share.mod.ChestAccess.overlayCount() > 0, 300);
                System.out.println("[TEST] team verify: positions handed to Litematica's overlay renderer (" + share.mod.ChestAccess.overlayCount() + ")");
                sp.getServer().runCommand("fill 8 69 8 15 69 15 stone"); // 铺一块地面方便看
                sp.getServer().runCommand("tp @p 11.5 78 11.5 0 90"); // 站到投影旁边，看 Litematica 渲染器画的缺失方块高亮
                sp.getClientWorld().waitForChunksRender();
                ctx.waitTicks(40);
                System.out.println("[TEST] overlay screenshot: overlayCount=" + share.mod.ChestAccess.overlayCount());
                ctx.takeScreenshot("share-verify-overlay");
                sp.getServer().runCommand("tp @p 12 100 12");
                check(share.mod.ChestAccess.verifyCorrect() > 0, "correct blocks counted");
                // 手动刷新: 本机清空后重新扫描并从服务器拉取所有区块 (别人的区块也回来)
                ctx.runOnClient(c -> share.mod.ChestAccess.refreshVerify());
                ctx.waitFor(c -> share.mod.ChestAccess.verifyChunks() >= 2 && share.mod.ChestAccess.verifyExtra() >= 5, 600);
                System.out.println("[TEST] team verify: manual refresh restored own + other chunks");
                ctx.runOnClient(c -> share.mod.ChestAccess.selectVerify(-1));
                ctx.waitFor(c -> share.mod.ChestAccess.overlayCount() == -1, 300);

                // 管理员挪动并旋转，成员应跟随
                Packet.of(LitematicaShare.PLACEMENT).str(placement(sha, 20, 71, -5, "CLOCKWISE_90")).build().write(out);
                ctx.waitFor(c -> shared().map(x -> x.getOrigin().getX() == 20).orElse(false), 600);
                p = shared().get();
                System.out.println("[TEST] member follows: origin=" + p.getOrigin() + " rot=" + p.getRotation());
                check(p.getOrigin().getZ() == -5 && p.getRotation().name().equals("CLOCKWISE_90"), "move+rotate");
                check(p.isLocked(), "member copy is locked");

                // 成员绕过锁强行改动，应被自动还原
                ctx.runOnClient(c -> {
                    SchematicPlacement x = shared().get();
                    x.toggleLocked();
                    x.setOrigin(new net.minecraft.core.BlockPos(99, 99, 99), s2 -> { });
                });
                ctx.waitFor(c -> shared().map(x -> x.getOrigin().getX() == 20).orElse(false), 600);
                System.out.println("[TEST] member tamper reverted");

                check(share.mod.ChestAccess.live(), "live sync on by default");

                // 投影范围: 管理员关掉某个子区域，成员那边同步变成不参与
                String region = share.mod.ChestAccess.regionNames().get(0);
                Packet.of(LitematicaShare.PLACEMENT).str(placementR(sha, 20, 71, -5, "CLOCKWISE_90", "{\"" + region + "\":false}")).build().write(out);
                ctx.waitFor(c -> !share.mod.ChestAccess.regionEnabled(region), 400);
                Packet.of(LitematicaShare.PLACEMENT).str(placementR(sha, 20, 71, -5, "CLOCKWISE_90", "{\"" + region + "\":true}")).build().write(out);
                ctx.waitFor(c -> share.mod.ChestAccess.regionEnabled(region), 400);
                System.out.println("[TEST] sub-region enabled state follows admin");

                // 渲染层同步: 管理员设为只显示单层，成员跟随；成员自己改回去会被还原
                Packet.of(LitematicaShare.LAYER_SET).bool(true).str("{\"mode\":\"SINGLE_LAYER\",\"axis\":\"Y\",\"single\":70,\"above\":0,\"below\":0,\"min\":0,\"max\":0}").build().write(out);
                ctx.waitFor(c -> share.mod.ChestAccess.layerMode().equals("SINGLE_LAYER"), 400);
                ctx.runOnClient(c -> share.mod.ChestAccess.setLayerAll());
                ctx.waitFor(c -> share.mod.ChestAccess.layerMode().equals("SINGLE_LAYER"), 400);
                Packet.of(LitematicaShare.LAYER_SET).bool(false).str("{}").build().write(out);
                ctx.waitTicks(10);
                ctx.runOnClient(c -> share.mod.ChestAccess.setLayerAll());
                System.out.println("[TEST] render layer follows admin and member edits are reverted");

                ctx.takeScreenshot("share-member");
            }

            // 重新进入世界: 应按已保存的配置自动重新加入房间，无需再输入命令
            ctx.runOnClient(c -> DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()
                    .stream().filter(x -> x.getName().startsWith("[LS]")).toList()
                    .forEach(x -> DataManager.getSchematicPlacementManager().removeSchematicPlacement(x)));
            try (TestSingleplayerContext sp2 = ctx.worldBuilder().create()) {
                sp2.getServer().runCommand("gamemode creative @p");
                Packet.of(LitematicaShare.PLACEMENT).str(placement(sha, 30, 72, 30, "NONE")).build().write(out);
                ctx.waitFor(c -> shared().map(x -> x.getOrigin().getX() == 30).orElse(false), 600);
                System.out.println("[TEST] auto-rejoin OK");

                // 连接被服务器断开(这里用同一玩家重复登录来模拟)后，应自动重连并继续同步
                String uuid0 = ctx.computeOnClient(c -> c.getUser().getProfileId().toString());
                try (Socket dup = new Socket("127.0.0.1", 20359)) {
                    DataOutputStream dout = new DataOutputStream(new BufferedOutputStream(dup.getOutputStream()));
                    Packet.of(Packet.HELLO).str(LitematicaShare.CHANNEL).i32(LitematicaShare.VERSION).build().write(dout);
                    Packet.of(LitematicaShare.JOIN).str(ROOM).str(KEY).str(uuid0).str("dup").str("").build().write(dout);
                    ctx.waitTicks(20);
                }
                ctx.waitTicks(120); // 重连最短 2 秒
                Packet.of(LitematicaShare.PLACEMENT).str(placement(sha, 45, 72, 45, "NONE")).build().write(out);
                ctx.waitFor(c -> shared().map(x -> x.getOrigin().getX() == 45).orElse(false), 600);
                System.out.println("[TEST] auto-reconnect OK");

                // 客户端被设为管理员后，仍应跟随另一位管理员的投影，且不回推
                String uuid = ctx.computeOnClient(c -> c.getUser().getProfileId().toString());
                String name = ctx.computeOnClient(c -> c.getUser().getName());
                Packet.of(LitematicaShare.SET_ADMIN).str(uuid).bool(true).build().write(out);
                admin.setSoTimeout(5000);
                while (!membersHaveAdmin(Packet.read(in), name)) { /* 等转发服务器确认 */ }
                Packet.of(LitematicaShare.PLACEMENT).str(placement(sha, 40, 73, 40, "NONE")).build().write(out);
                ctx.waitFor(c -> shared().map(x -> x.getOrigin().getX() == 40).orElse(false), 600);
                System.out.println("[TEST] admin client follows other admin");
                ctx.waitTicks(60);
                admin.setSoTimeout(1500);
                try {
                    while (true) {
                        Packet p = Packet.read(in);
                        check(p.type != LitematicaShare.PLACEMENT && p.type != LitematicaShare.FILE, "no echo from client-admin");
                    }
                } catch (java.net.SocketTimeoutException expected) {
                    System.out.println("[TEST] no echo OK");
                }

                // 被设为管理员的客户端改动共享投影，另一位管理员应收到
                ctx.runOnClient(c -> shared().get().setOrigin(new net.minecraft.core.BlockPos(50, 74, 50), s2 -> { }));
                ctx.waitTicks(30);
                System.out.println("[TEST] after admin edit: origin=" + shared().get().getOrigin() + " locked=" + shared().get().isLocked());
                admin.setSoTimeout(8000);
                String got;
                do {
                    Packet p2 = Packet.read(in);
                    got = p2.type == LitematicaShare.PLACEMENT ? Packet.readStr(p2.reader()) : null;
                } while (got == null);
                check(got.contains("\"x\":50"), "admin edit propagates: " + got);
                System.out.println("[TEST] admin edit propagated");

                // 管理员在界面里直接改渲染层规则 (这里调同一批方法)，开了同步就推给所有人
                ctx.runOnClient(c -> share.mod.ChestAccess.layerSyncOn());
                ctx.runOnClient(c -> share.mod.ChestAccess.setLayerAll());
                ctx.waitTicks(20);
                ctx.runOnClient(c -> share.mod.ChestAccess.cycleLayerMode()); // 全部 -> 单层
                ctx.waitTicks(40); // 先让游戏 tick 把改动推出去，再阻塞读 socket
                admin.setSoTimeout(8000);
                while (true) {
                    Packet lp = Packet.read(in);
                    if (lp.type != LitematicaShare.LAYER_STATE) continue;
                    DataInputStream lr = lp.reader();
                    lr.readBoolean();
                    if (Packet.readStr(lr).contains("SINGLE_LAYER")) break;
                }
                ctx.runOnClient(c -> share.mod.ChestAccess.setLayerAll());
                System.out.println("[TEST] admin edited render layer rules -> pushed to everyone");

                // 推送模式关闭: 本机改动不推送；重新开启后才同步
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls pushmode off"));
                ctx.waitTicks(10);
                ctx.runOnClient(c -> shared().get().setOrigin(new net.minecraft.core.BlockPos(60, 75, 60), s2 -> { }));
                ctx.waitTicks(60);
                admin.setSoTimeout(1500);
                try {
                    while (true) {
                        Packet p3 = Packet.read(in);
                        check(p3.type != LitematicaShare.PLACEMENT, "no push while push mode is off");
                    }
                } catch (java.net.SocketTimeoutException expected) {
                    System.out.println("[TEST] push mode off: nothing pushed");
                }
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls pushmode on"));
                ctx.waitTicks(40);
                admin.setSoTimeout(8000);
                String pushed;
                do {
                    Packet p4 = Packet.read(in);
                    pushed = p4.type == LitematicaShare.PLACEMENT ? Packet.readStr(p4.reader()) : null;
                } while (pushed == null);
                check(pushed.contains("\"x\":60"), "local edit pushed after push mode on: " + pushed);
                System.out.println("[TEST] push mode on: local edit pushed");

                // 在 Litematica 里移除共享投影 (切世界时 Litematica 也会重载放置列表): 不弹框，默认从房间状态恢复；真要取消用 /ls unshare (弹确认)
                ctx.runOnClient(c -> DataManager.getSchematicPlacementManager().removeSchematicPlacement(shared().get()));
                ctx.waitFor(c -> shared().isPresent(), 200);
                check(!share.mod.ChestAccess.answerConfirm(true), "no confirm dialog after removing the shared placement");
                System.out.println("[TEST] removed shared placement is restored silently");
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls unshare"));
                ctx.waitTicks(10);
                ctx.waitFor(c -> share.mod.ChestAccess.answerConfirm(true), 200); // 命令取消房间的共享投影要确认
                admin.setSoTimeout(8000);
                Packet q;
                ctx.waitTicks(30);
                System.out.println("[TEST] after removal: shared present=" + shared().isPresent() + " total=" + DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().size());
                do { q = Packet.read(in); System.out.println("[TEST] fake admin got type " + q.type); } while (q.type != LitematicaShare.CLEARED);
                check(shared().isEmpty(), "no shared placement left");
                System.out.println("[TEST] removal unshared the room");

                // 发布自己的投影: 原投影被打上共享标，并发给所有人
                java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("share-test");
                java.nio.file.Files.write(tmpDir.resolve("dbgtest.litematic"), file);
                ctx.runOnClient(c -> {
                    var sch = fi.dy.masa.litematica.schematic.LitematicaSchematic.createFromFile(tmpDir, "dbgtest.litematic");
                    var mine = SchematicPlacement.createFor(sch, new net.minecraft.core.BlockPos(5, 64, 5), "Unnamed", true, true);
                    DataManager.getSchematicPlacementManager().addSchematicPlacement(mine, false);
                    DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(mine);
                    c.player.connection.sendCommand("ls push");
                });
                ctx.waitFor(c -> shared().map(x -> x.getName().equals("[LS] dbgtest")).orElse(false), 600);
                boolean gotFile = false, gotPlacement = false;
                while (!gotPlacement) {
                    Packet q2 = Packet.read(in);
                    if (q2.type == LitematicaShare.FILE) gotFile = true;
                    if (q2.type == LitematicaShare.PLACEMENT) gotPlacement = true;
                }
                check(gotFile, "published file reached other admin");
                System.out.println("[TEST] publish tagged own placement + synced");

                // 关共享只是暂停: 房间的投影文件保留，开回来不用重新发布/上传，原来的投影被沿用(不会多出副本)
                ctx.runOnClient(c -> share.mod.ChestAccess.setSharing(false));
                ctx.waitFor(c -> shared().isEmpty(), 300);
                ctx.runOnClient(c -> share.mod.ChestAccess.setSharing(true));
                ctx.waitFor(c -> shared().isPresent(), 600);
                ctx.waitTicks(20);
                check(share.mod.ChestAccess.placementCount() == 1, "no duplicate placement after share off/on: " + share.mod.ChestAccess.placementCount());
                System.out.println("[TEST] share off/on keeps the room's projection without re-publishing");

                ctx.waitTicks(40);
                // 共享箱子库存索引: 别人上报 -> 本机收到并能搜索高亮；本机打开真实箱子 -> 上报给别人
                sp2.getServer().runCommand("setblock 30 100 30 chest"); // 真箱子，不然定期清理会把不存在的容器记录删掉
                ctx.waitTicks(10);
                Packet.of(LitematicaShare.CHEST_ALLOW).str("minecraft:overworld").i32(30).i32(100).i32(30).bool(true).build().write(out);
                new share.protocol.ChestRecord("minecraft:overworld", 30, 100, 30, "chest", "", 0,
                        new java.util.LinkedHashMap<>(java.util.Map.of("minecraft:oak_planks", 500)))
                        .write(Packet.of(LitematicaShare.CHEST_REPORT)).build().write(out);
                ctx.waitFor(c -> share.mod.ChestAccess.size() == 1, 400);
                ctx.waitFor(c -> share.mod.ChestAccess.available("minecraft:oak_planks") == 500, 400);
                System.out.println("[TEST] available includes allowed chest contents (oak_planks 500)");
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls f planks"));
                ctx.waitFor(c -> share.mod.ChestAccess.highlighting(), 100);
                System.out.println("[TEST] chest index received + find highlights");
                // 箱子很多时全部命中的箱子都要高亮 (以前只取数量最多的 20 个)
                for (int i = 0; i < 25; i++) {
                    int bx = 40 + 2 * i;
                    sp2.getServer().runCommand("setblock " + bx + " 100 40 chest");
                    Packet.of(LitematicaShare.CHEST_ALLOW).str("minecraft:overworld").i32(bx).i32(100).i32(40).bool(true).build().write(out);
                    new share.protocol.ChestRecord("minecraft:overworld", bx, 100, 40, "chest", "", 0,
                            new java.util.LinkedHashMap<>(java.util.Map.of("minecraft:oak_planks", 10 + i)))
                            .write(Packet.of(LitematicaShare.CHEST_REPORT)).build().write(out);
                }
                ctx.waitFor(c -> share.mod.ChestAccess.size() == 26, 600);
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls f planks"));
                ctx.waitTicks(10);
                check(share.mod.ChestAccess.highlightCount() == 26, "all 26 matching chests highlighted: " + share.mod.ChestAccess.highlightCount());
                System.out.println("[TEST] all matching chests highlighted (26 > 20)");
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls f"));
                ctx.waitTicks(5);
                for (int i = 0; i < 25; i++) { // 清理: 不允许这些箱子，后面的步骤按只有 1 个允许的箱子计数
                    Packet.of(LitematicaShare.CHEST_ALLOW).str("minecraft:overworld").i32(40 + 2 * i).i32(100).i32(40).bool(false).build().write(out);
                    sp2.getServer().runCommand("setblock " + (40 + 2 * i) + " 100 40 air");
                }
                ctx.waitFor(c -> share.mod.ChestAccess.size() == 1, 600);
                ctx.runOnClient(c -> share.mod.ChestAccess.openGui(2));
                ctx.waitTicks(10);
                ctx.takeScreenshot("share-gui-chests");
                ctx.runOnClient(c -> share.mod.ChestAccess.openGui(3));
                ctx.waitTicks(10);
                ctx.takeScreenshot("share-gui-verify");
                ctx.runOnClient(c -> share.mod.ChestAccess.openGui(4));
                ctx.waitTicks(10);
                ctx.takeScreenshot("share-gui-tasks-admin");
                ctx.runOnClient(c -> share.mod.ChestAccess.openGui(2));
                ctx.waitTicks(10);
                ctx.takeScreenshot("share-gui-materials");
                ctx.runOnClient(c -> share.mod.ChestAccess.openGui(1));
                ctx.waitTicks(10);
                ctx.takeScreenshot("share-gui-projection");
                ctx.runOnClient(c -> share.mod.ChestAccess.openGui(0));
                ctx.waitTicks(10);
                ctx.takeScreenshot("share-gui-room");
                ctx.runOnClient(c -> c.setScreen(null));

                // 关闭投影显示 = 只关 Litematica 的渲染开关，材料列表照常；再打开还原
                int rowsBefore = share.mod.ChestAccess.materialRows();
                check(rowsBefore > 0 && share.mod.ChestAccess.schematicRendering(), "before hiding: rows=" + rowsBefore);
                ctx.runOnClient(c -> share.mod.ChestAccess.setProjection(false));
                ctx.waitTicks(40);
                check(!share.mod.ChestAccess.schematicRendering(), "rendering flag off while hidden");
                check(share.mod.ChestAccess.materialRows() == rowsBefore, "materials kept while hidden: " + share.mod.ChestAccess.materialRows());
                ctx.runOnClient(c -> share.mod.ChestAccess.setProjection(true));
                ctx.waitTicks(10);
                check(share.mod.ChestAccess.schematicRendering(), "rendering flag restored");
                System.out.println("[TEST] hiding the projection keeps materials and restores rendering");

                // 手动首次验证模式: 开了扫描不限距离，关了回到自动判断 (房间里已经有验证数据时不是首次)
                boolean autoFirst = share.mod.ChestAccess.firstVerification();
                ctx.runOnClient(c -> share.mod.ChestAccess.setFirstVerification(true));
                check(share.mod.ChestAccess.firstVerification(), "manual first-verification mode on");
                ctx.runOnClient(c -> share.mod.ChestAccess.setFirstVerification(false));
                check(share.mod.ChestAccess.firstVerification() == autoFirst, "manual first-verification mode off restores auto rule");
                System.out.println("[TEST] manual first-verification mode toggles");

                sp2.getServer().runCommand("tp @p 20 100 20");
                sp2.getServer().runCommand("fill 16 99 16 26 99 26 stone");
                sp2.getServer().runCommand("setblock 20 100 22 chest");
                sp2.getServer().runCommand("item replace block 20 100 22 container.0 with iron_ingot 64");
                sp2.getServer().runCommand("item replace block 20 100 22 container.1 with stone 32");
                sp2.getServer().runCommand("tp @p 20.5 100 20.5 0 29"); // 朝南偏下，准星对着箱子正面
                ctx.waitTicks(20);
                // 没被允许前打开箱子不会上传；管理员(本机)用 /ls c 允许准星对着的箱子，所有人收到新的白名单
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls cm"));
                ctx.waitTicks(10);
                var chest1 = new net.minecraft.core.BlockPos(20, 100, 22);
                ctx.runOnClient(c -> c.gameMode.startDestroyBlock(chest1, net.minecraft.core.Direction.NORTH));
                admin.setSoTimeout(8000);
                while (true) {
                    Packet ap = Packet.read(in);
                    if (ap.type == LitematicaShare.CHEST_ALLOWED && ap.reader().readInt() == 2) break;
                }
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls cm"));
                ctx.waitTicks(10);
                System.out.println("[TEST] chest mode allowed the real chest");
                ctx.runOnClient(c -> {
                    var pos = new net.minecraft.core.BlockPos(20, 100, 22);
                    c.gameMode.useItemOn(c.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                            new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false));
                });
                ctx.waitTicks(40);
                admin.setSoTimeout(8000);
                share.protocol.ChestRecord mine;
                while (true) {
                    Packet cp = Packet.read(in);
                    if (cp.type != LitematicaShare.CHEST_UPDATE) continue;
                    mine = share.protocol.ChestRecord.read(cp.reader());
                    break;
                }
                check(mine.x() == 20 && mine.y() == 100 && mine.z() == 22 && mine.kind().equals("chest"), "chest pos/kind: " + mine);
                check(mine.items().getOrDefault("minecraft:iron_ingot", 0) == 64, "chest items: " + mine.items());
                ctx.waitFor(c -> share.mod.ChestAccess.available("minecraft:stone") == 25 + 32, 400);
                System.out.println("[TEST] opened chest's stone added to available (25 + 32)");
                System.out.println("[TEST] opening a real chest reported it");
                ctx.runOnClient(c -> c.setScreen(null));
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls f iron"));
                ctx.waitTicks(10);
                ctx.takeScreenshot("share-chest-highlight");

                // 自动拿取: 高亮(选中)的是铁锭，打开箱子后铁锭格子高亮，并被自动拿进背包；石头不动
                ctx.runOnClient(c -> c.gameMode.useItemOn(c.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(chest1), net.minecraft.core.Direction.UP, chest1, false)));
                ctx.waitTicks(4);
                ctx.takeScreenshot("share-chest-slots");
                ctx.waitFor(c -> c.player.getInventory().countItem(net.minecraft.world.item.Items.IRON_INGOT) == 64, 300);
                ctx.runOnClient(c -> check(c.player.getInventory().countItem(net.minecraft.world.item.Items.STONE) == 0, "stone not taken"));
                ctx.runOnClient(c -> c.setScreen(null));
                System.out.println("[TEST] auto-take moved the highlighted iron out of the chest");

                // 箱子模式: 左键 = 允许(不会破坏箱子)，右键 = 不允许(不会打开箱子)
                sp2.getServer().runCommand("setblock 22 100 22 chest");
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls cm"));
                ctx.waitTicks(10);
                var chest2 = new net.minecraft.core.BlockPos(22, 100, 22);
                ctx.runOnClient(c -> c.gameMode.startDestroyBlock(chest2, net.minecraft.core.Direction.NORTH));
                while (true) {
                    Packet ap = Packet.read(in);
                    if (ap.type == LitematicaShare.CHEST_ALLOWED && ap.reader().readInt() == 3) break;
                }
                ctx.waitTicks(10);
                ctx.runOnClient(c -> check(c.level.getBlockState(chest2).getBlock() instanceof net.minecraft.world.level.block.ChestBlock, "chest not broken in chest mode"));
                ctx.runOnClient(c -> c.gameMode.useItemOn(c.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(chest2), net.minecraft.core.Direction.UP, chest2, false)));
                while (true) {
                    Packet ap = Packet.read(in);
                    if (ap.type == LitematicaShare.CHEST_ALLOWED && ap.reader().readInt() == 2) break;
                }
                ctx.waitTicks(10);
                ctx.runOnClient(c -> check(!(c.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>), "chest not opened in chest mode"));
                ctx.runOnClient(c -> c.player.connection.sendCommand("ls cm"));
                ctx.waitTicks(5);
                System.out.println("[TEST] chest mode: left allows, right disallows, neither breaks/opens");

                // 多个房间: 切换到另一个房间时只加载新房间的投影数据 (当前房间的共享副本被清掉)，再切回来又恢复
                ctx.runOnClient(c -> share.mod.ChestAccess.switchRoom("gametest-b", "k"));
                ctx.waitFor(c -> share.mod.ChestAccess.roomName().equals("gametest-b"), 300);
                ctx.waitTicks(40);
                check(shared().isEmpty(), "old room's shared copy removed after switching");
                check(java.util.Arrays.asList(share.mod.ChestAccess.savedRooms()).containsAll(java.util.List.of("gametest", "gametest-b")), "both rooms saved");
                ctx.runOnClient(c -> share.mod.ChestAccess.switchRoom("gametest", "k"));
                ctx.waitFor(c -> share.mod.ChestAccess.roomName().equals("gametest"), 300);
                ctx.waitFor(c -> shared().isPresent(), 600);
                System.out.println("[TEST] multi-room: switching loads only one room's shared projection at a time");
                // 服务器房间列表: 不加入房间也能看到所有房间
                try {
                    var list = share.mod.ChestAccess.fetchRooms("127.0.0.1", 20359);
                    check(list.stream().anyMatch(x -> x.startsWith("gametest:")) && list.stream().anyMatch(x -> x.startsWith("gametest-b:")), "server room list: " + list);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                System.out.println("[TEST] server room list shows every room");
                ctx.runOnClient(c -> share.mod.ChestAccess.openRooms());
                ctx.waitTicks(40);
                ctx.takeScreenshot("share-rooms");
                ctx.runOnClient(c -> c.setScreen(null));
            }
            System.out.println("[TEST] PASS");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean membersHaveAdmin(Packet p, String name) throws IOException {
        if (p.type != LitematicaShare.MEMBERS) return false;
        DataInputStream r = p.reader();
        int n = r.readInt();
        for (int i = 0; i < n; i++) {
            Packet.readStr(r);
            String nm = Packet.readStr(r);
            if (r.readBoolean() && nm.equals(name)) return true;
        }
        return false;
    }

    private static Optional<SchematicPlacement> shared() {
        return DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                .filter(p -> p.getName().startsWith("[LS]")).findFirst();
    }

    private static String placementR(String sha, int x, int y, int z, String rot, String regionsJson) {
        return "{\"sha\":\"" + sha + "\",\"name\":\"dbgtest\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z
                + ",\"rotation\":\"" + rot + "\",\"mirror\":\"NONE\",\"enabled\":true,\"regions\":" + regionsJson + "}";
    }

    private static String placement(String sha, int x, int y, int z, String rot) {
        return "{\"sha\":\"" + sha + "\",\"name\":\"dbgtest\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z
                + ",\"rotation\":\"" + rot + "\",\"mirror\":\"NONE\",\"enabled\":true}";
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("check failed: " + what);
    }

    private static String sha256(byte[] d) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
