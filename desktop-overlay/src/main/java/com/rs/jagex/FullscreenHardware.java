package com.rs.jagex;

import java.util.Objects;
import java.util.function.IntFunction;

/** A display transition can retry another GPU backend, but cannot silently select software. */
final class FullscreenHardware {
    record Created<T>(int type, T renderer) { }
    static final class Failure extends RuntimeException {
        Failure(Throwable cause) { super("Hardware display transition failed", cause); }
    }
    static boolean hardware(int type) { return type == 1 || type == 3 || type == 5; }

    static <T> Created<T> create(int requested, IntFunction<T> factory) {
        if (!hardware(requested)) throw new IllegalArgumentException("Hardware renderer required");
        Throwable first;
        try { return new Created<>(requested, Objects.requireNonNull(factory.apply(requested))); }
        catch (RuntimeException | LinkageError failure) { first = failure; }
        int alternate = requested == 1 ? 3 : 1;
        try { return new Created<>(alternate, Objects.requireNonNull(factory.apply(alternate))); }
        catch (RuntimeException | LinkageError failure) {
            Failure result = new Failure(failure);
            result.addSuppressed(first);
            throw result;
        }
    }
}
