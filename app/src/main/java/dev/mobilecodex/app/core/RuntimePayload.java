package dev.mobilecodex.app.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Extracts trusted, checksum-pinned runtime data into a new, private staging directory. */
public final class RuntimePayload {
    private RuntimePayload() {}
    public static Path resolve(Path root, String relative) throws IOException {
        if (relative.isEmpty() || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0 || relative.startsWith("/"))
            throw new IOException("Invalid runtime path");
        for (String part : relative.split("/")) if (part.equals("..") || part.equals(".")) throw new IOException("Invalid runtime path");
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root.normalize()) || result.equals(root.normalize())) throw new IOException("Runtime path escapes root");
        return result;
    }
    public static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    public static String sha256(byte[] bytes) throws IOException {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    public static void extract(InputStream source, Path destination, String expectedHash) throws IOException {
        if (!expectedHash.matches("[a-f0-9]{64}")) throw new IOException("Invalid runtime checksum");
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            // Digest the complete ZIP, including the central directory, before extracting.
            Path zip = destination.resolveSibling(destination.getFileName() + ".zip");
            try {
                try (InputStream in = new DigestInputStream(source, hash); OutputStream out = Files.newOutputStream(zip, StandardOpenOption.CREATE_NEW)) {
                    byte[] block = new byte[65536]; int n; long count = 0;
                    while ((n = in.read(block)) != -1) { count += n; if (count > 512L * 1024 * 1024) throw new IOException("Runtime archive too large"); out.write(block, 0, n); }
                }
                if (!hex(hash.digest()).equals(expectedHash)) throw new IOException("Runtime checksum mismatch");
                Files.createDirectory(destination);
                long total = 0; int entries = 0; Set<Path> seen = new HashSet<>();
                try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
                    ZipEntry entry; byte[] block = new byte[65536];
                    while ((entry = in.getNextEntry()) != null) {
                        if (++entries > 60000) throw new IOException("Too many runtime files");
                        Path target = resolve(destination, entry.getName());
                        if (!seen.add(target)) throw new IOException("Duplicate runtime file");
                        if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                        Files.createDirectories(target.getParent());
                        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                            int n;
                            while ((n = in.read(block)) != -1) { total += n; if (total > 1024L * 1024 * 1024) throw new IOException("Runtime payload too large"); out.write(block, 0, n); }
                        }
                    }
                }
                if (entries == 0) throw new IOException("Empty runtime archive");
            } finally { Files.deleteIfExists(zip); }
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
