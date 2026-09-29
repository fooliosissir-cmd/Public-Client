package com.rs.jagex;

import com.rs.Loader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Unhooked, one-shot game-thread handoff. The caller freezes all producers and prepares
 * authentication teardown before entering. TARGET_FAILED has no rollback or retry path:
 * retain this gate until a separately reviewed target recovery policy exists.
 */
final class ServiceSwitchCommit {
    enum Status { COMMITTING, SOURCE_RESUMED, SOURCE_FAILED, RELOADING, TARGET_FAILED }
    private static volatile ServiceSwitchCommit current;
    private final Thread owner = Thread.currentThread();
    private final long cacheGeneration;
    private final ServiceSwitchPreflight.Result proof;
    private final CacheProfileLifecycle.Prepared cache;
    private final ServiceSwitchQuiescence quiescence;
    private Status status = Status.COMMITTING;
    private Throwable failure;
    private long authenticationGeneration = -1;
    private List<IOException> closeWarnings = List.of();
    private NativeLoadingReload reload;

    private ServiceSwitchCommit(long generation, ServiceSwitchPreflight.Result proof,
                                CacheProfileLifecycle.Prepared cache, ServiceSwitchQuiescence quiescence) {
        cacheGeneration = generation;
        this.proof = Objects.requireNonNull(proof);
        this.cache = Objects.requireNonNull(cache);
        this.quiescence = Objects.requireNonNull(quiescence);
    }

    static synchronized ServiceSwitchCommit begin(long generation, ServiceSwitchPreflight.Result proof,
                                                   CacheProfileLifecycle.Prepared cache,
                                                   ServiceSwitchQuiescence quiescence, Runnable resetAuthentication,
                                                   MapRegion background, MapRegion foreground) {
        if (current != null) throw new IllegalStateException("Commit/failure handoff already held");
        ServiceSwitchCommit handoff = new ServiceSwitchCommit(generation, proof, cache, quiescence);
        current = handoff;
        handoff.commit(resetAuthentication, background, foreground);
        return handoff;
    }

    private void commit(Runnable resetAuthentication, MapRegion background, MapRegion foreground) {
        try {
            validate(resetAuthentication, background, foreground);
            MapRegionLoaderTask maps = IndexLoaders.MAP_REGION_LOADER_THREAD;
            JS5LocalRequester disk = Whirlpool.JS5_LOCAL_REQUESTER;
            // committed() is authoritative even if old-handle cleanup throws after publication.
            closeWarnings = cache.commit(cacheGeneration, maps, disk, background).sourceCloseFailures();
            authenticationGeneration = ServiceLoginAdapter.committedServiceSwitch(proof.target(), resetAuthentication);
            publishEndpoints(proof.target());
            reload = NativeLoadingReload.begin(quiescence.resources(), cache, maps, disk, foreground);
            if (reload.status() == NativeLoadingReload.Status.FAILED)
                throw new IllegalStateException("Target native reload failed", reload.failure());
            quiescence.releaseToReload(cache);
            status = Status.RELOADING;
            current = null; // NativeLoadingReload now owns the frame gate.
        } catch (RuntimeException | Error problem) {
            failure = problem;
            if (cache.committed()) {
                status = Status.TARGET_FAILED;
                // Do not resume old audio, reopen old handles, or release either frame gate.
            } else {
                status = Status.SOURCE_FAILED;
                try {
                    quiescence.abort();
                    status = Status.SOURCE_RESUMED;
                    current = null;
                } catch (RuntimeException | Error abortFailure) { problem.addSuppressed(abortFailure); }
                try { cache.close(); }
                catch (IOException | RuntimeException closeFailure) { problem.addSuppressed(closeFailure); }
            }
        }
    }

    private void validate(Runnable resetAuthentication, MapRegion background, MapRegion foreground) {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Game-thread handoff required");
        Objects.requireNonNull(resetAuthentication); Objects.requireNonNull(background); Objects.requireNonNull(foreground);
        if (cacheGeneration < 0 || cacheGeneration != quiescence.generation() || cache.committed()
                || proof.source() == proof.target() || !proof.nativeCompatible())
            throw new IllegalStateException("Fresh compatible target and exact cache generation required");
        if ((client.GAME_STATE != GameState.UNK_0 && client.GAME_STATE != GameState.UNK_5)
                || Login.getLoginStage() != LoginStage.NONE_2 || Class192.ACCOUNT_CREATION_STAGE != null
                || CS2Executor.CURRENT_CS2_EXEC_IDX != 0 || NativeLoadingReload.holdsFrame())
            throw new IllegalStateException("Stable source boundary required");
        if (ServiceLoginMemory.serviceFor(Loader.IP_ADDRESS, Loader.LOBBY_PORT) != proof.source()
                || !sourceEndpoint(ConnectionInfo.LOBBY_CONNECTION_INFO, Loader.LOBBY_WORLD)
                || !sourceEndpoint(ConnectionInfo.JS5_CONNECTION_INFO, Loader.JS5_SOURCE_WORLD)
                || !(cache.profile().getFileName().toString().equals("Live")
                    || cache.profile().getFileName().toString().equals(proof.target().profileName())))
            throw new IllegalStateException("Pinned source/target profile changed");
        JS5Manager target = quiescence.resources().targetManager();
        if (target.grabWorkers.length != proof.indexCount()
                || !Arrays.equals(target.buffer.buffer, proof.masterTable())
                || !Arrays.equals(ReloadResourceSession.nativeIdentity(ScreenSizePreference.JS5_MANAGER), proof.sourceNativeIdentity()))
            throw new IllegalStateException("Preflight manager proof changed");
        MapRegion oldBackground = IndexLoaders.MAP_REGION_LOADER_THREAD.method6052();
        MapRegion oldForeground = IndexLoaders.MAP_REGION_DECODER;
        if (background == foreground || background == oldBackground || background == oldForeground
                || foreground == oldBackground || foreground == oldForeground)
            throw new IllegalStateException("Fresh distinct target regions required");
        if (!quiescence.poll(() -> { throw new IllegalStateException("Map acknowledgement required before commit"); }))
            throw new IllegalStateException("Disk acknowledgement required before commit");
    }

    private boolean sourceEndpoint(ConnectionInfo endpoint, int world) {
        return endpoint != null && endpoint.worldId == world
                && ServiceLoginMemory.serviceFor(endpoint.host, Loader.getPort(world)) == proof.source();
    }

    private static void publishEndpoints(ServiceLoginMemory.Service service) {
        ConnectionInfo js5 = endpoint(service, Loader.JS5_SOURCE_WORLD);
        ConnectionInfo lobby = endpoint(service, Loader.LOBBY_WORLD);
        ConnectionInfo game = endpoint(service, service.id());
        // Match the native selector: the pinned service ID is also its displayed game-world ID.
        if (ConnectionInfo.SERVER_ENVIRONMENT != ServerEnvironment.LIVE)
            game.anInt5434 = game.anInt5437 = service.id();
        Loader.IP_ADDRESS = service.host(); Loader.LOBBY_PORT = service.lobbyPort();
        Loader.clientParams.setProperty("5", service.host());
        Loader.clientParams.setProperty("21", service.host());
        ConnectionInfo.JS5_CONNECTION_INFO = js5;
        ConnectionInfo.LOBBY_CONNECTION_INFO = lobby;
        ConnectionInfo.GAME_CONNECTION_INFO = game;
    }

    private static ConnectionInfo endpoint(ServiceLoginMemory.Service service, int world) {
        ConnectionInfo result = new ConnectionInfo(); result.host = service.host(); result.worldId = world;
        return result;
    }

    static boolean holdsFrame() { return current != null; }
    Status status() { return status; }
    Throwable failure() { return failure; }
    long cacheGeneration() { return cacheGeneration; }
    /** -1 if reset failed: no successful target authentication generation may be restored. */
    long authenticationGeneration() { return authenticationGeneration; }
    List<IOException> sourceCloseWarnings() { return closeWarnings; }
    NativeLoadingReload reload() { return reload; }
}
