package com.rs.jagex;

import java.util.Arrays;
import java.util.Objects;

/**
 * Nonblocking precommit participant; this helper alone does not freeze the game loop.
 * While held, the caller must suppress ordinary render/lobby/UI/asset producers. Only
 * its old-JS5 pump may run while waiting for map acknowledgement, never after disk pause.
 * Save preferences/varcs before begin; the coordinator supplies timeout and cancellation.
 */
final class ServiceSwitchQuiescence {
    private static volatile ServiceSwitchQuiescence current;
    private final Thread owner = Thread.currentThread();
    private final long generation;
    private final MapRegionLoaderTask maps;
    private final JS5LocalRequester disk;
    private final JS5Manager sourceManager;
    private final UID192 sourceData, sourceMaster;
    private final UID192[] sourceIndices, capturedIndices;
    private final ReloadResourceSession resources;
    private boolean diskPaused;

    static boolean held() { return current != null; }

    static synchronized ServiceSwitchQuiescence begin(long generation, JS5Manager target) {
        if (generation < 0) throw new IllegalArgumentException("Invalid switch generation");
        if (current != null || NativeLoadingReload.holdsFrame())
            throw new IllegalStateException("Service switch already pending");
        if ((client.GAME_STATE != GameState.UNK_0 && client.GAME_STATE != GameState.UNK_5)
                || Login.getLoginStage() != LoginStage.NONE_2
                || Class192.ACCOUNT_CREATION_STAGE != null || CS2Executor.CURRENT_CS2_EXEC_IDX != 0)
            throw new IllegalStateException("Stable game-thread boundary required");
        ServiceSwitchQuiescence next = new ServiceSwitchQuiescence(generation, Objects.requireNonNull(target));
        if (!next.maps.beginQuiesce(generation)) throw new IllegalStateException("Map barrier rejected");
        try {
            next.resources.suspendAudio();
        } catch (RuntimeException | Error failure) {
            if (!next.maps.resume(generation))
                failure.addSuppressed(new IllegalStateException("Map barrier rollback rejected"));
            throw failure;
        }
        current = next;
        return next;
    }

    private ServiceSwitchQuiescence(long generation, JS5Manager target) {
        this.generation = generation;
        maps = Objects.requireNonNull(IndexLoaders.MAP_REGION_LOADER_THREAD, "Map worker required");
        disk = Objects.requireNonNull(Whirlpool.JS5_LOCAL_REQUESTER, "Disk worker required");
        sourceManager = Objects.requireNonNull(ScreenSizePreference.JS5_MANAGER, "Source manager required");
        if (sourceManager == target || sourceManager.localRequester != disk || target.localRequester != disk)
            throw new IllegalStateException("Fresh target manager and existing disk worker required");
        sourceData = Objects.requireNonNull(Engine.aClass440_3270, "Source cache required");
        sourceMaster = Engine.aClass440_3271;
        sourceIndices = Objects.requireNonNull(Class97.aClass440Array996);
        capturedIndices = sourceIndices.clone();
        resources = ReloadResourceSession.capture(generation, target);
    }

    boolean poll(Runnable pumpSourceAssets) {
        requireCurrent(); requireSource();
        Objects.requireNonNull(pumpSourceAssets);
        if (!diskPaused) {
            if (!maps.isQuiescent(generation)) {
                pumpSourceAssets.run();
                requireCurrent(); requireSource();
                if (!maps.isQuiescent(generation)) return false;
            }
            if (!disk.beginQuiesce(generation)) throw new IllegalStateException("Disk barrier rejected");
            diskPaused = true;
        }
        return maps.isQuiescent(generation) && disk.isQuiescent(generation);
    }

    ReloadResourceSession resources() { return resources; }
    long generation() { return generation; }

    void abort() {
        requireCurrent(); requireSource();
        if (diskPaused) {
            if (!disk.resume(generation)) throw new IllegalStateException("Disk resume rejected");
            diskPaused = false;
        }
        resources.resumeSource();
        if (!maps.resume(generation)) throw new IllegalStateException("Map resume rejected");
        current = null;
    }

    /** Transfer the frame gate only after the real native reload has adopted the target. */
    void releaseToReload(CacheProfileLifecycle.Prepared cache) {
        requireCurrent(); requireWorkers();
        if (!Objects.requireNonNull(cache).ownsActiveCache(generation) || !NativeLoadingReload.holdsFrame()
                || ScreenSizePreference.JS5_MANAGER != resources.targetManager())
            throw new IllegalStateException("Adopted native reload required");
        current = null;
    }

    private void requireCurrent() {
        if (Thread.currentThread() != owner || current != this)
            throw new IllegalStateException("Active switch must remain on its creating thread");
    }

    private void requireWorkers() {
        if (maps != IndexLoaders.MAP_REGION_LOADER_THREAD || disk != Whirlpool.JS5_LOCAL_REQUESTER)
            throw new IllegalStateException("Worker identities changed");
    }

    private void requireSource() {
        requireWorkers();
        if (sourceManager != ScreenSizePreference.JS5_MANAGER || sourceManager.localRequester != disk
                || resources.targetManager().localRequester != disk || sourceData != Engine.aClass440_3270
                || sourceMaster != Engine.aClass440_3271 || sourceIndices != Class97.aClass440Array996
                || !Arrays.equals(capturedIndices, Class97.aClass440Array996))
            throw new IllegalStateException("Source cache ownership changed");
    }
}
