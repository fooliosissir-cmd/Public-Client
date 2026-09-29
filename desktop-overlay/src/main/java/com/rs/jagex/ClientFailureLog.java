package com.rs.jagex;

import com.rs.Loader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.IdentityHashMap;

/** Last-resort local diagnostics for javaw: deliberately excludes exception messages and user state. */
final class ClientFailureLog {
    enum Phase { STARTUP, MAIN_LOOP, SHUTDOWN }
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_RECORD_BYTES = 64 * 1024;
    private static final Object WRITE_LOCK = new Object();

    private ClientFailureLog() { }

    static void record(Phase phase, Throwable failure) {
        try {
            Class<?> renderer = null;
            UiScale.Geometry geometry = null;
            try {
                AbstractRenderer active = Renderers.CURRENT_RENDERER;
                if (active != null) renderer = active.getClass();
            } catch (Throwable ignored) { }
            try { geometry = UiScale.active(); } catch (Throwable ignored) { }
            String home = Class110.aString1103;
            if (home == null || home.isBlank()) {
                home = System.getProperty("user.home");
            }
            recordTo(Path.of(home, ".darkanrs"), phase, failure, renderer, geometry);
        } catch (Throwable ignored) {
            // Diagnostic failures must never replace the original fatal failure or alter shutdown.
        }
    }

    static void recordTo(Path directory, Phase phase, Throwable failure, Class<?> renderer, UiScale.Geometry geometry) {
        try {
            StringBuilder text = new StringBuilder();
            append(text, "\n" + Instant.now() + " phase=" + phase.name() + " build="
                    + Loader.CLIENT_BUILD + "/" + Loader.MAJOR_BUILD + "/" + Loader.MINOR_BUILD + "\n");
            append(text, "renderer=" + (renderer == null ? "unavailable" : identifier(renderer.getName())) + "\n");
            if (geometry != null)
                append(text, "logical=" + geometry.logicalWidth() + "x" + geometry.logicalHeight()
                        + " physical=" + geometry.physicalWidth() + "x" + geometry.physicalHeight() + "\n");
            IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
            int causes = 0;
            while (failure != null && causes++ < 16 && text.length() < MAX_RECORD_BYTES) {
                if (seen.put(failure, Boolean.TRUE) != null) {
                    append(text, "[cause cycle]\n");
                    break;
                }
                append(text, (causes == 1 ? "exception=" : "cause=") + identifier(failure.getClass().getName()) + "\n");
                StackTraceElement[] frames = failure.getStackTrace();
                int count = Math.min(64, frames.length);
                for (int i = 0; i < count && text.length() < MAX_RECORD_BYTES; i++) {
                    StackTraceElement frame = frames[i];
                    // Do not serialize file paths, Throwable.toString(), messages, locals, or login fields.
                    append(text, " at " + identifier(frame.getClassName()) + "." + identifier(frame.getMethodName())
                            + ":" + frame.getLineNumber() + (frame.isNativeMethod() ? " native" : "") + "\n");
                }
                if (frames.length > count) append(text, "[additional frames omitted]\n");
                failure = failure.getCause();
            }
            if (failure != null) append(text, "[remaining cause data omitted]\n");
            byte[] bytes = text.toString().getBytes(StandardCharsets.US_ASCII);
            synchronized (WRITE_LOCK) {
                Files.createDirectories(directory);
                Path log = directory.resolve("client-errors.log");
                if (Files.exists(log) && Files.size(log) + bytes.length > MAX_BYTES) {
                    // A pre-existing oversized file must not create an unbounded backup either.
                    if (Files.size(log) > MAX_BYTES) {
                        try (var channel = java.nio.channels.FileChannel.open(log, StandardOpenOption.WRITE)) {
                            channel.truncate(MAX_BYTES);
                        }
                    }
                    Files.move(log, directory.resolve("client-errors.log.1"), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.write(log, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Throwable ignored) { }
    }

    private static void append(StringBuilder target, String value) {
        int remaining = MAX_RECORD_BYTES - target.length();
        if (remaining > 0) target.append(value, 0, Math.min(remaining, value.length()));
    }

    private static String identifier(String value) {
        StringBuilder result = new StringBuilder(Math.min(256, value.length()));
        for (int i = 0; i < value.length() && i < 256; i++) {
            char c = value.charAt(i);
            result.append(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_' || c == '$' || c == '.' || c == '<' || c == '>' ? c : '?');
        }
        return result.toString();
    }
}
