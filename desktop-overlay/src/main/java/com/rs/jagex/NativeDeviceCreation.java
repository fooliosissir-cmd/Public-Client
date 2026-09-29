package com.rs.jagex;

import java.util.function.LongSupplier;

/** Native drivers may return zero instead of throwing; zero must never reach dependent JNI calls. */
final class NativeDeviceCreation {
    /** One cleanup owner across factory, constructor, and post-construction native initialization. */
    static final class Ownership implements AutoCloseable {
        private Runnable cleanup;
        Ownership(Runnable cleanup) { this.cleanup = cleanup; }
        void adopt(Runnable cleanup) { this.cleanup = cleanup; }
        void commit() { cleanup = null; }
        @Override public void close() {
            Runnable owned = cleanup;
            cleanup = null;
            if (owned != null) owned.run();
        }
    }

    static void cleanupAll(Runnable... actions) {
        Throwable first = null;
        for (Runnable action : actions) try { action.run(); }
        catch (Throwable failure) {
            if (first == null) first = failure; else first.addSuppressed(failure);
        }
        if (first instanceof Error error) throw error;
        if (first instanceof RuntimeException error) throw error;
    }

    static long requireHandle(long handle) {
        if (handle == 0L) throw new IllegalStateException("Native graphics creation returned no handle");
        return handle;
    }

    static long createDevice(LongSupplier primary, LongSupplier fallback) {
        RuntimeException first = null;
        try {
            long handle = primary.getAsLong();
            if (handle != 0L) return handle;
        } catch (RuntimeException error) { first = error; }
        try { return requireHandle(fallback.getAsLong()); }
        catch (RuntimeException error) {
            if (first != null) error.addSuppressed(first);
            throw error;
        }
    }
}
