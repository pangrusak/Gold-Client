package gold.runtime;

import java.io.File;
import net.minecraftforge.fml.common.FMLModContainer;
import net.minecraftforge.fml.common.discovery.ASMDataTable;

/** Must be implemented by actual client/Forge bindings, not by a no-op provider. */
public final class OriginalForgeRuntimeServices {
    public interface Services {
        void requireClientCapabilities(String... capabilities);
        FMLModContainer container(String modId);
        ASMDataTable harvestedData();
        File configurationDirectory();
        void enterConstruction(String modId);
        void bindOriginalInstance(String modId, Object originalInstance);
        void enterPhase(String modId, String phase);
        void completePhase(String modId, String phase);
    }
    private static Services installed;
    private OriginalForgeRuntimeServices() {}
    public static void install(Services services) {
        if(services==null||installed!=null)throw new IllegalStateException("Runtime services must be installed exactly once");
        installed=services;
    }
    public static Services require() {
        if(installed==null)throw new IllegalStateException("Actual client/Forge runtime services are not installed; refusing mod startup");
        return installed;
    }
}
