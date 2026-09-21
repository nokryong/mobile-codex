package dev.mobilecodex.app.core;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
public final class Utf8Files {
    private Utf8Files() {}
    public static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    public static void write(Path path, String value) throws IOException { Files.write(path, value.getBytes(StandardCharsets.UTF_8)); }
}
