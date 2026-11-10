package share.mod;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 构建时写进 jar 的值 (share_defaults.properties)：目前只有自动更新的公钥。模组里永远没有服务器地址。
 * 开源源码里公钥是空的，别人构建出来的模组不启用自动更新；作者自己打包时在 local.properties 里填 update_public_key。
 */
final class BuildDefaults {
    private static final Properties P = load();

    private static Properties load() {
        Properties p = new Properties();
        if (FabricLoader.getInstance().isModLoaded("litematica_share_test")) return p; // 自动化测试不用默认值，也不去连真实服务器
        try (InputStream in = BuildDefaults.class.getResourceAsStream("/share_defaults.properties")) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {
        }
        return p;
    }

    /** 自动更新用的 Ed25519 公钥 (X.509 base64)；没有就空串，此时不启用自动更新。 */
    static String updateKey() { return clean(P.getProperty("update_public_key")); }

    private static String clean(String s) {
        return s == null || s.contains("${") ? "" : s.trim();
    }

    private BuildDefaults() { }
}
