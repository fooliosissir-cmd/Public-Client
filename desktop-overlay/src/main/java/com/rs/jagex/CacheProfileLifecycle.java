package com.rs.jagex;

import com.rs.Loader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Cache-file ownership only. The coordinator must separately stop JS5 producers and rebuild all loaders. */
public final class CacheProfileLifecycle {
    private CacheProfileLifecycle() { }

    public record CommitResult(Path profile, Path cacheDirectory, List<IOException> sourceCloseFailures) {
        public CommitResult { sourceCloseFailures = List.copyOf(sourceCloseFailures); }
        /** A close failure is after commitment: the published target remains owned by Engine. */
        public boolean cleanSourceClose() { return sourceCloseFailures.isEmpty(); }
    }

    public static Prepared prepare(Path profilesRoot, ServiceLoginMemory.Service service,
                                    String gameName, String buildEnvironment, int indexCount) throws IOException {
        return prepareProfile(profilesRoot, service.profileName(), gameName, buildEnvironment, indexCount, false);
    }

    /** One asset profile for both services. Shared handles remain Engine-owned even on abort. */
    public static Prepared prepareShared(Path profilesRoot, String gameName, String buildEnvironment,
                                         int indexCount) throws IOException {
        return prepareProfile(profilesRoot, "Live", gameName, buildEnvironment, indexCount, true);
    }

    private static Prepared prepareProfile(Path profilesRoot, String profileName, String gameName,
                                            String buildEnvironment, int indexCount, boolean reuse) throws IOException {
        if (indexCount < 1 || indexCount > 255) throw new IllegalArgumentException("Invalid JS5 index count");
        requirePathPart(gameName);
        requirePathPart(buildEnvironment);
        Source source = Source.capture();
        Path root = Objects.requireNonNull(profilesRoot).toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Linked profile root is not supported");
        Path realRoot = root.toRealPath();
        if (!realRoot.equals(root)) throw new IOException("Redirected profile root is not supported");
        root = realRoot;
        Path profile = safeDirectory(root, profileName);
        Path home = safeDirectory(profile, Loader.HOME_DIR);
        Path caches = safeDirectory(home, "caches");
        Path game = safeDirectory(caches, gameName);
        Path directory = safeDirectory(game, buildEnvironment);
        if (source.directory != null && directory.equals(source.directory.toPath().toRealPath())) {
            if (!reuse) throw new IOException("Target cache is already active");
            if (source.data == null || source.master == null || source.indices == null
                    || source.indices.length != indexCount || source.indexCount != indexCount
                    || java.util.Arrays.stream(source.indices).anyMatch(Objects::isNull))
                throw new IOException("Active shared cache does not match destination index count");
            return new Prepared(source, profile, directory, source.data, source.master, source.indices, true);
        }
        List<UID192> opened = new ArrayList<>();
        try {
            UID192 data = open(directory, "main_file_cache.dat2", 524288000L, 5200, opened);
            UID192 master = open(directory, "main_file_cache.idx255", 1048576L, 6000, opened);
            UID192[] indices = new UID192[indexCount];
            for (int index = 0; index < indexCount; index++)
                indices[index] = open(directory, "main_file_cache.idx" + index, 1048576L, 6000, opened);
            return new Prepared(source, profile, directory, data, master, indices, false);
        } catch (IOException | RuntimeException failure) {
            for (IOException close : closeHandles(opened)) failure.addSuppressed(close);
            throw failure;
        }
    }

    private static void requirePathPart(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Single cache path component required");
    }

    private static Path safeDirectory(Path parent, String name) throws IOException {
        Path path = parent.resolve(name).normalize();
        if (!path.startsWith(parent) || Files.isSymbolicLink(path)) throw new IOException("Linked/outside cache path rejected");
        Files.createDirectories(path);
        Path real = path.toRealPath();
        if (!real.equals(path) || !real.startsWith(parent)) throw new IOException("Redirected cache directory rejected");
        return real;
    }

    private static UID192 open(Path directory, String name, long limit, int buffer, List<UID192> opened) throws IOException {
        Path path = directory.resolve(name);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path))
                throw new IOException("Linked/non-file cache entry rejected");
            // Class442 otherwise deletes oversized files; preparing a switch must never discard existing data.
            if (Files.size(path) > limit) throw new IOException("Existing cache file exceeds supported size");
        }
        Class442 file = new Class442(path.toFile(), limit);
        try {
            UID192 handle = new UID192(file, buffer);
            opened.add(handle);
            return handle;
        } catch (IOException | RuntimeException failure) {
            try { file.method7385(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private record Source(File directory, File memoDirectory, String profileHome, UID192 data, UID192 master,
                          UID192[] indices, UID192[] capturedIndices, int indexCount, UID192 playerUid) {
        static Source capture() {
            return new Source(Engine.aFile3264, Class271.aFile3327, Class110.aString1103, Engine.aClass440_3270,
                    Engine.aClass440_3271, Class97.aClass440Array996,
                    Class97.aClass440Array996 == null ? null : Class97.aClass440Array996.clone(),
                    HeadbarIndexLoader.anInt3451, Engine.PLAYER_UID192);
        }
        boolean stillCurrent() {
            return Objects.equals(directory, Engine.aFile3264) && Objects.equals(memoDirectory, Class271.aFile3327)
                    && Objects.equals(profileHome, Class110.aString1103) && data == Engine.aClass440_3270
                    && master == Engine.aClass440_3271 && indices == Class97.aClass440Array996
                    && java.util.Arrays.equals(capturedIndices, Class97.aClass440Array996)
                    && indexCount == HeadbarIndexLoader.anInt3451 && playerUid == Engine.PLAYER_UID192;
        }
        List<UID192> handles() {
            List<UID192> result = new ArrayList<>();
            if (data != null) result.add(data);
            if (master != null) result.add(master);
            if (capturedIndices != null) for (UID192 index : capturedIndices) if (index != null) result.add(index);
            return result;
        }
    }

    public static final class Prepared implements AutoCloseable {
        private final Source source;
        private final Path profile, directory;
        private final UID192 data, master;
        private final UID192[] indices, expectedIndices;
        private final boolean borrowed;
        private boolean closed, committed;
        private long committedGeneration = -1;

        private Prepared(Source source, Path profile, Path directory, UID192 data, UID192 master, UID192[] indices, boolean borrowed) {
            this.borrowed = borrowed;
            this.source = source; this.profile = profile; this.directory = directory;
            this.data = data; this.master = master; this.indices = indices; expectedIndices = indices.clone();
        }
        public Path profile() { return profile; }
        public Path cacheDirectory() { return directory; }

        /** Game-thread commit only, after source varcs/preferences are saved and all other producers stopped. */
        public synchronized CommitResult commit(long generation, MapRegionLoaderTask maps, JS5LocalRequester disk,
                                                MapRegion replacement) {
            if (closed || committed) throw new IllegalStateException("Prepared cache is no longer available");
            Objects.requireNonNull(maps); Objects.requireNonNull(disk); Objects.requireNonNull(replacement);
            // Hold both admission gates so a concurrent timeout/resume cannot invalidate either proof.
            synchronized (maps.aLinkedList3990) {
                synchronized (disk.aClass477_3664) {
                    if (!maps.isQuiescent(generation) || !disk.isQuiescent(generation))
                        throw new IllegalStateException("Both exact-generation worker barriers are required");
                    if (!source.stillCurrent()) throw new IllegalStateException("Active source cache changed during preparation");
                    if (!maps.resetWhileQuiescent(generation, replacement))
                        throw new IllegalStateException("Map barrier changed before commitment");
                    committed = true;
                    committedGeneration = generation;
                    Engine.aFile3264 = directory.toFile();
                    Class271.aFile3327 = directory.toFile();
                    Class271.aBool3328 = true;
                    Class271.aHashtable3329.clear();
                    Class110.aString1103 = profile + File.separator;
                    Engine.aClass440_3270 = data;
                    Engine.aClass440_3271 = master;
                    Class97.aClass440Array996 = indices;
                    HeadbarIndexLoader.anInt3451 = indices.length;
                    // PLAYER_UID192/random.dat and Engine's thread remain untouched.
                    return new CommitResult(profile, directory, borrowed ? List.of() : closeHandles(source.handles()));
                }
            }
        }

        public synchronized boolean committed() { return committed; }

        synchronized boolean ownsActiveCache(long generation) {
            return committed && committedGeneration == generation && data == Engine.aClass440_3270
                    && master == Engine.aClass440_3271 && indices == Class97.aClass440Array996
                    && java.util.Arrays.equals(expectedIndices, Class97.aClass440Array996)
                    && directory.toFile().equals(Engine.aFile3264);
        }

        /** Abort before commitment closes only prepared target handles; after commit Engine owns them. */
        @Override public synchronized void close() throws IOException {
            if (closed || committed) return;
            closed = true;
            if (borrowed) return;
            List<UID192> handles = new ArrayList<>(List.of(data, master));
            handles.addAll(List.of(indices));
            List<IOException> failures = closeHandles(handles);
            if (!failures.isEmpty()) {
                IOException first = failures.getFirst();
                for (int i = 1; i < failures.size(); i++) first.addSuppressed(failures.get(i));
                throw first;
            }
        }
    }

    private static List<IOException> closeHandles(List<UID192> handles) {
        List<IOException> failures = new ArrayList<>();
        for (UID192 handle : handles) {
            try { handle.method7346(); }
            catch (IOException error) {
                failures.add(error);
                // A flush failure must not prevent closing this or subsequent old file handles.
                try { handle.aClass442_5346.method7385(); } catch (IOException close) { failures.add(close); }
            }
        }
        return failures;
    }
}
