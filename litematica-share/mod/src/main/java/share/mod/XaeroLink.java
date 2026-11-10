package share.mod;

import java.lang.reflect.Method;

/**
 * 和 Xaero 的小地图 (Xaero's Minimap) 对接: 给任务定位的目标点加一个临时路径点 (目的地类型，到达后 Xaero 自己也会清掉)。
 * 全部用反射，不依赖 Xaero 的 jar；没装 Xaero 或接口变了就静默跳过 (只记一次日志)。
 */
final class XaeroLink {
    /** 加进去的路径点，用来之后删除。 */
    record Handle(Object set, Object waypoint) { }

    private static boolean warned;

    /** 在当前世界的当前路径点集里加一个临时目的地路径点；失败返回 null。 */
    static Handle add(int x, int y, int z, String name, String initials) {
        try {
            Class<?> modules = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Object module = modules.getField("MINIMAP").get(null);
            Object session = module.getClass().getMethod("getCurrentSession").invoke(module);
            if (session == null) return null;
            Object world = session.getClass().getMethod("getWorldManager").invoke(session);
            Object current = world.getClass().getMethod("getCurrentWorld").invoke(world);
            if (current == null) return null;
            Object set = current.getClass().getMethod("getCurrentWaypointSet").invoke(current);
            if (set == null) return null;
            Class<?> wpClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Class<?> colorClass = Class.forName("xaero.hud.minimap.waypoint.WaypointColor");
            Class<?> purposeClass = Class.forName("xaero.hud.minimap.waypoint.WaypointPurpose");
            Object color = colorClass.getField("GREEN").get(null);
            Object purpose = purposeClass.getField("DESTINATION").get(null);
            Object wp = wpClass.getConstructor(int.class, int.class, int.class, String.class, String.class, colorClass, purposeClass)
                    .newInstance(x, y, z, name, initials, color, purpose);
            invokeIfPresent(wp, "setTemporary", boolean.class, true);
            invokeIfPresent(wp, "setOneoffDestination", boolean.class, true); // 到达后自动移除
            set.getClass().getMethod("add", wpClass).invoke(set, wp);
            return new Handle(set, wp);
        } catch (Throwable t) { // 没装 Xaero (ClassNotFound) 或版本不同
            if (!warned) {
                warned = true;
                ShareMod.LOGGER.info("Xaero waypoint not available: {}", t.toString());
            }
            return null;
        }
    }

    static void remove(Handle h) {
        if (h == null) return;
        try {
            Class<?> wpClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Method m = h.set().getClass().getMethod("remove", wpClass);
            m.invoke(h.set(), h.waypoint());
        } catch (Throwable ignored) { }
    }

    private static void invokeIfPresent(Object o, String name, Class<?> type, Object arg) {
        try {
            o.getClass().getMethod(name, type).invoke(o, arg);
        } catch (ReflectiveOperationException ignored) { }
    }

    private XaeroLink() { }
}
