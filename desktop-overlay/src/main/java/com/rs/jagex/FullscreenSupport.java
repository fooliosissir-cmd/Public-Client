package com.rs.jagex;

import java.awt.DisplayMode;

/** Small, testable policies shared by script entry, native display selection, and failure cleanup. */
final class FullscreenSupport {
    private static volatile java.awt.Frame closeRequested;
    static void requestClose(java.awt.Frame frame) { closeRequested = frame; }
    static void pollCloseRequest() {
        java.awt.Frame requested = closeRequested;
        closeRequested = null;
        if (requested != null && requested == Engine.fullScreenFrame)
            UID192.method7373(windowedFallback(Class393.preferences.screenSize.getValue()), -1, -1);
    }
    @FunctionalInterface interface Attempt { boolean enter(int width, int height); }

    static void choose(CS2Executor executor, Attempt attempt) {
        executor.intStackPtr -= 2;
        int width = executor.intStack[executor.intStackPtr];
        int height = executor.intStack[executor.intStackPtr + 1];
        boolean opened = false;
        try {
            opened = attempt.enter(width, height);
        } catch (RuntimeException failure) {
            System.err.println("Fullscreen entry failed: " + failure.getClass().getSimpleName());
        }
        executor.intStack[executor.intStackPtr++] = opened ? 1 : 0;
    }

    static int windowedFallback(int savedMode) { return savedMode == 1 ? 1 : 2; }

    static DisplayMode select(DisplayMode[] modes, DisplayMode current, int width, int height, int depth) {
        if (current == null) return null;
        if (width <= 0 || height <= 0) { width = current.getWidth(); height = current.getHeight(); }
        DisplayMode best = null;
        long bestScore = Long.MAX_VALUE;
        for (DisplayMode mode : modes) {
            if (mode.getWidth() != width || mode.getHeight() != height) continue;
            if (depth != 0 && mode.getBitDepth() != depth && mode.getBitDepth() != DisplayMode.BIT_DEPTH_MULTI) continue;
            long score = Math.abs((long) mode.getRefreshRate() - current.getRefreshRate());
            if (mode.getBitDepth() != current.getBitDepth()) score += 1000;
            if (score < bestScore) { best = mode; bestScore = score; }
        }
        // Some drivers omit their current desktop mode from enumeration; it is already active and valid.
        if (best == null && current.getWidth() == width && current.getHeight() == height &&
                (depth == 0 || depth == current.getBitDepth() || current.getBitDepth() == DisplayMode.BIT_DEPTH_MULTI)) return current;
        return best;
    }

    static RuntimeException cleanup(Runnable... steps) {
        RuntimeException failure = null;
        for (Runnable step : steps) try { step.run(); }
        catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        return failure;
    }
}
