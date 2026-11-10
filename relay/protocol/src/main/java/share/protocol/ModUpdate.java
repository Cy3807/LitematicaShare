package share.protocol;

import java.nio.charset.StandardCharsets;

/**
 * 通道 "mod_update": 所有模组共用的自动更新。服务器只存放已签名的新版本 (relay-data/update/&lt;模组id&gt;/)，
 * 客户端用内置的公钥验证签名，所以即使转发服务器被入侵也推不了恶意 jar。
 */
public final class ModUpdate {
    public static final String CHANNEL = "mod_update";
    public static final int VERSION = 1;

    // client -> server
    public static final byte CHECK = 1;     // 模组 id
    public static final byte DOWNLOAD = 2;  // 模组 id
    // server -> client
    public static final byte INFO = 3;      // 模组 id, 最新版本, sha256(十六进制), long 大小, 签名(base64), 更新说明
    public static final byte NONE = 4;      // 服务器上没有这个模组的更新
    public static final byte FILE = 5;      // bytes: 模组 jar

    /** 签名的内容: 模组 id、版本、jar 的 sha256，防止把旧版本/别的模组的签名套到别的文件上。 */
    public static byte[] signedBytes(String modId, String version, String sha256) {
        return (modId + "\n" + version + "\n" + sha256).getBytes(StandardCharsets.UTF_8);
    }

    /** 点分数字版本比较 (0.2.0 &gt; 0.1.9)，后缀如 -beta 忽略。 */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("[-+]")[0].split("\\."), y = b.split("[-+]")[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private ModUpdate() { }
}
