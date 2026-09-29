package com.rs.jagex;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Objects;

/** Memory describes session-only confirmed logins, one-shot handoff between owned worlds. */
public final class ServiceLoginMemory implements AutoCloseable {
    public enum Service {
        DEVELOPMENT(1, "127.0.0.1", "Development"),
        LIVE(3, "201.79.51.16", "Live");
        private final int id;
        private final String host;
        private final String profileName;
        Service(int id, String host, String profileName) { this.id = id; this.host = host; this.profileName = profileName; }
        public int id() { return id; }
        public String host() { return host; }
        public int lobbyPort() { return 43594; }
        public String profileName() { return profileName; }
    }

    public static Service forId(int id) {
        for (Service service : Service.values()) if (service.id == id) return service;
        return null;
    }

    /** Only pinned lobby endpoints are accepted; game-world IDs and advertised hosts are not authorities. */
    public static Service serviceFor(String host, int port) {
        if (host == null || port != 43594) return null;
        if (host.equalsIgnoreCase("localhost")) return Service.DEVELOPMENT;
        for (Service service : Service.values()) if (service.host.equalsIgnoreCase(host)) return service;
        return null;
    }

    /** Opaque identity bound to one helper, service, generation, and attempt; contains no credentials. */
    public static final class Attempt {
        private final Service service;
        private final long generation;
        private Attempt(Service service, long generation) { this.service = service; this.generation = generation; }
        public Service service() { return service; }
        public long generation() { return generation; }
        @Override public String toString() { return "LoginAttempt[" + service + ", generation=" + generation + "]"; }
    }

    /** Caller owns each returned password copy and must wipe it after the native login adapter consumes it. */
    public static final class CredentialSnapshot implements AutoCloseable {
        private final Service service;
        private final String username;
        private final char[] password;
        private boolean closed;
        private CredentialSnapshot(Service service, String username, char[] password) {
            this.service = service;
            this.username = username;
            this.password = password.clone();
        }
        public Service service() { return service; }
        public String username() { return username; }
        public synchronized char[] copyPassword() {
            if (closed) throw new IllegalStateException("Credential snapshot is closed");
            return password.clone();
        }
        @Override public synchronized void close() {
            Arrays.fill(password, '\0');
            closed = true;
        }
        @Override public String toString() { return "CredentialSnapshot[" + service + ", redacted]"; }
    }

    private static final class Credentials {
        final String username;
        final char[] password;
        Credentials(String username, char[] password) { this.username = username; this.password = password.clone(); }
        void wipe() { Arrays.fill(password, '\0'); }
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    private final EnumMap<Service, Credentials> remembered = new EnumMap<>(Service.class);
    private Service currentService;
    private long generation;
    private Attempt pendingAttempt;
    private Credentials pending;
    private boolean restoreTaken;
    private Credentials fallback;
    private boolean closed;

    public ServiceLoginMemory(Service initialService) { currentService = Objects.requireNonNull(initialService); }
    public synchronized Service currentService() { return currentService; }
    public synchronized long generation() { return generation; }

    /** Call before changing connection destinations. Even a same-service transition fences old callbacks.
     * <p>
     * If the target service has no remembered credentials, a defensive one-shot copy of the confirmed
     * SOURCE credentials is offered as a fallback for that committed generation. The fallback is wiped
     * on switch, takeRestore, clear(current target), and close.
     */
    public synchronized long switchTo(Service target) {
        ensureOpen();
        Objects.requireNonNull(target);
        long next = Math.incrementExact(generation);
        wipePending();
        
        // Wipe existing fallback
        if (fallback != null) {
            fallback.wipe();
            fallback = null;
        }
        
        // If target has no remembered credentials, offer a defensive one-shot copy of confirmed SOURCE credentials
        if (!remembered.containsKey(target) && currentService != null && currentService != target) {
            Credentials sourceCreds = remembered.get(currentService);
            if (sourceCreds != null) {
                fallback = new Credentials(sourceCreds.username, sourceCreds.password);
            }
        }
        
        currentService = target;
        generation = next;
        restoreTaken = false;
        return generation;
    }

    /** Username normalization belongs to the native adapter. Stale submissions are rejected without mutation. */
    public synchronized Attempt beginAttempt(Service service, long expectedGeneration, String normalizedUsername, char[] password) {
        ensureOpen();
        if (!matches(service, expectedGeneration)) return null;
        if (normalizedUsername == null || normalizedUsername.isBlank() || normalizedUsername.length() > 320
                || password == null || password.length == 0)
            throw new IllegalArgumentException("Nonempty login credentials required");
        Credentials replacement = new Credentials(normalizedUsername, password);
        wipePending();
        pendingAttempt = new Attempt(service, generation);
        pending = replacement;
        return pendingAttempt;
    }

    /** Only a matching successful authentication commits. Failure preserves the last confirmed manual prefill. */
    public synchronized boolean complete(Attempt attempt, boolean success) {
        if (closed || attempt == null || attempt != pendingAttempt || !matches(attempt.service, attempt.generation)) return false;
        if (success) {
            Credentials replacement = new Credentials(pending.username, pending.password);
            Credentials previous = remembered.put(attempt.service, replacement);
            if (previous != null) previous.wipe();
        }
        wipePending();
        return true;
    }

public synchronized CredentialSnapshot takeRestore(Service service, long expectedGeneration) { if (closed || !matches(service, expectedGeneration) || restoreTaken) { return null; } restoreTaken = true; try { Credentials entry = remembered.get(service); if (entry == null) { entry = fallback; } if (entry == null) { return null; } return new CredentialSnapshot(service, entry.username, entry.password); } finally { if (fallback != null) { fallback.wipe(); fallback = null; } } }

    /** Defensive snapshot for deliberate manual prefill; this does not authorize another automatic attempt. */
    public synchronized CredentialSnapshot snapshot(Service service) {
        if (closed || service == null) return null;
        Credentials entry = remembered.get(service);
        return entry == null ? null : new CredentialSnapshot(service, entry.username, entry.password);
    }

    public synchronized void clear(Service service) {
        Credentials entry = remembered.remove(Objects.requireNonNull(service));
        if (entry != null) entry.wipe();
        if (pendingAttempt != null && pendingAttempt.service == service) wipePending();
        
        // Wipe fallback on clear(current target)
        if (service == currentService && fallback != null) {
            fallback.wipe();
            fallback = null;
        }
    }

    @Override public synchronized void close() {
        wipePending();
        for (Credentials entry : remembered.values()) entry.wipe();
        remembered.clear();
        if (fallback != null) {
            fallback.wipe();
            fallback = null;
        }
        closed = true;
    }

    private boolean matches(Service service, long expectedGeneration) {
        return service != null && service == currentService && expectedGeneration == generation;
    }
    private void wipePending() {
        if (pending != null) pending.wipe();
        pending = null;
        pendingAttempt = null;
    }
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Login memory is closed");
    }
    @Override public String toString() { return "ServiceLoginMemory[redacted]"; }
}
