package net.minecraftforge.fml.common.eventhandler;

public final class EventBus {
  public void register(Object listener) {
    throw unavailable();
  }

  public void unregister(Object listener) {
    throw unavailable();
  }

  private UnsupportedOperationException unavailable() {
    return new UnsupportedOperationException(
        "Deferred Forge event delivery is unavailable in this client profile");
  }
}
