package com.goldclient.translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
class ForgeRuntimeEvidenceTest {
    @TempDir Path temp;
    private Path[] fixture()throws Exception {
        Path root=temp.resolve("src"),classes=temp.resolve("classes");Files.createDirectories(classes);
        Map<String,String> sources=new LinkedHashMap<>();
        sources.put("net/minecraftforge/common/ForgeVersion.java","package net.minecraftforge.common;public class ForgeVersion{public static final String mcVersion=\"1.12.2\";public static String getVersion(){return \"14.23.5.3000\";}}");
        sources.put("net/minecraftforge/fml/common/versioning/ArtifactVersion.java","package net.minecraftforge.fml.common.versioning;public interface ArtifactVersion{}");
        sources.put("net/minecraftforge/fml/common/versioning/DefaultArtifactVersion.java","package net.minecraftforge.fml.common.versioning;public class DefaultArtifactVersion implements ArtifactVersion{public DefaultArtifactVersion(String version){}}");
        sources.put("net/minecraftforge/fml/common/versioning/VersionRange.java","package net.minecraftforge.fml.common.versioning;public class VersionRange{private String spec;public static VersionRange createFromVersionSpec(String s){VersionRange r=new VersionRange();r.spec=s;return r;}public boolean containsVersion(ArtifactVersion v){return spec.equals(\"ACCEPT\");}}");
        List<String> args=new ArrayList<>(List.of("--release","8","-d",classes.toString()));
        for(var e:sources.entrySet()){Path f=root.resolve(e.getKey());Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());args.add(f.toString());}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        Path runtime=temp.resolve("runtime.jar"),at=temp.resolve("at.jar");
        try(var output=new JarOutputStream(Files.newOutputStream(runtime));var paths=Files.walk(classes)){
            for(Path f:paths.filter(Files::isRegularFile).toList()){output.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\','/')));Files.copy(f,output);output.closeEntry();}}
        try(var output=new JarOutputStream(Files.newOutputStream(at))){}
        Properties p=new Properties();p.setProperty("engine","net.minecraftforge.fml.common.asm.transformers.AccessTransformer");
        p.setProperty("output.sha256",VerifiedForgeAccess.sha256(at));p.setProperty("runtime.0.path",runtime.toString());p.setProperty("runtime.0.sha256",VerifiedForgeAccess.sha256(runtime));
        try(var output=Files.newOutputStream(Path.of(at.toString()+".provenance.properties"))){p.store(output,"Synthetic fixture, not real Forge");}
        Path evidence=temp.resolve("runtime-evidence.properties");ForgeRuntimeEvidence.write(at,evidence);return new Path[]{runtime,at,evidence};
    }
    @Test void readsSuppliedVersionCodeAndDetectsChangedRuntime()throws Exception {
        Path[] f=fixture();assertEquals("14.23.5.3000",ForgeRuntimeEvidence.verify(f[2],f[1]));
        Files.write(f[0],new byte[]{0},StandardOpenOption.APPEND);assertThrows(java.io.IOException.class,()->ForgeRuntimeEvidence.verify(f[2],f[1]));
    }
    @Test void delegatesRangeChecksAndRetainsUnsupportedDependencyBlockers()throws Exception {
        Path[] f=fixture();assertTrue(ForgeRuntimeEvidence.contains(f[2],f[1],"ACCEPT"));assertFalse(ForgeRuntimeEvidence.contains(f[2],f[1],"REJECT"));
        List<String> blockers=new ArrayList<>();ForgeRuntimeEvidence.dependencies("required-after:forge@ACCEPT;required-after:othermod@[1,)",f[2],f[1],blockers);
        assertEquals(1,blockers.size());assertTrue(blockers.get(0).contains("othermod"));
    }
}
