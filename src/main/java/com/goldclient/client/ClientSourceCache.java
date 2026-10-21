package com.goldclient.client;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Downloads and verifies the pinned Eaglercraft 1.12.2 client. */
public final class ClientSourceCache {
  public static final String CLIENT_URL =
      "https://github.com/Eaglercraft-Archive/unminified-eaglercraft-builds-1.12/"
          + "releases/download/u2/Eaglercraft_1.12_Offline_en_US.html";
  public static final String CLIENT_SHA256 =
      "B23DECDCA4FF9AF58F63EEA4E93772D8A0A1DCBB59781A32E505E9C9735D1E67";
  public static final long CLIENT_BYTES = 75_729_830L;

  public record CachedClient(Path path, String sha256, long bytes) {}

  private ClientSourceCache() {}

  public static CachedClient ensure(Path cacheDirectory, boolean refresh, PrintStream log)
      throws IOException, InterruptedException {
    Path cache = cacheDirectory.toAbsolutePath().normalize();
    Files.createDirectories(cache);
    Path cached = cache.resolve(CLIENT_SHA256 + ".html");
    if (!refresh && Files.isRegularFile(cached)) {
      if (matches(cached)) {
        log.println("Client cache: " + cached);
        return new CachedClient(cached, CLIENT_SHA256, Files.size(cached));
      }
      log.println("Client cache failed verification; downloading a fresh copy.");
      Files.deleteIfExists(cached);
    }

    Path temporary = Files.createTempFile(cache, "eaglercraft-1.12.2-", ".download");
    try {
      log.println("Downloading pinned Eaglercraft 1.12.2 client...");
      HttpClient client = HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(60))
          .build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(CLIENT_URL))
          .timeout(Duration.ofMinutes(10))
          .header("User-Agent", "GoldClient/1 (+https://github.com/anomalyco/opencode)")
          .GET()
          .build();
      HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
      if (response.statusCode() != 200) {
        throw new IOException("Client download returned HTTP " + response.statusCode());
      }
      long bytes = Files.size(temporary);
      if (bytes != CLIENT_BYTES) {
        throw new IOException("Client size mismatch: expected " + CLIENT_BYTES + ", got " + bytes);
      }
      String hash = sha256(temporary);
      if (!CLIENT_SHA256.equalsIgnoreCase(hash)) {
        throw new IOException("Client SHA-256 mismatch: expected " + CLIENT_SHA256 + ", got " + hash);
      }
      try {
        Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
      }
      log.println("Client downloaded: " + cached);
      return new CachedClient(cached, CLIENT_SHA256, bytes);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static boolean matches(Path file) throws IOException {
    return Files.size(file) == CLIENT_BYTES
        && CLIENT_SHA256.equalsIgnoreCase(sha256(file));
  }

  public static String sha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var stream = Files.newInputStream(file)) {
        byte[] buffer = new byte[1 << 20];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
          if (read > 0) digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().withUpperCase().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
