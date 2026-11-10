import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 发布工具 (java tools/SignUpdate.java ...):
 *   keygen <私钥文件>                       生成 Ed25519 密钥对，私钥写到文件，公钥(base64)打印出来
 *   sign <私钥文件> <模组id> <版本> <jar> <输出目录> [更新说明]   生成 <输出目录>/mod.jar 和 latest.txt
 * 私钥不要放进项目目录，也不要上传。
 */
public class SignUpdate {
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "keygen" -> {
                KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                Path f = Path.of(a[1]);
                if (Files.exists(f)) throw new IllegalStateException(f + " 已存在，不覆盖");
                Files.createDirectories(f.getParent());
                Files.writeString(f, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
                f.toFile().setReadable(false, false); f.toFile().setReadable(true, true);
                System.out.println(Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
            }
            case "sign" -> {
                PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(Path.of(a[1])).trim())));
                String modId = a[2], version = a[3];
                byte[] jar = Files.readAllBytes(Path.of(a[4]));
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
                Signature sig = Signature.getInstance("Ed25519");
                sig.initSign(key);
                sig.update((modId + "\n" + version + "\n" + sha).getBytes(StandardCharsets.UTF_8));
                String s = Base64.getEncoder().encodeToString(sig.sign());
                Path out = Path.of(a[5]);
                Files.createDirectories(out);
                Files.write(out.resolve("mod.jar"), jar);
                Files.writeString(out.resolve("latest.txt"), version + "\n" + sha + "\n" + s + "\n" + (a.length > 6 ? a[6].replace("\n", " ") : "") + "\n");
                System.out.println("已生成 " + out + " (版本 " + version + ", sha256 " + sha.substring(0, 12) + "…)");
            }
            default -> throw new IllegalArgumentException("keygen | sign");
        }
    }
}
