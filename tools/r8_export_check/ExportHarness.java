import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.TinkProtoKeysetFormat;
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import com.kitalonlabs.neogold.core.security.export.ExportCryptoEngine;
import com.kitalonlabs.neogold.core.security.export.ExportSession;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/** Runs the app's export crypto (the same entry points ResearchExportManager uses) inside an R8-shrunk jar. */
public final class ExportHarness {
    public static void main(String[] args) throws Exception {
        byte[] publicKey = Base64.getDecoder().decode(args[0]);
        File out = new File(args[1]);
        out.mkdirs();
        ExportSession session = ExportCryptoEngine.INSTANCE.createSession();
        byte[] aad = "images/SYN-0001.enc".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = session.encryptBytes("synthetic export plaintext".getBytes(StandardCharsets.UTF_8), aad);
        Files.write(new File(out, "ciphertext.bin").toPath(), ciphertext);
        Files.write(new File(out, "wrapped.txt").toPath(), session.wrapDataKey(publicKey).getBytes(StandardCharsets.UTF_8));
        System.out.println("encrypted " + ciphertext.length + " bytes");
        keysetRoundTrip();
    }

    /**
     * The other thing the app does with Tink: FileKeysetProvider stores a serialized keyset and parses it back on the next start.
     * Parsing goes through Tink's shaded protobuf, which reads its fields by name, so this is the step R8 renaming would break.
     */
    private static void keysetRoundTrip() throws Exception {
        StreamingAeadConfig.register();
        KeysetHandle fresh = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB);
        byte[] stored = TinkProtoKeysetFormat.serializeKeyset(fresh, InsecureSecretKeyAccess.get());
        KeysetHandle loaded = TinkProtoKeysetFormat.parseKeyset(stored, InsecureSecretKeyAccess.get());
        StreamingAead aead = loaded.getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
        StreamingAead original = fresh.getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
        byte[] plain = "stored keyset".getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream enc = new java.io.ByteArrayOutputStream();
        try (java.io.OutputStream out = original.newEncryptingStream(enc, new byte[0])) { out.write(plain); }
        java.io.ByteArrayOutputStream dec = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = aead.newDecryptingStream(new java.io.ByteArrayInputStream(enc.toByteArray()), new byte[0])) {
            in.transferTo(dec);
        }
        if (!new String(dec.toByteArray(), StandardCharsets.UTF_8).equals("stored keyset")) {
            throw new IllegalStateException("a stored keyset did not parse back to the same key");
        }
        System.out.println("keyset round trip ok");
    }
}
