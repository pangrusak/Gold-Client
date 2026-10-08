package com.goldclient.bridge;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;

public final class ClientBridgeFixture {
  private ClientBridgeFixture() {
  }

  public static void main(String[] args) {
    showStatus("Gold Client bridge: waiting for world");
  }

  @JSExport
  public static void onClientTick() {
    if (!isConnected()) {
      showStatus("Gold Client bridge: waiting for world");
      return;
    }

    showStatus("Gold Client bridge XYZ: "
        + (int) Math.floor(playerX()) + " "
        + (int) Math.floor(playerY()) + " "
        + (int) Math.floor(playerZ()));
  }

  @JSBody(script = "var p = window.goldClientReadPlayerPosition(); return p !== null && p.connected === true;")
  private static native boolean isConnected();

  @JSBody(script = "return window.goldClientReadPlayerPosition().x;")
  private static native double playerX();

  @JSBody(script = "return window.goldClientReadPlayerPosition().y;")
  private static native double playerY();

  @JSBody(script = "return window.goldClientReadPlayerPosition().z;")
  private static native double playerZ();

  @JSBody(params = "text", script = """
      if (typeof document === "undefined") return;
      var id = "gold-client-bridge-proof";
      var element = document.getElementById(id);
      if (element === null) {
          element = document.createElement("div");
          element.id = id;
          element.style.position = "fixed";
          element.style.left = "8px";
          element.style.top = "8px";
          element.style.zIndex = "2147483647";
          element.style.padding = "6px 8px";
          element.style.background = "rgba(0, 0, 0, 0.8)";
          element.style.color = "#7CFC00";
          element.style.font = "bold 13px monospace";
          element.style.pointerEvents = "none";
          (document.body || document.documentElement).appendChild(element);
      }
      element.textContent = text;
      """)
  private static native void showStatus(String text);
}
