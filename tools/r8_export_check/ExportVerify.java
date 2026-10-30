import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.StreamingAead;
import com.kitalonlabs.neogold.decrypt.DecryptEngine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/** `keygen` prints a synthetic key pair; otherwise opens what ExportHarness wrote, with the offline decrypt tool (not shrunk). */
public final class ExportVerify {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("keygen")) {
            kotlin.Pair<byte[], byte[]> pair = DecryptEngine.INSTANCE.generateKeyPair();
            System.out.println(Base64.getEncoder().encodeToString(pair.getFirst()) + " "
                + Base64.getEncoder().encodeToString(pair.getSecond()));
            return;
        }
        byte[] privateKey = Base64.getDecoder().decode(args[0]);
        File dir = new File(args[1]);
        String wrapped = new String(Files.readAllBytes(new File(dir, "wrapped.txt").toPath()), StandardCharsets.UTF_8);
        byte[] ciphertext = Files.readAllBytes(new File(dir, "ciphertext.bin").toPath());
        StreamingAead aead = DecryptEngine.INSTANCE.unwrapDataKey(wrapped, privateKey)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        DecryptEngine.INSTANCE.decryptStream(aead, new ByteArrayInputStream(ciphertext), plain,
            "images/SYN-0001.enc".getBytes(StandardCharsets.UTF_8));
        String text = new String(plain.toByteArray(), StandardCharsets.UTF_8);
        if (!"synthetic export plaintext".equals(text)) throw new IllegalStateException("wrong plaintext: " + text);
        System.out.println("OK: the shrunk export opens with the decrypt tool");
    }
}
