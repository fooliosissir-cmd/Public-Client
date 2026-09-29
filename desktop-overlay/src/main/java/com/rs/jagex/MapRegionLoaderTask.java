package com.rs.jagex;

import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

public class MapRegionLoaderTask implements Runnable {
    static int anInt3991;
    MapRegion region = new MapRegion(true);
    volatile boolean aBool3989;
    final LinkedList<Class335> aLinkedList3990 = new LinkedList<>();
    private boolean quiescing, parked, publishing;
    private long quiesceGeneration = -1, sceneGeneration;
    private static final ThreadLocal<Work> CURRENT = new ThreadLocal<>();
    private record Work(MapRegionLoaderTask owner, long generation) { }
    private static final class DiscardedScene extends RuntimeException {
        DiscardedScene() { super(null, null, false, false); }
    }

    /** Closes admission and cooperatively parks active work without losing its parser state. */
    public boolean beginQuiesce(long generation) {
        synchronized (aLinkedList3990) {
            if (generation < 0) return false;
            if (quiescing) return generation == quiesceGeneration;
            if (generation <= quiesceGeneration) return false;
            quiesceGeneration = generation;
            quiescing = true;
            aLinkedList3990.notifyAll();
            return true;
        }
    }

    public boolean isQuiescent(long generation) {
        synchronized (aLinkedList3990) {
            return quiescing && generation == quiesceGeneration && (!aBool3989 || parked) && !publishing;
        }
    }

    /** Timeout/cancel before commit preserves queued tasks and the active suspended stack. */
    public boolean resume(long generation) {
        synchronized (aLinkedList3990) {
            if (!quiescing || generation != quiesceGeneration) return false;
            quiescing = false;
            aLinkedList3990.notifyAll();
            return true;
        }
    }

    /** Commit-only: prepare the replacement object loader before installing this acknowledged scene. */
    public boolean resetWhileQuiescent(long generation, MapRegion replacement) {
        Objects.requireNonNull(replacement);
        synchronized (aLinkedList3990) {
            if (!isQuiescent(generation)) return false;
            ++sceneGeneration;
            aLinkedList3990.clear();
            region = replacement;
            aLinkedList3990.notifyAll();
            return true;
        }
    }

    public void loadMapRegionAsync(Class335 request) {
        Objects.requireNonNull(request);
        synchronized (aLinkedList3990) {
            if (quiescing) throw new RejectedExecutionException("Map admission is closed");
            aLinkedList3990.add(request);
            aLinkedList3990.notifyAll();
        }
    }

    void method6050(MapRegion replacement) {
        synchronized (aLinkedList3990) {
            Work current = CURRENT.get();
            if (aBool3989 && !(current != null && current.owner == this && publishing))
                throw new IllegalStateException("Cannot replace an active map region");
            region = replacement;
        }
    }

    public boolean method6051() { return aBool3989; }
    public MapRegion method6052() {
        synchronized (aLinkedList3990) { return region; }
    }

    void method6054() {
        Class335 request;
        Work work;
        synchronized (aLinkedList3990) {
            while (quiescing || aLinkedList3990.isEmpty()) {
                try { aLinkedList3990.wait(); } catch (InterruptedException ignored) { }
            }
            request = aLinkedList3990.removeFirst();
            aBool3989 = true;
            work = new Work(this, sceneGeneration);
        }
        CURRENT.set(work);
        try {
            checkpoint();
            method6055(request);
        } catch (DiscardedScene ignored) {
            // Only an acknowledged committed reset discards source work, never ordinary resume.
        } catch (Exception ignored) {
            // Preserve the legacy worker's recover-and-process-next-task behavior.
        } finally {
            CURRENT.remove();
            synchronized (aLinkedList3990) {
                publishing = false;
                parked = false;
                aBool3989 = false;
                aLinkedList3990.notifyAll();
            }
        }
    }

    static void checkpoint() {
        Work work = CURRENT.get();
        if (work != null) work.owner.awaitPermission(work.generation);
    }

    private void awaitPermission(long generation) {
        synchronized (aLinkedList3990) {
            try {
                while (quiescing && generation == sceneGeneration && !publishing) {
                    parked = true;
                    aLinkedList3990.notifyAll();
                    try { aLinkedList3990.wait(); } catch (InterruptedException ignored) { }
                }
                if (generation != sceneGeneration) throw new DiscardedScene();
            } finally { parked = false; }
        }
    }

    /** An admitted native publication finishes before acknowledgement; pause never cuts its handoff in half. */
    static void beginPublication() {
        Work work = CURRENT.get();
        if (work == null) return;
        synchronized (work.owner.aLinkedList3990) {
            work.owner.awaitPermission(work.generation);
            work.owner.publishing = true;
        }
    }

    void method6055(Class335 request) {
        MapRegion workingRegion = method6052();
        if (RegionLoadType.aRegionLoadType_3152 == request.aRegionLoadType_3915)
            workingRegion.method4547();
        else
            workingRegion.loadMapScene(request);
        while (true) {
            checkpoint();
            if (workingRegion.method4461()) break;
            Thread.yield();
        }
        beginPublication();
        // Native handoff may swap our region with the previous foreground decoder.
        method6052().method4445();
        IndexLoaders.MAP_REGION_DECODER.method4445();
    }

    @Override public void run() { while (true) method6054(); }

    public void setObjectIndexLoader(LocationIndexLoader loader) {
        synchronized (aLinkedList3990) {
            if (aBool3989 && !parked) throw new IllegalStateException("Cannot replace active map object loader");
            region.setObjectIndexLoader(loader);
        }
    }
}
