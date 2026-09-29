package com.rs.jagex;

import com.rs.Loader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** Native asset-stage replay only. Endpoint selection and authentication belong to the coordinator. */
final class NativeLoadingReload {
    enum Status { RUNNING, READY, FAILED }
    private static NativeLoadingReload current;
    private final Thread owner = Thread.currentThread();
    private final ReloadResourceSession resources;
    private final MapRegionLoaderTask maps;
    private Status status = Status.RUNNING;
    private Throwable failure;
    enum FailureKind { TRANSIENT_TRANSPORT, TERMINAL }
    private FailureKind failureKind;
    private CacheProfileLifecycle.Prepared cache;
    private JS5LocalRequester disk;
    private JS5StandardRequester requester;
    private MapRegion foreground, background;
    private Endpoint js5Endpoint, lobbyEndpoint;
    private LoadingStage failedStage;
    private int failedNativeStage;
    private int failedResponse;
    private boolean rendererReplacementStarted, mapAdmissionStarted;

    private record Endpoint(ConnectionInfo identity, String host, int world, int port) {
        static Endpoint capture(ConnectionInfo info) {
            return info == null ? null : new Endpoint(info, info.host, info.worldId, Loader.getPort(info.worldId));
        }
        boolean matches(ConnectionInfo info, ServiceLoginMemory.Service target) {
            return info == identity && info != null && world == info.worldId && Objects.equals(host, info.host)
                    && port == Loader.getPort(world) && target.host().equals(host) && target.lobbyPort() == port;
        }
    }

    private NativeLoadingReload(ReloadResourceSession resources, MapRegionLoaderTask maps) {
        this.resources = resources; this.maps = maps;
    }

    /** Caller has saved source state, frozen all source producers and cleared service authentication. */
    static NativeLoadingReload begin(ReloadResourceSession resources, CacheProfileLifecycle.Prepared cache,
                                     MapRegionLoaderTask maps, JS5LocalRequester disk, MapRegion foreground) {
        Objects.requireNonNull(resources); Objects.requireNonNull(foreground);
        if (current != null && current.status != Status.READY) throw new IllegalStateException("Reload already pending");
        if ((client.GAME_STATE != GameState.UNK_0 && client.GAME_STATE != GameState.UNK_5)
                || Login.getLoginStage() != LoginStage.NONE_2 || CS2Executor.CURRENT_CS2_EXEC_IDX != 0)
            throw new IllegalStateException("Stable signed-out/lobby game-thread boundary required");
        if (maps != IndexLoaders.MAP_REGION_LOADER_THREAD || disk != Whirlpool.JS5_LOCAL_REQUESTER)
            throw new IllegalStateException("Existing worker identities required");
        JS5Manager target = resources.targetManager();
        if (target.localRequester != disk || target.standardRequester == Class119.JS5_STANDARD_REQUESTER)
            throw new IllegalStateException("Fresh target network requester and existing local worker required");
        for (JS5GrabWorker worker : target.grabWorkers)
            if (worker != null) throw new IllegalStateException("Target index workers must be created after cache commitment");
        if (foreground == IndexLoaders.MAP_REGION_DECODER || foreground == maps.method6052())
            throw new IllegalStateException("Fresh foreground region required");
        if (Class446.aClass446_5412.method7443() == null)
            throw new IllegalStateException("Initial native startup has not completed");
        resources.commitOwnership(cache, maps, disk);
        NativeLoadingReload reload = new NativeLoadingReload(resources, maps);
        reload.cache = cache; reload.disk = disk; reload.requester = target.standardRequester;
        reload.foreground = foreground; reload.background = maps.method6052();
        reload.js5Endpoint = Endpoint.capture(ConnectionInfo.JS5_CONNECTION_INFO);
        reload.lobbyEndpoint = Endpoint.capture(ConnectionInfo.LOBBY_CONNECTION_INFO);
        current = reload;
        try {
            UiScaleFrame.release(resources.retainedRenderer());
            closeUpdateConnection();
            Class119.JS5_STANDARD_REQUESTER = target.standardRequester;
            ScreenSizePreference.JS5_MANAGER = target;
            Class533.aClass203_7073 = new JS5CacheFile(255, Engine.aClass440_3270, Engine.aClass440_3271);
            resetServiceState();
            IndexLoaders.MAP_REGION_DECODER.method4444();
            IndexLoaders.MAP_REGION_DECODER = foreground;
            foreground.setMapSizes(MapSize.SIZE_104);
            if (!disk.resume(resources.generation())) throw new IllegalStateException("Target disk barrier changed");
            GameState.setGameState(GameState.UNK_4);
        } catch (RuntimeException | LinkageError error) { reload.fail(error); }
        return reload;
    }

    Status status() { return status; }
    Throwable failure() { return failure; }
    static boolean active() { return current != null && current.status == Status.RUNNING; }
    static boolean holdsFrame() { return current != null && current.status != Status.READY; }

    static boolean stageFailed() { return current != null && current.status == Status.FAILED; }
    static boolean failStage(Throwable error) {
        if (!active()) return false;
        current.requireOwner(); current.fail(error); return true;
    }
    private void fail(Throwable error) {
        failure = error; failureKind = FailureKind.TERMINAL; status = Status.FAILED;
        failedStage = IndexLoaders.LOADING_STAGE; failedNativeStage = Class296.anInt3532;
    }
    FailureKind failureKind() { return failureKind; }
    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Reload must stay on its game thread");
    }

    static void bindAudioAndNatives() {
        current.requireOwner();
        current.resources.bindTargetIndices(IndexLoaders.NATIVE_LIBRARY_INDEX, IndexLoaders.SOUND_MIDI_INDEX,
                IndexLoaders.MIDI_INSTRUMENT_INDEX, IndexLoaders.SOUND_EFFECT_INDEX);
    }
    static void objectLoadersReady() {
        if (active()) {
            current.mapAdmissionStarted = true;
            if (!current.maps.resume(current.resources.generation()))
                throw new IllegalStateException("Target map barrier changed");
        }
    }
    static void restoreRenderer() {
        current.requireOwner();
        if (!current.resources.targetBound()) throw new IllegalStateException("Target resources not bound");
        current.rendererReplacementStarted = true;
        // Existing native cleanup releases the old renderer and rebinds all GPU assets to IMAGE_LOADER.
        // true uses the checked GPU-only retry path and does not alter the actual display mode.
        ParticleProducer.switchRenderType(current.resources.retainedRendererType(), true);
    }
    static void completed() {
        if (active()) { current.requireOwner(); current.status = Status.READY; }
    }

    /** Suppress native startup's fatal web/error path for a committed target; coordinator presents retry. */
    static boolean handleConnectionFailure(int count, int response) {
        if (!holdsFrame()) return false;
        if (stageFailed()) return true;
        if ((count >= 2 && response == 6) || (count >= 1 && response == 48) || count >= 4) {
            failStage(new IllegalStateException("Target asset connection failed (" + response + ")"));
            current.failureKind = connectionFailureKind(response);
            current.failedResponse = response;
            return true;
        }
        return false;
    }

    static FailureKind connectionFailureKind(int response) {
        return response == -2 || response == 1001 || response == 1002
                ? FailureKind.TRANSIENT_TRANSPORT : FailureKind.TERMINAL;
    }

    /** Resume only this failed transport generation, keeping native stage and request identities intact. */
    void retryTransient(ServiceLoginMemory.Service target, long cacheGeneration, long authenticationGeneration,
                        boolean targetAuthenticationStarted) {
        requireOwner();
        if (current != this || status != Status.FAILED || failureKind != FailureKind.TRANSIENT_TRANSPORT
                || rendererReplacementStarted || mapAdmissionStarted || targetAuthenticationStarted
                || !ServiceLoginAdapter.nativeRetryAllowed(target, authenticationGeneration)
                || ServiceLoginMemory.serviceFor(Loader.IP_ADDRESS, Loader.LOBBY_PORT) != target
                || !GameState.loadingState(client.GAME_STATE) || Login.getLoginStage() != LoginStage.NONE_2
                || Class192.ACCOUNT_CREATION_STAGE != null
                || client.GAME_CONNECTION_CONTEXT.getConnection() != null
                || client.LOBBY_CONNECTION_CONTEXT.getConnection() != null
                || IndexLoaders.LOADING_STAGE != failedStage || Class296.anInt3532 != failedNativeStage
                || maps != IndexLoaders.MAP_REGION_LOADER_THREAD || disk != Whirlpool.JS5_LOCAL_REQUESTER
                || foreground != IndexLoaders.MAP_REGION_DECODER || background != maps.method6052()
                || requester != Class119.JS5_STANDARD_REQUESTER || requester != resources.targetManager().standardRequester
                || requester.anInt3650 != failedResponse
                || disk != resources.targetManager().localRequester || !maps.isQuiescent(cacheGeneration)
                || js5Endpoint == null || lobbyEndpoint == null
                || js5Endpoint.world() != Loader.JS5_SOURCE_WORLD || lobbyEndpoint.world() != Loader.LOBBY_WORLD
                || !js5Endpoint.matches(ConnectionInfo.JS5_CONNECTION_INFO, target)
                || !lobbyEndpoint.matches(ConnectionInfo.LOBBY_CONNECTION_INFO, target))
            throw new IllegalStateException("Transient retry proof is no longer valid");
        resources.validateAdoptedForRetry(cache, cacheGeneration);
        if (!(requester instanceof JS5StandardRequester_Sub1 network))
            throw new IllegalStateException("Retry requires the existing native requester");
        network.disconnectForRetry();
        java.net.Socket socket = MaterialProp8.clientSocket;
        if (socket == null && Class47_Sub1.updateConnection instanceof AsyncConnection async) socket = async.socket;
        try { if (socket != null) socket.close(); } catch (IOException | RuntimeException ignored) { }
        try {
            if (Class47_Sub1.updateConnection != null) Class47_Sub1.updateConnection.end();
        } finally {
            Class47_Sub1.updateConnection = null; MaterialProp8.clientSocket = null;
        }
        client.updateStage = client.anInt7232 = client.anInt7202 = 0;
        client.aString7463 = null; JS5CacheFile.aLong2577 = 0;
        requester.anInt3657 = requester.anInt3650 = 0;
        failure = null; failureKind = null; status = Status.RUNNING;
    }

    private static void closeUpdateConnection() {
        // Unblock asynchronous writers before either native close path joins them.
        java.net.Socket handshakeSocket = MaterialProp8.clientSocket;
        if (handshakeSocket == null && Class47_Sub1.updateConnection instanceof AsyncConnection async)
            handshakeSocket = async.socket;
        try { if (handshakeSocket != null) handshakeSocket.close(); } catch (IOException | RuntimeException ignored) { }
        if (Class119.JS5_STANDARD_REQUESTER instanceof JS5StandardRequester_Sub1 requester
                && requester.aClass202_7778 instanceof AsyncConnection async && async.socket != null)
            try { async.socket.close(); } catch (IOException | RuntimeException ignored) { }
        if (Class119.JS5_STANDARD_REQUESTER != null) Class119.JS5_STANDARD_REQUESTER.method5525();
        if (Class47_Sub1.updateConnection != null) Class47_Sub1.updateConnection.end();
        Class47_Sub1.updateConnection = null;
        if (MaterialProp8.clientSocket != null) try { MaterialProp8.clientSocket.close(); } catch (IOException ignored) { }
        MaterialProp8.clientSocket = null;
        client.updateStage = client.anInt7232 = client.anInt7202 = 0;
        client.aString7463 = null;
        JS5CacheFile.aLong2577 = 0;
    }

    /** Called only after admission/commit checks. No scene callbacks or old interface scripts are executed. */
    static void resetServiceState() {
        Class492.aClass327_Sub1Array5777 = null;
        IndexLoaders.resetCacheReferences();
        Class302.aClass387Array3557 = LoadingStage.method6676();
        IndexLoaders.LOADING_STAGE = Class302.aClass387Array3557[0];
        Class302.anInterface27Array3559 = null;
        Class302.anInt3560 = Class302.anInt3564 = -1;
        Class302.anInt3561 = Class302.anInt3563 = 0;
        Class302.aLong3562 = Utils.time();
        Comparable_Sub1.aClass306_3771 = null;
        VarDefinitionLoader.aThread4520 = null;
        EntityNode_Sub7.GAME_TIPS_LOADER = null;
        // Stage 0 is one-time jaclib/ping initialization. Preserve its completed wrapper, replay cache stage 1.
        Class296.anInt3532 = 1; Class296.anInt3533 = 0; Class296.aClass446Array3531 = null;
        for (Class446 resource : Class446.method7436())
            if (resource != Class446.aClass446_5412) resource.anInterface41_5414 = null;
        Class358.method6234();
        Class506.CS2_CACHE.method3760();
        CS2Executor.CS2_EXECUTORS.clear();
        client.PENDING_HOOK_REQUESTS.removeAll();
        client.aClass482_7233.removeAll(); client.aClass482_7404.removeAll();
        client.OPEN_INTERFACES.method7749(); client.ICOMPONENT_SETTINGS_SLOTS.method7749();
        client.BASE_WINDOW_ID = -1;
        Interface.INTERFACES = null; MapAreaIndexLoader.INTERFACES_LOADED = null;
        Class388.INTERFACE_INDEX = null; Class488.MESH_INDEX = null; ProcessorSpecs.SPRITES_INDEX = null;
        IComponentDefinitions.aClass229_1280.method3859(); IComponentDefinitions.aClass229_1341.method3859();
        IComponentDefinitions.aClass229_1303.method3859(); IComponentDefinitions.aClass229_1282.method3859();
        UiScaleSettingsPanel.resetForService();
        client.anInt7399 = 0;
        Class320.VARC_INT = null; Class462.VARC_STRING = null; Node_Sub17_Sub2.IS_VARC_SAVE_TO_FILE = null;
        client.aBool7219 = false;
        Class358.method6240();
        Class260.anInt3228 = Class260.anInt3223 = -1;
        Class260.SOUNDS_SIZE = 0; Arrays.fill(Class260.SOUNDS, null);
        Class492.INDEX36_FILE_CACHE.method7749();
        BillboardDefinitions.BILLBOARD_CACHE.method3859(); ParticleProducerDefinition.aClass229_533.method3859();
        ScriptRunner.anIntArray2668 = null;
        Class174.aBool2135 = false;
        client.IF_CURR_LAYER = 0;
    }

    static void pulseOnlyLoading() {
        // Keep event queues bounded without executing old input, menus, sound or item-icon producers.
        PlaySoundJingleCutsceneAction.keyRecorder.method3235();
        while (PlaySoundJingleCutsceneAction.keyRecorder.getNext() != null) { }
        Class163.mouseRecorder.method3589();
        for (MouseRecord record = Class163.mouseRecorder.nextSubmission(); record != null;
             record = Class163.mouseRecorder.nextSubmission()) record.cache();
        client.mouseRecords.removeAll();
        client.anInt7193 = client.maximumHeldKeys = client.anInt7191 = 0;
        Preference_Sub20.method12808();
    }
}
