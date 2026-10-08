package net.minecraftforge.fml.common;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.teavm.jso.JSBody;

public final class ProgressManager {
  private static final List<ProgressBar> BARS = new CopyOnWriteArrayList<>();

  private ProgressManager() {
  }

  public static ProgressBar push(String title, int steps) {
    ProgressBar bar = new ProgressBar(title, steps);
    BARS.add(bar);
    publish(bar, true);
    return bar;
  }

  public static void pop(ProgressBar bar) {
    if (bar.getSteps() != bar.getStep()) {
      throw new IllegalStateException("can't pop unfinished ProgressBar " + bar.getTitle());
    }
    BARS.remove(bar);
    publish(bar, false);
    if (!BARS.isEmpty()) {
      publish(BARS.get(BARS.size() - 1), true);
    }
  }

  private static void publish(ProgressBar bar, boolean active) {
    notifyClient(bar.getTitle(), bar.getSteps(), bar.getStep(), bar.getMessage(), active);
  }

  @JSBody(params = {"title", "steps", "step", "message", "active"}, script = """
      if (typeof window !== "undefined") {
          if (typeof window.__goldClientJeiProgressUpdate !== "function") {
              throw new Error("JEI progress reporter is not installed");
          }
          window.__goldClientJeiProgressUpdate(title, steps, step, message, active);
      }
      """)
  private static native void notifyClient(
      String title, int steps, int step, String message, boolean active);

  public static Iterator<ProgressBar> barIterator() {
    return BARS.iterator();
  }

  public static final class ProgressBar {
    private final String title;
    private final int steps;
    private volatile int step;
    private volatile String message = "";

    private ProgressBar(String title, int steps) {
      this.title = title;
      this.steps = steps;
    }

    public void step(String message) {
      if (step >= steps) {
        throw new IllegalStateException("too much steps for ProgressBar " + title);
      }
      step++;
      this.message = message;
      publish(this, true);
    }

    public String getTitle() {
      return title;
    }

    public int getSteps() {
      return steps;
    }

    public int getStep() {
      return step;
    }

    public String getMessage() {
      return message;
    }
  }
}
