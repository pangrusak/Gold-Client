package gold.runtime;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

/** Reference to the existing client object, not a replacement Java Minecraft implementation. */
public final class NativeClientReference {
    public interface MinecraftReference extends JSObject {}
    private NativeClientReference() {}
    @JSBody(script = "if (!window.goldClientNativeRuntime) throw new Error('Native client runtime binding is not installed'); return window.goldClientNativeRuntime.getMinecraft();")
    public static native MinecraftReference minecraft();
    @JSBody(params = {"reference"}, script = "if (!window.goldClientNativeRuntime) throw new Error('Native client runtime binding is not installed'); return window.goldClientNativeRuntime.isCurrentMinecraft(reference);")
    public static native boolean isCurrentMinecraft(MinecraftReference reference);
}
