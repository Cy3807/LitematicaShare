package share.mod;

/** 测试用: 暴露包内可见的共享仓库状态和界面。 */
public final class ChestAccess {
    public static int size() {
        ShareSession s = ShareMod.session();
        return s == null ? -1 : s.chests().size();
    }

    public static boolean highlighting() {
        ShareSession s = ShareMod.session();
        return s != null && s.tracker().hasHighlight();
    }

    public static void setFirstVerification(boolean on) { ShareMod.session().setManualFirstVerification(on); }

    public static boolean firstVerification() { return ShareMod.session().isFirstVerification(); }

    public static int highlightCount() {
        ShareSession s = ShareMod.session();
        return s == null ? -1 : s.tracker().highlightCount();
    }

    public static void openGui(int tab) {
        ShareScreen.selectTab(tab);
        net.minecraft.client.Minecraft.getInstance().setScreen(new ShareScreen());
    }

    public static boolean answerConfirm(boolean yes) {
        return Confirm.answer(yes);
    }

    public static boolean verifyOn() {
        ShareSession s = ShareMod.session();
        return s != null && s.isVerifyOn();
    }

    public static long verifyExtra() {
        ShareSession s = ShareMod.session();
        return s == null ? -1 : s.verifyModel().totals()[2];
    }

    public static int rows() {
        ShareSession s = ShareMod.session();
        return s == null ? -1 : s.materialRows().size();
    }

    public static boolean live() {
        ShareSession s = ShareMod.session();
        return s != null && s.isLiveMaterials();
    }

    public static java.util.List<String> regionNames() {
        ShareSession s = ShareMod.session();
        return s == null ? java.util.List.of() : s.regionList().stream().map(java.util.Map.Entry::getKey).toList();
    }

    public static boolean regionEnabled(String name) {
        ShareSession s = ShareMod.session();
        return s != null && s.regionList().stream().anyMatch(e -> e.getKey().equals(name) && e.getValue());
    }

    public static String layerMode() {
        return fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().getLayerMode().name();
    }

    public static void setLayerAll() {
        fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().setLayerMode(fi.dy.masa.malilib.util.LayerMode.ALL, false);
    }

    public static int overlayCount() {
        ShareSession s = ShareMod.session();
        return s == null ? -2 : s.overlayCount();
    }

    public static void selectVerify(int type) {
        ShareSession s = ShareMod.session();
        if (s != null) s.verifyModel().select(type, "");
    }

    public static long verifyCorrect() {
        ShareSession s = ShareMod.session();
        return s == null ? -1 : s.verifyModel().correct();
    }

    public static int verifyChunks() {
        ShareSession s = ShareMod.session();
        return s == null ? -1 : s.verifyModel().observedChunks();
    }

    public static void refreshVerify() {
        ShareSession s = ShareMod.session();
        if (s != null) s.refreshVerify();
    }

    public static void layerSyncOn() {
        ShareSession s = ShareMod.session();
        if (s != null) s.setLayerSync(true);
    }

    public static void cycleLayerMode() {
        ShareSession s = ShareMod.session();
        if (s != null) s.cycleLayerMode();
    }

    public static int taskCount() {
        ShareSession s = ShareMod.session();
        return s == null ? -1 : s.tasks().size();
    }

    public static String firstTaskId() {
        ShareSession s = ShareMod.session();
        return s == null || s.tasks().isEmpty() ? "" : s.tasks().get(0).id();
    }

    public static void setTaskStatus(String id, int status) {
        ShareSession s = ShareMod.session();
        if (s != null) s.setTaskStatus(id, status);
    }

    public static boolean taskMode() {
        ShareSession s = ShareMod.session();
        return s != null && s.isTaskMode();
    }

    public static int filterSize() {
        ShareSession s = ShareMod.session();
        if (s == null) return -2;
        return s.effectiveFilter() == null ? -1 : s.effectiveFilter().size();
    }

    public static boolean regionRendered(String name) {
        ShareSession s = ShareMod.session();
        if (s == null) return false;
        var p = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                .filter(x -> x.getName().startsWith("[LS]")).findFirst().orElse(null);
        return p != null && p.getRelativeSubRegionPlacement(name) != null && p.getRelativeSubRegionPlacement(name).isEnabled();
    }

    public static void toggleFilterRegion(String name) {
        ShareSession s = ShareMod.session();
        if (s != null) s.toggleFilterRegion(name);
    }

    public static void filterAll() {
        ShareSession s = ShareMod.session();
        if (s != null) s.setFilterAll();
    }

    public static void filterMine() {
        ShareSession s = ShareMod.session();
        if (s != null) s.setFilterMine();
    }

    public static void openRegionPicker() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.setScreen(new RegionPickerScreen(mc.screen));
    }

    /** Litematica 管理器里「要渲染的区域」缓存的总数 (touchedVolumesInChunk 里各区块的部分之和)。 */
    public static int renderingParts() {
        try {
            var mgr = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
            var f = mgr.getClass().getDeclaredField("touchedVolumesInChunk");
            f.setAccessible(true);
            int n = 0;
            for (Object list : ((java.util.Map<?, ?>) f.get(mgr)).values()) {
                for (Object part : (java.util.List<?>) list) {
                    var bf = part.getClass().getDeclaredFields();
                    n++;
                }
            }
            return n;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    public static long rowTotal(String id) {
        ShareSession s = ShareMod.session();
        if (s == null) return -1;
        return s.materialRows().stream().filter(r -> r.item().equals(id)).mapToLong(MaterialReport.Row::total).findFirst().orElse(-1);
    }

    public static long rowMissing(String id) {
        ShareSession s = ShareMod.session();
        if (s == null) return -1;
        return s.materialRows().stream().filter(r -> r.item().equals(id)).mapToLong(MaterialReport.Row::missing).findFirst().orElse(-1);
    }

    public static long available(String id) {
        ShareSession s = ShareMod.session();
        if (s == null || s.materialRows().stream().noneMatch(r -> r.item().equals(id))) return -1;
        return s.availableOf(id);
    }

    public static String[] savedRooms() {
        return ShareMod.config().rooms.stream().map(r -> r.name).toArray(String[]::new);
    }

    public static void switchRoom(String name, String key) {
        ShareMod.switchTo(name, key);
    }

    public static String roomName() {
        ShareSession s = ShareMod.session();
        return s == null ? "" : s.roomName();
    }

    public static void locateFirstTask() {
        ShareSession s = ShareMod.session();
        if (s != null && !s.tasks().isEmpty()) s.locate(s.tasks().get(0));
    }

    public static boolean locating() {
        ShareSession s = ShareMod.session();
        return s != null && !s.tasks().isEmpty() && s.isLocating(s.tasks().get(0).id());
    }

    public static String filterLabel() {
        ShareSession s = ShareMod.session();
        return s == null ? "" : s.filterLabel();
    }

    public static void cancelLocate() {
        ShareSession s = ShareMod.session();
        if (s != null) s.cancelLocate(false);
    }

    /** 向转发服务器要房间列表 (不加入房间)，返回「房间名:在线人数」。 */
    public static java.util.List<String> fetchRooms(String host, int port) throws Exception {
        return RoomFetcher.fetch(host, port).get(8, java.util.concurrent.TimeUnit.SECONDS).stream().map(r -> r.name() + ":" + r.online()).toList();
    }

    public static void openRooms() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.setScreen(new RoomsScreen(mc.screen));
    }

    public static void setSharing(boolean on) {
        ShareSession s = ShareMod.session();
        if (s != null) s.setSharing(on);
    }

    public static int placementCount() {
        return fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().size();
    }

    public static void setProjection(boolean on) { ShareMod.session().setProjectionOn(on); }

    /** Litematica 的「渲染原理图」总开关当前值。 */
    public static boolean schematicRendering() {
        return fi.dy.masa.litematica.config.Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue();
    }

    public static int materialRows() { return ShareMod.session().materialRows().size(); }

    private ChestAccess() { }
}
