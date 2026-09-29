package com.rs.jagex;

import com.rs.Loader;
import java.util.Arrays;
import java.util.Objects;

/** Game-thread adapter. Holds only confirmed session credentials; never persists or logs them. */
public final class ServiceLoginAdapter implements AutoCloseable {
    public enum RestoreResult { NOT_READY, MANUAL_LOGIN, SUBMITTED, REJECTED }
    @FunctionalInterface public interface FreshSubmitter {
        /** Consume synchronously; the supplied password buffer is erased before this call returns. */
        boolean submit(String accountLogin, char[] password);
    }

    private final ServiceLoginMemory memory;
    private ServiceLoginMemory.Attempt pending;
    private Object pendingConnection;
    private boolean restoreArmed;
    private boolean suspended;
    private boolean closed;
    private boolean targetSubmissionStarted;
    private static ServiceLoginAdapter nativeAdapter;

    public ServiceLoginAdapter(ServiceLoginMemory.Service initialService) {
        memory = new ServiceLoginMemory(initialService);
    }

    public long generation() { return memory.generation(); }
    public ServiceLoginMemory.Service service() { return memory.currentService(); }

    /** The exact account/email submitted by the native login screen, never a display name. */
    public boolean submitted(ServiceLoginMemory.Service service, String accountLogin, char[] password) {
        if (closed || suspended || service != service()) return false;
        discardPending();
        if (accountLogin == null || accountLogin.isBlank() || accountLogin.length() > 320
                || password == null || password.length == 0) return false;
        pending = memory.beginAttempt(service, generation(), accountLogin, password);
        if (pending != null) targetSubmissionStarted = true;
        return pending != null;
    }

    /** Bind each native retry to its newly opened transport, not the reused connection context. */
    public boolean bindConnection(ServiceLoginMemory.Service service, Object connection) {
        if (closed || suspended || pending == null || service != service() || connection == null) return false;
        pendingConnection = connection;
        return true;
    }

    /** Call only after the complete authoritative normal lobby-auth response was parsed. */
    public boolean authenticated(ServiceLoginMemory.Service service, Object connection) {
        if (closed || suspended || pending == null || service != service() || connection == null
                || connection != pendingConnection) return false;
        boolean committed = memory.complete(pending, true);
        pending = null;
        pendingConnection = null;
        return committed;
    }

    /** Failure/cancellation clears pending secrets, retaining only previously confirmed credentials. */
    public void terminal() { if (!closed) discardPending(); }

    /**
     * Coordinator-only commit boundary, after source users are stopped and before target publication.
     * The owner MUST clear native auth/token/connection/parser state in resetNativeAuthentication.
     * A failed reset leaves restoration and capture suspended; do not continue into targetReady.
     */
    public long switchCommitted(ServiceLoginMemory.Service target, Runnable resetNativeAuthentication) {
        if (closed) throw new IllegalStateException("Login adapter is closed");
        Objects.requireNonNull(target);
        Objects.requireNonNull(resetNativeAuthentication);
        suspended = true;
        restoreArmed = false;
        discardPending();
        long generation = memory.switchTo(target);
        resetNativeAuthentication.run();
        targetSubmissionStarted = false;
        suspended = false;
        restoreArmed = true;
        return generation;
    }

    static boolean nativeRetryAllowed(ServiceLoginMemory.Service target, long generation) {
        ServiceLoginAdapter adapter = nativeAdapter;
        return adapter != null && !adapter.closed && !adapter.suspended && adapter.restoreArmed
                && !adapter.targetSubmissionStarted && adapter.pending == null && adapter.pendingConnection == null
                && adapter.service() == target && adapter.generation() == generation;
    }

    /** Never called by startup. Offers at most one fresh native submission per committed generation. */
    public RestoreResult restoreOnce(ServiceLoginMemory.Service target, long generation, boolean nativeReady,
                                     FreshSubmitter submitter) {
        Objects.requireNonNull(submitter);
        if (closed || suspended || target != service() || generation != generation() || !nativeReady)
            return RestoreResult.NOT_READY;
        if (!restoreArmed) return RestoreResult.MANUAL_LOGIN;
        restoreArmed = false;
        try (ServiceLoginMemory.CredentialSnapshot snapshot = memory.takeRestore(target, generation)) {
            if (snapshot == null) return RestoreResult.MANUAL_LOGIN;
            char[] password = snapshot.copyPassword();
            try {
                // Native Class155 also captures accepted submission; replacement erases this copy.
                submitted(target, snapshot.username(), password);
                if (submitter.submit(snapshot.username(), password)) return RestoreResult.SUBMITTED;
                discardPending();
                return RestoreResult.REJECTED;
            } catch (RuntimeException failure) {
                discardPending();
                throw failure;
            } finally { Arrays.fill(password, '\0'); }
        }
    }

    private void discardPending() {
        if (pending != null) memory.complete(pending, false);
        pending = null;
        pendingConnection = null;
    }

    @Override public void close() {
        discardPending();
        memory.close();
        restoreArmed = false;
        closed = true;
    }
    @Override public String toString() { return "ServiceLoginAdapter[redacted]"; }

    private static ServiceLoginMemory.Service nativeService() {
        ConnectionInfo lobby = ConnectionInfo.LOBBY_CONNECTION_INFO;
        return lobby == null ? null : ServiceLoginMemory.serviceFor(lobby.host, Loader.getPort(lobby.worldId));
    }
    private static ServiceLoginAdapter nativeFor(ServiceLoginMemory.Service service) {
        if (service == null) return null;
        if (nativeAdapter == null) nativeAdapter = new ServiceLoginAdapter(service);
        return nativeAdapter;
    }

    static void captureSubmittedNative(String accountLogin, String password) {
        ServiceLoginMemory.Service service = nativeService();
        ServiceLoginAdapter adapter = nativeFor(service);
        if (adapter == null) return;
        // The cached/social path does not authenticate the submitted account/password.
        if (client.aByteArray7152 != null) { adapter.terminal(); return; }
        char[] chars = password == null ? null : password.toCharArray();
        try { adapter.submitted(service, accountLogin, chars); }
        finally { if (chars != null) Arrays.fill(chars, '\0'); }
    }
    static void bindNativeConnection() {
        if (nativeAdapter != null && Login.getLobbyStage() == LobbyStage.LOGGING_IN_LOBBY
                && Class9.CURRENT_CONNECTION_CONTEXT == client.LOBBY_CONNECTION_CONTEXT && !Class9.socialNetworkLogin)
            nativeAdapter.bindConnection(nativeService(), Class9.CURRENT_CONNECTION_CONTEXT.getConnection());
    }
    static void nativeLobbyAuthenticated() {
        if (nativeAdapter != null && Login.getLobbyStage() == LobbyStage.LOGGING_IN_LOBBY
                && Class9.CURRENT_CONNECTION_CONTEXT == client.LOBBY_CONNECTION_CONTEXT && !Class9.socialNetworkLogin
                && client.aByteArray7152 == null && Class9.aLong77 == -1L)
            nativeAdapter.authenticated(nativeService(), Class9.CURRENT_CONNECTION_CONTEXT.getConnection());
    }
    static void nativeTerminal() { if (nativeAdapter != null) nativeAdapter.terminal(); }

    /** Unhooked until the switching coordinator owns the full native-auth reset inventory. */
    public static long committedServiceSwitch(ServiceLoginMemory.Service target, Runnable resetNativeAuthentication) {
        ServiceLoginAdapter adapter = nativeFor(nativeService());
        if (adapter == null) throw new IllegalStateException("Unknown current authentication service");
        return adapter.switchCommitted(target, resetNativeAuthentication);
    }

    /** Coordinator calls only after target UNK_5 and complete native reset. Never reuses a session token. */
    public static RestoreResult targetReady(ServiceLoginMemory.Service target, long generation) {
        if (nativeAdapter == null) return RestoreResult.NOT_READY;
        boolean ready = target == nativeService() && Class388.method6693()
                && client.aByteArray7152 == null && Class9.aLong77 == -1L && Class9.anInt76 == -1
                && !Class9.socialNetworkLogin && (Static.LOBBY_AUTH_TOKEN == null || Static.LOBBY_AUTH_TOKEN.isEmpty())
                && client.LOBBY_CONNECTION_CONTEXT.getConnection() == null
                && client.GAME_CONNECTION_CONTEXT.getConnection() == null;
        return nativeAdapter.restoreOnce(target, generation, ready, (accountLogin, password) -> {
            Class155.method2635(accountLogin, new String(password));
            return client.GAME_STATE == GameState.UNK_14;
        });
    }

    /** Optional lifecycle hook for final client shutdown; no remembered credentials survive this call. */
    public static void clearNativeMemory() {
        if (nativeAdapter != null) nativeAdapter.close();
        nativeAdapter = null;
    }
}
