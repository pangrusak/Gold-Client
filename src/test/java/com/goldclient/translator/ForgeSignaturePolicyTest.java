package com.goldclient.translator;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;
class ForgeSignaturePolicyTest {
    @Test void identifiesOnlyRootSignatureMetadata() {
        assertTrue(ForgeOriginalAccessTransform.signatureEntry("META-INF/MOJANGCS.RSA"));
        assertTrue(ForgeOriginalAccessTransform.signatureEntry("META-INF/MOJANGCS.SF"));
        assertTrue(ForgeOriginalAccessTransform.signatureEntry("META-INF/SIG-CUSTOM"));
        assertFalse(ForgeOriginalAccessTransform.signatureEntry("META-INF/MANIFEST.MF"));
        assertFalse(ForgeOriginalAccessTransform.signatureEntry("META-INF/services/example.RSA"));
        assertFalse(ForgeOriginalAccessTransform.signatureEntry("assets/example.RSA"));
    }
    @Test void removesDigestsButPreservesNonSigningManifestMetadata() throws Exception {
        Manifest input = new Manifest();
        input.getMainAttributes().putValue("Manifest-Version", "1.0");
        input.getMainAttributes().putValue("Main-Class", "example.Main");
        input.getMainAttributes().putValue("FMLAT", "example_at.cfg");
        input.getMainAttributes().putValue("SHA-256-Digest-Manifest", "obsolete");
        Attributes signed = new Attributes();
        signed.putValue("SHA-256-Digest", "obsolete");
        signed.putValue("Sealed", "true");
        input.getEntries().put("example/Main.class", signed);
        Attributes digestOnly = new Attributes(); digestOnly.putValue("SHA1-Digest", "obsolete");
        input.getEntries().put("example/Other.class", digestOnly);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(); input.write(encoded);
        Manifest output = new Manifest(new ByteArrayInputStream(
                ForgeOriginalAccessTransform.unsignedManifest(encoded.toByteArray())));
        assertEquals("example.Main", output.getMainAttributes().getValue("Main-Class"));
        assertEquals("example_at.cfg", output.getMainAttributes().getValue("FMLAT"));
        assertNull(output.getMainAttributes().getValue("SHA-256-Digest-Manifest"));
        assertEquals("true", output.getAttributes("example/Main.class").getValue("Sealed"));
        assertNull(output.getAttributes("example/Main.class").getValue("SHA-256-Digest"));
        assertNull(output.getAttributes("example/Other.class"));
    }
}
