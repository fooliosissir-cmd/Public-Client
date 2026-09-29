package com.rs.jagex;

import java.util.Arrays;
import java.util.Objects;

/**
 * Resource ownership participant, deliberately not a loading-pipeline entry point.
 * Call on the game thread after stopping game/music producers and the loading-tip thread.
 * Capture and suspend while source cache files are open; either resumeSource before cache
 * commitment or commitOwnership while both exact-generation worker barriers remain held.
 * After target JS5 is published and disk I/O resumes, bindTargetIndices installs fresh graphs.
 * This class never opens devices, starts workers, loads/unloads DLLs or changes renderer.
 */
final class ReloadResourceSession {
    private enum State { CAPTURED, SUSPENDED, SOURCE_RESUMED, TARGET_COMMITTED, TARGET_BOUND }
    private final Thread owner = Thread.currentThread();
    private final long generation;
    private final JS5Manager sourceManager, targetManager;
    private final byte[] nativeIdentity;
    private final NativeLibraryLoader natives;
    private final AbstractRenderer renderer;
    private final int rendererType;
    private final Class253 music, effects;
    private final Class254 audioWorker;
    private final UID192 sourceData, sourceMaster;
    private final UID192[] sourceIndices, capturedIndices;
    private Node_Sub15 sourceMusic, sourceEffects;
    private CacheProfileLifecycle.Prepared targetCache;
    private Node_Sub15 targetMusic, targetEffects;
    private Index targetNativeIndex;
    private Index targetSamples, targetInstruments, targetSounds;
    private State state = State.CAPTURED;

    static ReloadResourceSession capture(long generation, JS5Manager targetManager) {
        if (generation < 0) throw new IllegalArgumentException("Invalid reload generation");
        return new ReloadResourceSession(generation, Objects.requireNonNull(targetManager));
    }

    private ReloadResourceSession(long generation, JS5Manager targetManager) {
        this.generation = generation;
        sourceManager = Objects.requireNonNull(ScreenSizePreference.JS5_MANAGER, "Source JS5 manager required");
        if (targetManager == sourceManager) throw new IllegalArgumentException("Fresh target JS5 manager required");
        this.targetManager = targetManager;
        nativeIdentity = nativeIdentity(sourceManager);
        requireCompatibleTarget();
        natives = Objects.requireNonNull(IndexLoaders.NATIVE_LIBRARY_LOADER, "Loaded native owner required");
        if (natives != Class404.LIBRARY_LOADER) throw new IllegalStateException("Native owner changed");
        renderer = Objects.requireNonNull(Renderers.CURRENT_RENDERER, "Current renderer required");
        rendererType = Class393.preferences.currentToolkit.getValue();
        music = Objects.requireNonNull(Class320.aClass253_3723, "Music device required");
        effects = Objects.requireNonNull(ShaderDecoder.aClass253_1008, "Effects device required");
        if (music == effects) throw new IllegalStateException("Separate audio devices required");
        audioWorker = Class253.aClass254_3120;
        sourceData = Objects.requireNonNull(Engine.aClass440_3270, "Source cache required");
        sourceMaster = Engine.aClass440_3271;
        sourceIndices = Objects.requireNonNull(Class97.aClass440Array996);
        capturedIndices = sourceIndices.clone();
        requireLoadingThreadStopped();
    }

    /** Reads the already RSA/Whirlpool-validated master without moving its decoder cursor. */
    static byte[] nativeIdentity(JS5Manager manager) {
        if (manager == null || manager.buffer == null || manager.grabWorkers == null)
            throw new IllegalStateException("Validated JS5 master required");
        byte[] bytes = manager.buffer.buffer;
        int count = manager.grabWorkers.length, offset = 6 + 30 * 72;
        if (count <= 30 || count > 255 || bytes.length < 6 + count * 72 || (bytes[5] & 255) != count)
            throw new IllegalStateException("Native index metadata missing or truncated");
        return Arrays.copyOfRange(bytes, offset, offset + 72);
    }

    /** The device monitor drains an in-progress mixer callback before detaching its old graph. */
    void suspendAudio() {
        requireOwner(); requireState(State.CAPTURED); requireSource(); requireResources();
        synchronized (music) {
            synchronized (effects) {
                sourceMusic = music.aNode_Sub15_3122;
                sourceEffects = effects.aNode_Sub15_3122;
                music.method4329(null);
                effects.method4329(null);
                state = State.SUSPENDED;
            }
        }
    }

    /** Precommit abort only. A committed target must never resurrect a graph backed by closed source files. */
    void resumeSource() {
        requireOwner(); requireState(State.SUSPENDED); requireSource(); requireResources();
        synchronized (music) {
            synchronized (effects) {
                requireDetached();
                music.method4329(sourceMusic);
                effects.method4329(sourceEffects);
                sourceMusic = sourceEffects = null;
                state = State.SOURCE_RESUMED;
            }
        }
    }

    /** Adopt under the paused workers; ordinary target asset loading can then resume disk I/O. */
    void commitOwnership(CacheProfileLifecycle.Prepared cache, MapRegionLoaderTask maps, JS5LocalRequester disk) {
        requireOwner(); requireState(State.SUSPENDED); requireResources(); requireCompatibleTarget();
        synchronized (maps.aLinkedList3990) {
            synchronized (disk.aClass477_3664) {
                if (!cache.ownsActiveCache(generation) || !maps.isQuiescent(generation) || !disk.isQuiescent(generation))
                    throw new IllegalStateException("Committed target and both exact-generation barriers required");
                requireDetached();
                targetCache = cache;
                sourceMusic = sourceEffects = null;
                state = State.TARGET_COMMITTED;
            }
        }
    }

    /** All indices must belong to the published target manager, not just have matching archive numbers. */
    void bindTargetIndices(Index nativeIndex, Index midiSamples, Index midiInstruments, Index soundEffects) {
        requireOwner(); requireState(State.TARGET_COMMITTED); requireResources(); requireCompatibleTarget();
        if (!targetCache.ownsActiveCache(generation) || ScreenSizePreference.JS5_MANAGER != targetManager)
            throw new IllegalStateException("Adopted target cache/JS5 manager is no longer active");
        requireIndex(nativeIndex, 30); requireIndex(midiSamples, 15);
        requireIndex(midiInstruments, 14); requireIndex(soundEffects, 4);
        // Allocate before changing any retained owner or global stream reference.
        Node_Sub15_Sub2 newMusic = MaterialProp8.method15262(null);
        Node_Sub15_Sub4 newEffects = new Node_Sub15_Sub4();
        Class344 resampler = new Class344(22050, Class253.anInt3129);
        synchronized (music) {
            synchronized (effects) {
                requireDetached();
                natives.nativeLibraryIndex = nativeIndex;
                Static.method2084(midiSamples, midiInstruments, soundEffects, newMusic, music);
                Class79.aNode_Sub15_Sub4_783 = newEffects;
                Class119.aClass344_1460 = resampler;
                GraphicsPreference.method12658();
                music.method4329(newMusic);
                effects.method4329(newEffects);
                targetMusic = newMusic; targetEffects = newEffects; targetNativeIndex = nativeIndex;
                targetSamples = midiSamples; targetInstruments = midiInstruments; targetSounds = soundEffects;
                sourceMusic = sourceEffects = null;
                state = State.TARGET_BOUND;
            }
        }
    }

    int retainedRendererType() { return rendererType; }
    AbstractRenderer retainedRenderer() { return renderer; }
    boolean targetBound() { return state == State.TARGET_BOUND; }
    long generation() { return generation; }
    JS5Manager targetManager() { return targetManager; }

    /** Retry observes already adopted resources; it never adopts or binds them again. */
    void validateAdoptedForRetry(CacheProfileLifecycle.Prepared cache, long expectedGeneration) {
        requireOwner();
        if ((state != State.TARGET_COMMITTED && state != State.TARGET_BOUND)
                || expectedGeneration != generation || cache != targetCache
                || !cache.ownsActiveCache(generation) || ScreenSizePreference.JS5_MANAGER != targetManager)
            throw new IllegalStateException("Exact adopted target cache and manager required");
        requireRetainedOwners(); requireCompatibleTarget();
        synchronized (music) {
            synchronized (effects) {
                if (state == State.TARGET_COMMITTED) requireDetached();
                else if (music.aNode_Sub15_3122 != targetMusic || effects.aNode_Sub15_3122 != targetEffects
                        || natives.nativeLibraryIndex != targetNativeIndex
                        || Class79.aNode_Sub15_Sub4_783 != targetEffects || Class148.aNode_Sub15_Sub2_1735 != targetMusic
                        || Class148.aClass317_1737 != targetSamples || Class148.aClass317_1731 != targetInstruments
                        || Class148.aClass317_1732 != targetSounds || Class502.aClass253_5830 != music)
                    throw new IllegalStateException("Target native/audio bindings changed");
            }
        }
    }

    private void requireIndex(Index index, int number) {
        if (index == null || !(index.aClass327_3690 instanceof JS5GrabWorker worker)
                || worker.indexId != number || targetManager.grabWorkers[number] != worker)
            throw new IllegalStateException("Index does not belong to target JS5 manager");
    }
    private void requireCompatibleTarget() {
        if (!Arrays.equals(nativeIdentity, nativeIdentity(targetManager)))
            throw new IllegalStateException("Native index changed; a new process is required");
    }
    private void requireSource() {
        if (sourceManager != ScreenSizePreference.JS5_MANAGER || sourceData != Engine.aClass440_3270
                || sourceMaster != Engine.aClass440_3271 || sourceIndices != Class97.aClass440Array996
                || !Arrays.equals(capturedIndices, Class97.aClass440Array996))
            throw new IllegalStateException("Source cache is no longer active");
    }
    private void requireResources() {
        requireLoadingThreadStopped();
        requireRetainedOwners();
    }
    private void requireRetainedOwners() {
        if (natives != Class404.LIBRARY_LOADER || natives != IndexLoaders.NATIVE_LIBRARY_LOADER
                || renderer != Renderers.CURRENT_RENDERER || rendererType != Class393.preferences.currentToolkit.getValue()
                || music != Class320.aClass253_3723 || effects != ShaderDecoder.aClass253_1008
                || audioWorker != Class253.aClass254_3120)
            throw new IllegalStateException("Retained resource owner changed");
    }
    private static void requireLoadingThreadStopped() {
        if (VarDefinitionLoader.aThread4520 != null && VarDefinitionLoader.aThread4520.isAlive())
            throw new IllegalStateException("Loading-tip thread must be stopped before resource transfer");
    }
    private void requireDetached() {
        if (music.aNode_Sub15_3122 != null || effects.aNode_Sub15_3122 != null)
            throw new IllegalStateException("Audio producer resumed during resource transfer");
    }
    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Resource transfer must remain on its game thread");
    }
    private void requireState(State expected) {
        if (state != expected) throw new IllegalStateException("Invalid resource transfer phase");
    }
}
