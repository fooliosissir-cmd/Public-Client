package com.rs.jagex;

import com.rs.Loader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/** Game-thread coordinator. Native selector changes services without replacing the JVM. */
public final class ServiceSwitchCoordinator {
    public enum Status { IDLE, REQUESTED, PREFLIGHT, QUIESCING, COMMITTING, RELOADING, READY, CANCELLED, SOURCE_FAILED, TARGET_FAILED }
    public enum Error { NONE, PREFLIGHT, SOURCE_CHANGED, PREPARATION, QUIESCE_TIMEOUT, COMMIT, TARGET_LOADING, TARGET_AUTHENTICATION }
    /** Safe for UI/QA: no raw exceptions, token, credentials or remotely supplied text. */
    public record View(Status status, Error error, int targetId, long cacheGeneration,
                       long authenticationGeneration, boolean targetReady, boolean held) { }
    private static final long DEADLINE_NANOS = 3_000_000_000L;
    private static ServiceSwitchCoordinator instance = new ServiceSwitchCoordinator(new Operations(),
            Executors.newSingleThreadExecutor(job -> {
                Thread worker = new Thread(job, "service-switch-preflight"); worker.setDaemon(true); return worker;
            }), System::nanoTime);
    private final Operations operations;
    private final Executor executor;
    private final LongSupplier clock;
    private Thread owner;
    private client engine;
    private long generation, deadline;
    private Status state = Status.IDLE;
    private Error error = Error.NONE;
    private Throwable diagnosticFailure;
    private boolean sourceBlocked, restoreAttempted, pendingAutoJoin;
    private long autoJoinDeadline;
    private ServiceLoginMemory.Service target;
    private ServiceLoginMemory.Service requestedSource;
    private Path profilesRoot;
    private Source source;
    private CompletableFuture<Completion> pending;
    private ServiceSwitchPreflight.Result proof;
    private JS5Manager targetManager;
    private CacheProfileLifecycle.Prepared cache;
    private ServiceSwitchQuiescence quiescence;
    private ServiceSwitchCommit handoff;
    private Runnable resetAuthentication;
    private volatile View view = new View(Status.IDLE, Error.NONE, 0, 0, -1, false, false);
    private record Completion(long generation, ServiceSwitchPreflight.Result proof, boolean failed) { }

    ServiceSwitchCoordinator(Operations operations, Executor executor, LongSupplier clock) {
        this.operations = Objects.requireNonNull(operations);
        this.executor = Objects.requireNonNull(executor); this.clock = Objects.requireNonNull(clock);
    }

    public static boolean request(int targetId) { return request(targetId, defaultProfilesRoot()); }
    /** Call on the established client game thread; isolated QA may supply its own profile root. */
    public static boolean request(int targetId, Path profilesRoot) { return instance.start(targetId, profilesRoot); }
    public static boolean cancel() { return instance.cancelBeforeCommit(); }
    public static View status() { return instance.view; }
    static Throwable diagnosticFailure() { instance.requireOwner(); return instance.diagnosticFailure; }
    static ServiceSwitchCommit receipt() { instance.requireOwner(); return instance.handoff; }
    static boolean frameHeld() {
        return instance.view.held() || ServiceSwitchQuiescence.held()
                || ServiceSwitchCommit.holdsFrame() || NativeLoadingReload.holdsFrame();
    }

    /** First call binds ownership through the actual client pulse, never through an external request. */
    static boolean pulse(client engine) { return instance.tick(engine); }
    boolean tick(client candidate) {
        if (owner == null) {
            if (candidate != Class308.aclient3620) throw new IllegalStateException("Active client loop required");
            owner = Thread.currentThread(); engine = candidate;
        }
        requireOwner();
        if (candidate != engine) throw new IllegalStateException("Client owner changed");
        boolean consumed = ownHold();
        try {
            if (state == Status.REQUESTED) {
                if (!stable(true) || requestedSource != ServiceLoginMemory.serviceFor(Loader.IP_ADDRESS, Loader.LOBBY_PORT))
                    abort(Error.SOURCE_CHANGED, Status.SOURCE_FAILED);
                else startProbe();
            } else if (state == Status.PREFLIGHT) {
                if (!source.matches() || !stable(true)) abort(Error.SOURCE_CHANGED, Status.SOURCE_FAILED);
                else if (clock.getAsLong() >= deadline) abort(Error.PREFLIGHT, Status.SOURCE_FAILED);
                else if (pending.isDone()) {
                    Completion result = pending.join();
                    if (result.generation() == generation) {
                        if (result.failed() || result.proof() == null) abort(Error.PREFLIGHT, Status.SOURCE_FAILED);
                        else { prepare(result.proof()); consumed = true; }
                    }
                }
            } else if (state == Status.QUIESCING) {
                if (!source.matches() || !stable(true)) abort(Error.SOURCE_CHANGED, Status.SOURCE_FAILED);
                else if (clock.getAsLong() >= deadline) abort(Error.QUIESCE_TIMEOUT, Status.SOURCE_FAILED);
                else if (quiescence.poll(() -> operations.pump(engine, source.manager))) commit();
            } else if (state == Status.RELOADING) {
                NativeLoadingReload reload = handoff.reload();
                if (reload.status() == NativeLoadingReload.Status.FAILED) failTarget(Error.TARGET_LOADING);
                else {
                    requireTarget();
                    // Both network and manager can throw before the native stage catch: keep them inside our gate.
                    operations.pump(engine, targetManager);
                    if (reload.status() != NativeLoadingReload.Status.FAILED) operations.load();
                    if (reload.status() == NativeLoadingReload.Status.FAILED) failTarget(Error.TARGET_LOADING);
                    else if (reload.status() == NativeLoadingReload.Status.READY) ready();
                }
            } else if (state == Status.READY && pendingAutoJoin) {
                if (clock.getAsLong() >= autoJoinDeadline || client.GAME_STATE == GameState.UNK_5) {
                    pendingAutoJoin = false;
                } else if (client.GAME_STATE == GameState.UNK_0 && Login.getLoginStage() == LoginStage.NONE_2) {
                    ServiceLoginMemory.Service current = ServiceLoginMemory.serviceFor(Loader.IP_ADDRESS, Loader.LOBBY_PORT);
                    if (current == target) {
                        pendingAutoJoin = false;
                        operations.joinWorld(target);
                    } else {
                        pendingAutoJoin = false;
                    }
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            diagnosticFailure = failure;
            if (cache != null && cache.committed()) failTarget(Error.TARGET_LOADING);
            else abort(state == Status.PREFLIGHT ? Error.PREPARATION : Error.SOURCE_CHANGED, Status.SOURCE_FAILED);
        }
        publishView();
        // Bare quiescence/commit participants also suppress ordinary producers, but have no automatic pump.
        return consumed || ownHold() || ServiceSwitchQuiescence.held() || ServiceSwitchCommit.holdsFrame();
    }

    boolean start(int targetId, Path root) {
        requireOwner();
        ServiceLoginMemory.Service selected = ServiceLoginMemory.forId(targetId);
        if (selected == null || active() || frameHeld() || !stable(false)
                || (pending != null && !pending.isDone())) return false;
        ServiceLoginMemory.Service from = ServiceLoginMemory.serviceFor(Loader.IP_ADDRESS, Loader.LOBBY_PORT);
        if (from == null || from == selected || root == null) return false;
        source = null; requestedSource = from; target = selected; profilesRoot = root.toAbsolutePath().normalize();
        cache = null; quiescence = null; handoff = null; targetManager = null; proof = null; resetAuthentication = null;
        sourceBlocked = restoreAttempted = pendingAutoJoin = false; error = Error.NONE; diagnosticFailure = null; state = Status.REQUESTED;
        ++generation;
        publishView(); return true;
    }

    private void startProbe() {
        Source snapshot = new Source(requestedSource);
        if (client.aString7164 == null || client.aString7164.isEmpty() || client.aString7164.length() > 128)
            throw new IllegalStateException("JS5 token unavailable");
        byte[] token = client.aString7164.getBytes(StandardCharsets.ISO_8859_1);
        source = snapshot;
        ServiceLoginMemory.Service selected = target;
        state = Status.PREFLIGHT;
        long ticket = generation;
        deadline = clock.getAsLong() + DEADLINE_NANOS;
        byte[] nativeIdentity = source.nativeIdentity.clone();
        CompletableFuture<Completion> result = new CompletableFuture<>(); pending = result;
        try {
            executor.execute(() -> {
                Completion completion;
                try { completion = new Completion(ticket, operations.probe(snapshot.service, selected, nativeIdentity, token), false); }
                catch (Exception | LinkageError failure) { completion = new Completion(ticket, null, true); }
                finally { Arrays.fill(token, (byte) 0); Arrays.fill(nativeIdentity, (byte) 0); }
                result.complete(completion); // Immutable receipt only; never publish client globals from the worker.
            });
        } catch (RuntimeException failure) {
            Arrays.fill(token, (byte) 0); Arrays.fill(nativeIdentity, (byte) 0);
            result.complete(new Completion(ticket, null, true));
            abort(Error.PREFLIGHT, Status.SOURCE_FAILED);
        }
        publishView();
    }

    boolean cancelBeforeCommit() {
        requireOwner();
        if (state != Status.REQUESTED && state != Status.PREFLIGHT && state != Status.QUIESCING) return false;
        ++generation; // Late background results cannot reenter preparation.
        abort(Error.NONE, Status.CANCELLED); publishView(); return true;
    }

    private void prepare(ServiceSwitchPreflight.Result result) {
        if (result.source() != source.service || result.target() != target || !result.nativeCompatible()
                || !Arrays.equals(result.sourceNativeIdentity(), source.nativeIdentity))
            throw new IllegalStateException("Stale preflight result");
        proof = result;
        targetManager = operations.manager(result, source.disk);
        try {
            cache = CacheProfileLifecycle.prepareShared(profilesRoot, client.CURRENT_GAME.name,
                    source.directory.getName(), result.indexCount());
        } catch (java.io.IOException failure) { throw new IllegalStateException("Target profile unavailable", failure); }
        operations.saveSource(); // Engine and Class110 still refer to the source profile here.
        resetAuthentication = operations.prepareReset();
        quiescence = ServiceSwitchQuiescence.begin(generation, targetManager);
        state = Status.QUIESCING; deadline = clock.getAsLong() + DEADLINE_NANOS;
        publishView();
    }

    private void commit() {
        state = Status.COMMITTING; publishView();
        handoff = ServiceSwitchCommit.begin(generation, proof, cache, quiescence, resetAuthentication,
                new MapRegion(true), operations.foreground());
        if (cache.committed()) clearSourceWorldMetadata();
        if (handoff.status() == ServiceSwitchCommit.Status.RELOADING) state = Status.RELOADING;
        else if (cache.committed()) failTarget(Error.COMMIT);
        else {
            sourceBlocked = handoff.status() != ServiceSwitchCommit.Status.SOURCE_RESUMED;
            state = Status.SOURCE_FAILED; error = Error.COMMIT;
        }
    }

    private void ready() {
        if (client.GAME_STATE != GameState.UNK_5 || restoreAttempted) throw new IllegalStateException("Target login page not ready");
        requireTarget(); restoreAttempted = true;
        try {
            ServiceLoginAdapter.RestoreResult restored = operations.restore(target, handoff.authenticationGeneration());
            if (restored == ServiceLoginAdapter.RestoreResult.NOT_READY) failTarget(Error.TARGET_AUTHENTICATION);
            else {
                state = Status.READY;
                if (restored == ServiceLoginAdapter.RestoreResult.SUBMITTED) {
                    pendingAutoJoin = true;
                    autoJoinDeadline = clock.getAsLong() + 30_000_000_000L;
                }
            }
        } catch (RuntimeException | LinkageError failure) { failTarget(Error.TARGET_AUTHENTICATION); }
    }

    private void requireTarget() {
        if (!cache.ownsActiveCache(handoff.cacheGeneration()) || ScreenSizePreference.JS5_MANAGER != targetManager
                || Whirlpool.JS5_LOCAL_REQUESTER != source.disk || IndexLoaders.MAP_REGION_LOADER_THREAD != source.maps
                || ServiceLoginMemory.serviceFor(Loader.IP_ADDRESS, Loader.LOBBY_PORT) != target
                || ConnectionInfo.LOBBY_CONNECTION_INFO == null || ConnectionInfo.JS5_CONNECTION_INFO == null
                || !target.host().equals(ConnectionInfo.LOBBY_CONNECTION_INFO.host)
                || !target.host().equals(ConnectionInfo.JS5_CONNECTION_INFO.host))
            throw new IllegalStateException("Target ownership changed");
    }

    private void abort(Error reason, Status terminal) {
        error = reason;
        if (cache != null && cache.committed()) { failTarget(reason); return; }
        try { if (quiescence != null) quiescence.abort(); }
        catch (RuntimeException | LinkageError failure) { sourceBlocked = true; }
        try { if (cache != null) cache.close(); }
        catch (java.io.IOException | RuntimeException failure) { error = Error.PREPARATION; }
        state = sourceBlocked ? Status.SOURCE_FAILED : terminal;
    }

    private void failTarget(Error reason) {
        state = Status.TARGET_FAILED; error = reason;
        // A native READY/failed gate may not exist: this coordinator independently retains the target frame.
    }
    private boolean active() { return state == Status.REQUESTED || state == Status.PREFLIGHT || ownHold(); }
    private boolean ownHold() {
        return sourceBlocked || state == Status.QUIESCING || state == Status.COMMITTING
                || state == Status.RELOADING || state == Status.TARGET_FAILED;
    }
    private void publishView() {
        Status previous = view.status();
        view = new View(state, error, target == null ? 0 : target.id(), generation,
                handoff == null ? -1 : handoff.authenticationGeneration(), state == Status.READY, ownHold());
        if (state != previous) operations.showStatus(view);
    }
    private void requireOwner() {
        if (owner == null || Thread.currentThread() != owner) throw new IllegalStateException("Established client game thread required");
    }
    private static boolean stable(boolean noScripts) {
        return (client.GAME_STATE == GameState.UNK_0 || client.GAME_STATE == GameState.UNK_5)
                && Login.getLoginStage() == LoginStage.NONE_2 && Class192.ACCOUNT_CREATION_STAGE == null
                && (!noScripts || CS2Executor.CURRENT_CS2_EXEC_IDX == 0);
    }
    private static Path defaultProfilesRoot() {
        String local = System.getenv("LOCALAPPDATA");
        return local == null ? Path.of(System.getProperty("user.home"), ".burialgrounds") : Path.of(local, "BurialGrounds");
    }

    private static void clearSourceWorldMetadata() {
        Class244.aBool3007 = false; Class244.WORLD_LIST_DESCRIPTORS = new WorldDescriptor[0];
        Class485.WORLD_LIST_START = 0; Class244.WORLD_LIST_SIZEPLUS1 = -1; Class354.WORLDS = new WorldType[0];
        Class4.WORLD_LIST_SIZE = MapSpriteDefinitions.WORLD_LIST_IDK = 0;
        CutsceneAction_Sub20.WORLD_LIST_BUFFER = null;
        ConnectionInfo.CURRENT_WORLD = ConnectionInfo.currentWorldPingIdx = 0;
        ConnectionInfo.CURRENT_WORLD_PING_REQUEST = null; ConnectionInfo.PING_WORLDS = false;
        ConnectionInfo.aBool5422 = ConnectionInfo.aBool5428 = false; ConnectionInfo.aLong5425 = 0;
        ConnectionInfo.aClass450_5429 = ConnectionInfo.NEWS_CONNECTION_INFO = null;
        BurialGroundsWorlds.installDisplayList(); // Rebuild both pinned entries with no source counts/ports/flags.
    }

    /** Narrow hardware/network seams for deterministic offline checks; production uses these native operations. */
    static class Operations {
        void showStatus(View state) {
            if (Loader.INSTANCE == null || Loader.INSTANCE.clientFrame == null || !Loader.INSTANCE.clientFrame.isShowing()) return;
            boolean failure = state.status() == Status.SOURCE_FAILED || state.status() == Status.TARGET_FAILED;
            boolean busy = state.status() == Status.PREFLIGHT || state.status() == Status.QUIESCING
                    || state.status() == Status.COMMITTING || state.status() == Status.RELOADING;
            javax.swing.SwingUtilities.invokeLater(() -> {
                Loader.INSTANCE.clientFrame.setTitle(busy ? "Burial Grounds � Switching world�" : "Burial Grounds");
                if (failure) javax.swing.JOptionPane.showMessageDialog(Loader.INSTANCE.clientFrame,
                        state.status() == Status.TARGET_FAILED
                        ? "The destination could not finish loading. Your cache is preserved. Please reopen the client."
                        : "That world is unavailable or incompatible. Your current world remains selected.",
                        "World unavailable", javax.swing.JOptionPane.ERROR_MESSAGE);
            });
        }
        ServiceSwitchPreflight.Result probe(ServiceLoginMemory.Service from, ServiceLoginMemory.Service to,
                                             byte[] identity, byte[] token) throws Exception {
            return ServiceSwitchPreflight.probe(from, to, identity, token);
        }
        JS5Manager manager(ServiceSwitchPreflight.Result result, JS5LocalRequester disk) {
            try { return result.createTargetManager(new JS5StandardRequester_Sub1(), disk); }
            catch (ServiceSwitchPreflight.Failure failure) { throw new IllegalStateException("Validated target unavailable", failure); }
        }
        void saveSource() { if (client.NEEDS_VARC_SAVE) ClanSetting.saveVarcsToFile(); Class190.savePreferences(); }
        Runnable prepareReset() { return NativeAuthenticationReset.prepare(); }
        MapRegion foreground() { return new MapRegion(false); }
        void pump(client engine, JS5Manager manager) { engine.method11622(); manager.pulse(); }
        void load() { NativeLoadingReload.pulseOnlyLoading(); }
        ServiceLoginAdapter.RestoreResult restore(ServiceLoginMemory.Service service, long generation) {
            return ServiceLoginAdapter.targetReady(service, generation);
        }
        void joinWorld(ServiceLoginMemory.Service service) {
            Class62.setGameHost(service.id(), service.host());
            Class466.method7777();
        }
    }

    private static final class Source {
        final ServiceLoginMemory.Service service;
        final JS5Manager manager = Objects.requireNonNull(ScreenSizePreference.JS5_MANAGER);
        final MapRegionLoaderTask maps = Objects.requireNonNull(IndexLoaders.MAP_REGION_LOADER_THREAD);
        final JS5LocalRequester disk = Objects.requireNonNull(Whirlpool.JS5_LOCAL_REQUESTER);
        final UID192 data = Engine.aClass440_3270, master = Engine.aClass440_3271;
        final UID192[] indices = Class97.aClass440Array996, entries = indices.clone();
        final File directory = Objects.requireNonNull(Engine.aFile3264);
        final String profile = Class110.aString1103;
        final byte[] nativeIdentity = ReloadResourceSession.nativeIdentity(manager), table = manager.buffer.buffer.clone();
        final Connection lobby = client.LOBBY_CONNECTION_CONTEXT.getConnection(), game = client.GAME_CONNECTION_CONTEXT.getConnection();
        Source(ServiceLoginMemory.Service service) { this.service = service; }
        boolean matches() {
            return manager == ScreenSizePreference.JS5_MANAGER && maps == IndexLoaders.MAP_REGION_LOADER_THREAD
                    && disk == Whirlpool.JS5_LOCAL_REQUESTER && manager.localRequester == disk
                    && data == Engine.aClass440_3270 && master == Engine.aClass440_3271
                    && indices == Class97.aClass440Array996 && Arrays.equals(entries, indices)
                    && directory.equals(Engine.aFile3264) && Objects.equals(profile, Class110.aString1103)
                    && Arrays.equals(table, manager.buffer.buffer)
                    && service == ServiceLoginMemory.serviceFor(Loader.IP_ADDRESS, Loader.LOBBY_PORT)
                    && lobby == client.LOBBY_CONNECTION_CONTEXT.getConnection() && game == client.GAME_CONNECTION_CONTEXT.getConnection();
        }
    }
}

