package com.rs.jagex;

final class CameraWheelZoom {
    private static int pending;

    static void beginFrame() {
        pending = 0;
    }

    static void cancel() {
        pending = 0;
    }

    static void nominate(int delta) {
        pending = delta;
    }

    static int apply(int current, boolean allowed) {
        int delta = pending;
        pending = 0;

        if (!allowed || delta == 0) {
            return current;
        }

        long result = (long) current + (long) delta * 30;
        if (result < -50) {
            return -50;
        }
        if (result > 1700) {
            return 1700;
        }
        return (int) result;
    }
}
