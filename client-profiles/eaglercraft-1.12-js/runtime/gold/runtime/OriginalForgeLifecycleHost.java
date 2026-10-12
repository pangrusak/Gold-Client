package gold.runtime;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import gold.generated.ForgeModBootstrap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.FMLModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.discovery.ASMDataTable;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.relauncher.Side;

/** Uses original Forge event constructors and EventBus, never substitutes their algorithms. */
public final class OriginalForgeLifecycleHost implements ForgeModBootstrap.Host {
    private final OriginalForgeRuntimeServices.Services services;
    private final Set<Class<?>> registered=new HashSet<Class<?>>();
    private String enteredPhase;
    private Object instance;
    public OriginalForgeLifecycleHost() { services=OriginalForgeRuntimeServices.require(); }
    private FMLModContainer active(String modId) {
        FMLModContainer container=services.container(modId);
        if(container==null||!modId.equals(container.getModId()))throw new IllegalStateException("Original mod container is missing or mismatched");
        if(Loader.instance().activeModContainer()!=container)throw new IllegalStateException("Original Loader active mod context is not bound");
        if(FMLCommonHandler.instance().getSide()!=Side.CLIENT)throw new IllegalStateException("Original Forge client side is not bound");
        return container;
    }
    public void beforeConstruction(String modId) {
        services.requireClientCapabilities("minecraftObjects","textures","resources","inputEvents","configurationFiles","forgeNetworking","forgeModContext");
        services.enterConstruction(modId);active(modId);
    }
    public void afterConstruction(String modId,Object originalModInstance) {
        if(instance!=null||originalModInstance==null)throw new IllegalStateException("Invalid original mod instance handoff");
        services.bindOriginalInstance(modId,originalModInstance);
        if(active(modId).getMod()!=originalModInstance)throw new IllegalStateException("Container does not hold the exact constructed original instance");
        instance=originalModInstance;
    }
    public Object metadataFor(String modId) {
        FMLModContainer container=active(modId);
        Object metadata=container.getMetadata();
        if(metadata==null)throw new IllegalStateException("Original Forge metadata is missing for "+modId);
        return metadata;
    }
    public Object eventFor(String modId,String phase) {
        if(instance==null)throw new IllegalStateException("Original mod instance has not been bound");
        services.enterPhase(modId,phase);enteredPhase=phase;
        FMLModContainer container=active(modId);
        if(phase.equals("preInit")) {
            ASMDataTable data=services.harvestedData();File directory=services.configurationDirectory();
            if(data==null||directory==null)throw new IllegalStateException("Original ASM discovery/configuration context is missing");
            FMLPreInitializationEvent event=new FMLPreInitializationEvent(data,directory);
            event.applyModContainer(container);
            if(event.getAsmData()!=data||event.getModConfigurationDirectory()!=directory||event.getModMetadata()!=container.getMetadata()||event.getSourceFile()!=container.getSource()) {
                throw new IllegalStateException("Original pre-initialization context was not preserved");
            }
            return event;
        }
        if(phase.equals("init"))return new FMLInitializationEvent();
        if(phase.equals("postInit"))return new FMLPostInitializationEvent();
        if(phase.equals("loadComplete"))return new FMLLoadCompleteEvent();
        throw new IllegalArgumentException("Unsupported original lifecycle phase: "+phase);
    }
    public void afterPhase(String modId,String phase) {
        if(!phase.equals(enteredPhase))services.enterPhase(modId,phase);
        active(modId);services.completePhase(modId,phase);enteredPhase=null;
    }
    public void registerStaticSubscriber(String modId,Class<?> owner,Class<?> eventType,String method,String priority,boolean receiveCanceled,ForgeModBootstrap.SubscriberInvoker invoker) {
        if(instance==null)throw new IllegalStateException("Original mod instance must be bound before registration");
        active(modId);
        if(owner==null||eventType==null||invoker==null)throw new IllegalArgumentException("Incomplete subscriber binding");
        // Original EventBus reads the original annotations, priority, and cancellation settings.
        if(registered.add(owner))MinecraftForge.EVENT_BUS.register(owner);
    }
}
