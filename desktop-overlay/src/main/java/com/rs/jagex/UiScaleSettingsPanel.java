package com.rs.jagex;

/** Bounded compatibility shim — retired panel replaced by no-op stub.

 *  Retained method signatures only; no widgets appended, no preference changes.
 *  All native actions forwarded (return false). */
final class UiScaleSettingsPanel {

    /* --- retained public API (no-ops) ----------- */

    static void resetForService() { }

    static void append(int interfaceId, Interface owner) {
        // formerly injected game-scale controls for interfaces 742/882; now a stub.
    }

    static void refresh() {
        // formerly adjusted geometry/redraw of installed panels.
    }

    static void nativeHook(HookRequest hook) {
        // formerly closed the selector on raw mouse hooks; no longer relevant.
    }

    /** Forward to downstream handler — return {@code false} so nothing is consumed. */
    static boolean handle(int operation, int interfaceHash, int slotId) {
        return false;
    }
}
