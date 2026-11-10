package share.protocol;

/** 通道 "litematica_share" 的协议常量。客户端模组与转发 jar 共用。 */
public final class LitematicaShare {
    public static final String CHANNEL = "litematica_share";
    public static final int VERSION = 15;

    // client -> server
    public static final byte JOIN = 1;        // room, key, uuid, name, haveSha(本机已缓存的该房间投影文件的 sha，没有就空串；和房间当前文件一致时转发端不再重发文件)
    public static final byte SET_ADMIN = 2;   // uuid, boolean
    public static final byte SHARE = 3;       // boolean 开关共享
    public static final byte PLACEMENT = 4;   // json: 投影放置信息
    public static final byte FILE = 5;        // name, sha256, bytes
    /**
     * 材料统计: 客户端上报已加载区块的 需要/已摆 计数。sha, placeKey, cx, cz, int 本次扫描覆盖的子区域数 + 子区域名*,
     * m, (子区域+分隔符+物品id, 需要, 已摆)*m。转发端只替换这些子区域在该区块的旧数据，其他子区域(别人上报的)保留。
     */
    public static final byte PROGRESS = 7;
    public static final byte MATERIALS_REQUEST = 8; // 请求整个房间汇总的材料统计
    public static final byte PING = 9;       // 心跳: 客户端每 20 秒一次，防止隧道/NAT 因空闲断开，转发端回 PONG
    public static final byte CLEAR = 6;       // 管理员取消房间的共享投影 (共享开关保持不变)
    /** 共享箱子库存索引: 任何成员打开容器后上报一条 ChestRecord (reporter/time 由转发端填)。 */
    public static final byte CHEST_REPORT = 10;
    public static final byte CHEST_REMOVE = 11; // dim, x, y, z: 该容器已不存在
    /** 管理员允许/不允许某个容器上传: dim, x, y, z (大箱子用较小一半的坐标), boolean allow。只有被允许的容器才能 CHEST_REPORT。 */
    public static final byte CHEST_ALLOW = 12;
    public static final byte CHEST_CLEAR = 13;  // 管理员一键删除所有允许的容器和它们的记录
    /** 我背包里的物品: int n, (物品id, int 数量)*n；n=0 表示不再共享。变化时上报。 */
    public static final byte INVENTORY = 14;
    /** 团队验证开关(管理员): boolean。开启后所有成员的扫描会额外上报逐方块的缺失/错误/多余。 */
    public static final byte VERIFY_SET = 15;
    /**
     * 团队验证: 一个区块里**一个子区域**的验证结果 (每个子区域单独一个包，转发端按 区块+子区域 保存)。
     * sha, placeKey, cx, cz, int 子区域数(=1), (子区域名, 正确, 缺失, 错误方块, 多余, 错误状态)*1,
     * int 分组数, (子区域名, int 类型 0缺失 1错误方块 2多余 3错误状态 4正确, 期望方块id, 实际方块id, int 数量)*n,
     * int 位置数, (int: 分组序号<<24 | (y+64)<<8 | 区块内x<<4 | 区块内z)*m (每区块只带前一部分位置)。
     */
    public static final byte VERIFY_REPORT = 16;
    /** 渲染层同步(管理员): boolean 开关, json(模式/轴/层号/范围)。开启后所有成员的 Litematica 渲染层跟随管理员，成员改了会被还原。 */
    public static final byte LAYER_SET = 17;
    /** 手动刷新团队验证: 转发端把当前所有区块的验证结果重新发给请求的人 (先 VERIFY_RESET，再逐个 VERIFY_CHUNK)。 */
    public static final byte VERIFY_REFRESH = 18;
    /** 子任务 (管理员): 新建/修改一个 TaskRecord (id 为空表示新建，转发端分配 id)。 */
    public static final byte TASK_SET = 19;
    /** 房间列表: 连接后的第一个包发它(代替 JOIN)，转发端回一个 ROOMS 就断开；列出整个服务器的房间，选一个输入密码再 JOIN。 */
    public static final byte ROOM_LIST = 45;
    /** 本机没有房间当前的投影文件时向转发端要: sha。转发端回 FILE。 */
    public static final byte FILE_REQUEST = 48;
    /** 房间模式 (管理员): boolean，true = 任务模式 (子任务、按子区域筛选材料/验证)，false = 简单模式 (整个投影文件)。 */
    public static final byte MODE_SET = 43;
    public static final byte TASK_DELETE = 40;  // 管理员: id
    public static final byte TASK_STATUS = 41;  // 被分配的玩家或管理员: id, int status
    // server -> client
    public static final byte CHEST_SYNC = 27;   // 加入房间时下发全部记录: int n, ChestRecord*n (time 为"多久之前"毫秒)
    public static final byte CHEST_UPDATE = 28; // 一条新记录 (ChestRecord)
    public static final byte CHEST_GONE = 29;   // dim, x, y, z
    public static final byte CHEST_ALLOWED = 30;// 当前允许上传的容器全量: int n, (dim, x, y, z)*n (加入房间和每次变化时下发)
    public static final byte INV_UPDATE = 31;   // 某成员的背包: uuid, name, int n, (物品id, int 数量)*n
    public static final byte INV_GONE = 32;
    public static final byte VERIFY_STATE = 33; // boolean
    public static final byte VERIFY_CHUNK = 34; // 同 VERIFY_REPORT 的正文，转发别人的区块验证结果
    public static final byte VERIFY_RESET = 35;
    public static final byte TASKS = 42;
    public static final byte ROOMS = 46;
    /** 房间的投影文件没变且你已经有了 (JOIN 带的 haveSha 一致，或共享关了又开): 不重发文件，只告诉你 sha。 */
    public static final byte FILE_SAME = 47;        // int n, (房间名, int 在线人数, boolean 共享中, 投影名, boolean 任务模式)*n
    public static final byte MODE_STATE = 44;  // boolean 任务模式        // 房间全部子任务 (加入时和每次变化时下发): int n, TaskRecord*n
    public static final byte LAYER_STATE = 36;  // boolean 是否同步, json // 投影文件/位置变了或验证关闭: 清空所有区块的验证结果     // uuid: 该成员离开或不再共享背包
    public static final byte JOINED = 20;     // boolean isAdmin, boolean created
    public static final byte ERROR = 21;      // msg
    public static final byte MEMBERS = 22;    // int n, (uuid, name, boolean admin)*n
    public static final byte SHARE_STATE = 23;// boolean 共享开关, boolean 房间里是否有共享的投影文件 (关共享只是暂停，投影文件保留)
    /** 回复 MATERIALS_REQUEST；进度有变化时转发端也会主动推送给房间里所有成员 (最多每 2 秒一次)，这样不用重新验证也能一直同步。 */
    public static final byte MATERIALS = 25;  // sha, int 已观测区块数, int m, (方块id, long 观测区内需要, long 已摆)*m
    public static final byte PONG = 26;
    public static final byte CLEARED = 24;    // 房间的共享投影已被取消

    /** 进度/验证数据里「子区域 + 物品」的组合键分隔符 (子区域名可以含任何可见字符)。 */
    public static final String REGION_SEP = "\u0001";

    public static String regionKey(String region, String item) {
        return region + REGION_SEP + item;
    }

    /** 位置信息的关键字段，客户端上报进度时带上，转发服务据此丢弃过期(投影已被移动)的上报。 */
    public static String placeKey(int x, int y, int z, String rotation, String mirror) {
        return x + "," + y + "," + z + "|" + rotation + "|" + mirror;
    }

    private LitematicaShare() { }
}
