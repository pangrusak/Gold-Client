package com.goldclient.translator;

import com.goldclient.model.ClassModel;
import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import com.goldclient.model.MethodModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeCompatibilityPlannerTest {
  @TempDir
  Path tempDir;

  @Test
  void groupsForgeMinecraftAndJavaReferencesIntoActionableFamilies() {
    ClassModel mod = new ClassModel("sample/ExampleMod", "java/lang/Object",
        List.of("net/minecraftforge/common/SomeInterface"), Opcodes.ACC_PUBLIC, null,
        List.of(), List.of(), List.of(
            method("preInit", "(Lnet/minecraftforge/fml/common/event/FMLPreInitializationEvent;Lnet/minecraft/client/Minecraft;)V",
                Opcodes.ACC_PUBLIC, List.of(op("METHOD_CALL", "net/minecraft/client/Minecraft.getMinecraft()"))),
            method("parse", "(Ljava/util/List;)V", Opcodes.ACC_PUBLIC,
                List.of(op("METHOD_CALL", "java/util/List.add")))),
        List.of());

    var plan = new ForgeCompatibilityPlanner().plan(List.of(mod));
    assertEquals("gold-forge-compatibility-plan-v1", plan.formatVersion());
    assertEquals(1, plan.classCount());
    assertEquals(2, plan.methodCount());
    assertTrue(plan.references().stream().anyMatch(ref -> ref.owner().equals("net/minecraftforge/common/SomeInterface")
        && ref.family().equals("FORGE")));
    assertTrue(plan.references().stream().anyMatch(ref -> ref.owner().equals("net/minecraft/client/Minecraft")
        && ref.family().equals("MINECRAFT")));
    assertTrue(plan.references().stream().anyMatch(ref -> ref.owner().equals("java/util/List")
        && ref.family().equals("JAVA_PLATFORM")));
    assertTrue(plan.nextActions().stream().anyMatch(action -> action.contains("Eaglercraft")));
  }

  @Test
  void keepsUnknownModLibrariesVisibleInsteadOfPretendingTheyAreSupported() {
    ClassModel mod = new ClassModel("sample/UsesLibrary", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(), List.of(),
        List.of(method("run", "()V", Opcodes.ACC_PUBLIC,
            List.of(op("METHOD_CALL", "com/example/library/Feature.run")))), List.of());

    var plan = new ForgeCompatibilityPlanner().plan(List.of(mod));
    assertTrue(plan.references().stream().anyMatch(ref ->
        ref.owner().equals("com/example/library/Feature") && ref.supportLevel().equals("UNBOUND")));
    assertTrue(plan.blockingFamilies().contains("MOD_OR_LIBRARY"));
    assertTrue(plan.nextActions().stream().anyMatch(action -> action.contains("UNBOUND")));
  }

  @Test
  void exportsMachineReadableAndHumanReadablePlans() throws Exception {
    ClassModel mod = new ClassModel("sample/Empty", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(), List.of(),
        List.of(method("run", "()V", Opcodes.ACC_PUBLIC, List.of(op("RETURN")))), List.of());

    var plan = new ForgeCompatibilityPlanner().export(List.of(mod), tempDir);
    String json = Files.readString(tempDir.resolve("forge-compatibility-plan.json"));
    String report = Files.readString(tempDir.resolve("forge-compatibility-plan.txt"));
    assertEquals(1, plan.methodCount());
    assertTrue(json.contains("gold-forge-compatibility-plan-v1"));
    assertTrue(json.contains("Eaglercraft 1.12.2"));
    assertTrue(report.contains("not proof that the mod runs"));
    assertTrue(report.contains("Unbound families: none detected"));
  }

  @Test
  void classifiesUnknownAndUnsupportedMethodsConservatively() {
    ClassModel mod = new ClassModel("sample/Statuses", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(), List.of(), List.of(
            method("known", "()V", Opcodes.ACC_PUBLIC, List.of(op("RETURN"))),
            method("unknown", "()V", Opcodes.ACC_PUBLIC, List.of(op("UNKNOWN", "custom opcode"))),
            method("nativeCall", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, List.of())),
        List.of());
    var plan = new ForgeCompatibilityPlanner().plan(List.of(mod));
    assertEquals(1, plan.completeMethodCount());
    assertEquals(1, plan.incompleteMethodCount());
    assertEquals(1, plan.stubbedMethodCount());
    assertEquals(2, plan.translationWarnings().size());
  }

  private static MethodModel method(String name, String descriptor, int access,
      List<IrOperationModel> operations) {
    return new MethodModel(name, descriptor, null, access, List.of(), List.of(), List.of(),
        List.of(), List.of(), 2, 2, new com.goldclient.model.ControlFlowModel(0, List.of()),
        new IrMethodModel(name, descriptor, operations));
  }

  private static IrOperationModel op(String kind, String... operands) {
    return new IrOperationModel(kind, List.of(operands), 0);
  }
}
