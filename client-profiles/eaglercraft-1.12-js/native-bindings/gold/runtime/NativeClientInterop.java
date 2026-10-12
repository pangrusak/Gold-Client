package gold.runtime;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

/** Explicit JSO facade, NOT automatic net.minecraft Java type substitution. */
public final class NativeClientInterop {
    public interface Link extends JSObject {}
    private NativeClientInterop() {}

    @JSBody(params = {"manifestJson"}, script =
        "if (!globalThis.goldClientNativeLink) throw new Error('Native linker is not installed'); " +
        "return globalThis.goldClientNativeLink.install(JSON.parse(manifestJson));")
    public static native Link install(String manifestJson);

    @JSBody(params = {"link"}, script = "return link.getMinecraft();")
    public static native JSObject minecraft(Link link);

    @JSBody(script = "return [];")
    public static native JSObject arguments();

    @JSBody(params = {"args", "value"}, script = "args.push(value);")
    public static native void addReference(JSObject args, JSObject value);

    @JSBody(params = {"args", "value"}, script = "args.push(value);")
    public static native void addString(JSObject args, String value);

    @JSBody(params = {"args", "value"}, script = "args.push(value);")
    public static native void addInt(JSObject args, int value);

    @JSBody(params = {"args", "value"}, script = "args.push(value);")
    public static native void addBoolean(JSObject args, boolean value);

    @JSBody(params = {"args", "value"}, script = "args.push(value);")
    public static native void addDouble(JSObject args, double value);

    @JSBody(params = {"link", "id", "receiver", "args"}, script =
        "return link.invokeTyped(id, receiver, args, 'reference');")
    public static native JSObject invokeReference(Link link, String id, JSObject receiver, JSObject args);

    @JSBody(params = {"link", "id", "receiver", "args"}, script =
        "return link.invokeTyped(id, receiver, args, 'string');")
    public static native String invokeString(Link link, String id, JSObject receiver, JSObject args);

    @JSBody(params = {"link", "id", "receiver", "args"}, script =
        "return link.invokeTyped(id, receiver, args, 'int');")
    public static native int invokeInt(Link link, String id, JSObject receiver, JSObject args);

    @JSBody(params = {"link", "id", "receiver", "args"}, script =
        "return link.invokeTyped(id, receiver, args, 'boolean');")
    public static native boolean invokeBoolean(Link link, String id, JSObject receiver, JSObject args);

    @JSBody(params = {"link", "id", "receiver", "args"}, script =
        "return link.invokeTyped(id, receiver, args, 'number');")
    public static native double invokeDouble(Link link, String id, JSObject receiver, JSObject args);

    @JSBody(params = {"link", "id", "receiver", "args"}, script =
        "link.invokeTyped(id, receiver, args, 'void');")
    public static native void invokeVoid(Link link, String id, JSObject receiver, JSObject args);
}
