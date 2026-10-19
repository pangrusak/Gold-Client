package com.goldclient.analyzer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class ModMetadataTest{@Test void storesCoreMetadata(){ModMetadata m=new ModMetadata();m.setName("Test Mod");m.setVersion("1.2.3");m.setMinecraftVersion("1.12.2");m.setLoader("Fabric");m.addDependency("fabric-api");assertEquals("Test Mod",m.getName());assertEquals("1.2.3",m.getVersion());assertEquals("1.12.2",m.getMinecraftVersion());assertEquals("Fabric",m.getLoader());assertEquals(java.util.List.of("fabric-api"),m.getDependencies());}
 @Test void tracksModIdAndAllowsReset(){ModMetadata m=new ModMetadata();
  assertEquals("Unknown",m.getModId());
  m.setModId("example_mod");
  assertEquals("example_mod",m.getModId());
  // A multi-entry mcmod.info does not identify one mod, so the ID resets.
  m.setModId(null);
  assertEquals("Unknown",m.getModId());}}
