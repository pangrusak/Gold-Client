package com.goldclient.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Local web UI for dropping mod JARs and creating a self-contained client.
 */
public final class UiCommand {
  private static final long MAX_REQUEST_BYTES = 1L << 30;

  private UiCommand() {}

  public static int run(String[] args, PrintStream out, PrintStream err) {
    int port = 8080;
    boolean open = false;
    try {
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--help" -> {
            usage(out);
            return 0;
          }
          case "--port" -> {
            if (++i >= args.length) throw new IllegalArgumentException("Missing value for --port");
            port = Integer.parseInt(args[i]);
          }
          case "--open" -> open = true;
          default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
        }
      }
    } catch (RuntimeException exception) {
      err.println("Could not start UI: " + exception.getMessage());
      return 1;
    }

    try {
      byte[] page = readResource("ui.html");
      Map<String, Path> downloads = new ConcurrentHashMap<>();
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      server.setExecutor(Executors.newFixedThreadPool(4));
      server.createContext("/", exchange -> servePage(exchange, page));
      server.createContext("/api/create-client", exchange -> createClient(exchange, downloads));
      server.createContext("/api/download/", exchange -> download(exchange, downloads));
      server.createContext("/health", exchange -> respond(exchange, 200, "text/plain", "ok"));
      server.start();

      int actualPort = server.getAddress().getPort();
      String url = "http://127.0.0.1:" + actualPort + "/";
      out.println("Gold Client UI: " + url);
      out.println("Drop Forge 1.12.2 mod JARs in the browser to create a client.");
      out.println("Press Ctrl+C to stop.");
      if (open && Desktop.isDesktopSupported()) {
        try {
          Desktop.getDesktop().browse(URI.create(url));
        } catch (IOException ignored) {
        }
      }
      return 0;
    } catch (Exception exception) {
      err.println("Could not start UI: " + exception.getMessage());
      return 1;
    }
  }

  private static void servePage(HttpExchange exchange, byte[] page) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
        && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "text/plain", "Method not allowed");
      return;
    }
    if (!"/".equals(exchange.getRequestURI().getPath())
        && !"/index.html".equals(exchange.getRequestURI().getPath())) {
      respond(exchange, 404, "text/plain", "Not found");
      return;
    }
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, page.length);
    try (OutputStream body = exchange.getResponseBody()) {
      body.write(page);
    }
  }

  private static void createClient(HttpExchange exchange, Map<String, Path> downloads)
      throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "application/json",
          "{\"ok\":false,\"error\":\"Method not allowed\"}");
      return;
    }
    String length = exchange.getRequestHeaders().getFirst("Content-Length");
    if (length != null && Long.parseLong(length) > MAX_REQUEST_BYTES) {
      respond(exchange, 413, "application/json",
          "{\"ok\":false,\"error\":\"Upload is too large\"}");
      return;
    }

    Path work = Path.of("cache", "ui").toAbsolutePath().normalize()
        .resolve(UUID.randomUUID().toString());
    try {
      JsonObject request = JsonParser.parseString(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject();
      JsonArray files = request.has("files")
          ? request.getAsJsonArray("files") : new JsonArray();
      if (files.isEmpty()) {
        respond(exchange, 400, "application/json",
            "{\"ok\":false,\"error\":\"Add at least one mod JAR\"}");
        return;
      }

      Path inputs = work.resolve("inputs");
      Files.createDirectories(inputs);
      List<Path> jars = new ArrayList<>();
      for (JsonElement element : files) {
        JsonObject file = element.getAsJsonObject();
        String name = sanitize(getString(file, "name", "mod.jar"));
        String data = getString(file, "data", "");
        if (data.isBlank()) {
          throw new IllegalArgumentException("Uploaded file is empty: " + name);
        }
        Path target = uniqueTarget(inputs, name);
        Files.write(target, Base64.getDecoder().decode(data));
        jars.add(target);
      }

      Path output = Path.of("clients", "build", "web", work.getFileName().toString(),
          "gold-client.html").toAbsolutePath().normalize();
      Files.createDirectories(output.getParent());

      List<String> command = new ArrayList<>();
      for (Path jar : jars) {
        command.add("--input");
        command.add(jar.toString());
      }
      command.add("--output");
      command.add(output.toString());
      command.add("--force");
      command.add("--json");

      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
      int code;
      try (PrintStream commandOut = new PrintStream(captured, true, StandardCharsets.UTF_8);
           PrintStream commandErr = new PrintStream(capturedErr, true, StandardCharsets.UTF_8)) {
        code = CreateClientCommand.run(command.toArray(String[]::new), commandOut, commandErr);
      }

      if (code == 1 || !Files.isRegularFile(output)) {
        String detail = capturedErr.toString(StandardCharsets.UTF_8).strip();
        if (detail.isEmpty()) detail = "Client build failed";
        respond(exchange, 500, "application/json", new Gson().toJson(error(detail)));
        return;
      }

      JsonObject result = lastJson(captured.toString(StandardCharsets.UTF_8));
      if (result == null) result = new JsonObject();
      result.addProperty("ok", true);
      result.addProperty("complete", code == 0);
      result.addProperty("output", output.toString());
      String id = work.getFileName().toString();
      downloads.put(id, output);
      result.addProperty("downloadUrl", "/api/download/" + id);
      respond(exchange, 200, "application/json", new Gson().toJson(result));
    } catch (IllegalArgumentException exception) {
      respond(exchange, 400, "application/json",
          new Gson().toJson(error(exception.getMessage())));
    } catch (Exception exception) {
      respond(exchange, 500, "application/json",
          new Gson().toJson(error(exception.getMessage() == null
              ? exception.getClass().getSimpleName() : exception.getMessage())));
    } finally {
      deleteRecursively(work.resolve("inputs"));
      deleteRecursively(work);
    }
  }

  private static void download(HttpExchange exchange, Map<String, Path> downloads)
      throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
        && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "text/plain", "Method not allowed");
      return;
    }
    String id = exchange.getRequestURI().getPath().substring("/api/download/".length());
    Path file;
    try {
      UUID.fromString(id);
      file = downloads.get(id);
      if (file == null) {
        file = Path.of("clients", "build", "web", id, "gold-client.html")
            .toAbsolutePath().normalize();
      }
    } catch (IllegalArgumentException invalidId) {
      respond(exchange, 400, "text/plain", "Invalid download id");
      return;
    }
    if (!Files.isRegularFile(file)) {
      respond(exchange, 404, "text/plain", "Download not found");
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.getResponseHeaders().set("Content-Disposition",
        "attachment; filename=\"gold-client.html\"");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, Files.size(file));
    try (OutputStream body = exchange.getResponseBody()) {
      Files.copy(file, body);
    }
  }

  private static byte[] readResource(String name) throws IOException {
    try (InputStream stream = UiCommand.class.getResourceAsStream(
        "/com/goldclient/client/" + name)) {
      if (stream == null) throw new IOException("Packaged UI resource is missing: " + name);
      return stream.readAllBytes();
    }
  }

  private static void respond(HttpExchange exchange, int status, String contentType, String text)
      throws IOException {
    byte[] body = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private static JsonObject error(String message) {
    JsonObject object = new JsonObject();
    object.addProperty("ok", false);
    object.addProperty("error", message);
    return object;
  }

  private static JsonObject lastJson(String text) {
    String[] lines = text.split("\\R");
    for (int i = lines.length - 1; i >= 0; i--) {
      String line = lines[i].strip();
      if (line.startsWith("{")) {
        try {
          return JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException ignored) {
        }
      }
    }
    return null;
  }

  private static String getString(JsonObject object, String name, String fallback) {
    return object.has(name) && !object.get(name).isJsonNull()
        ? object.get(name).getAsString() : fallback;
  }

  private static String sanitize(String name) {
    String cleaned = name.replace('\\', '/');
    int slash = cleaned.lastIndexOf('/');
    if (slash >= 0) cleaned = cleaned.substring(slash + 1);
    cleaned = cleaned.replaceAll("[^A-Za-z0-9._ ()-]", "_");
    if (cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..")) cleaned = "mod.jar";
    if (!cleaned.toLowerCase().endsWith(".jar")) cleaned += ".jar";
    return cleaned;
  }

  private static Path uniqueTarget(Path directory, String name) {
    Path target = directory.resolve(name);
    if (!Files.exists(target)) return target;
    String base = name.substring(0, name.length() - 4);
    for (int i = 2; ; i++) {
      target = directory.resolve(base + "-" + i + ".jar");
      if (!Files.exists(target)) return target;
    }
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) return;
    try (var stream = Files.walk(root)) {
      for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
      }
    } catch (IOException ignored) {
    }
  }

  private static void usage(PrintStream out) {
    out.println("""
        Usage: java -jar gold-client-1.jar --ui [options]

        Starts a local web UI for creating a self-contained Eaglercraft client.

        Options:
          --port <port>   Port to listen on (default: 8080)
          --open          Open the UI in the default browser
          --help          Show this help
        """);
  }
}
