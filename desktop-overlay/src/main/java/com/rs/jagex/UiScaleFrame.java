package com.rs.jagex;

/** One retained hardware target; native scene, UI, clipping and pointer coordinates share its size. */
final class UiScaleFrame {
    private static AbstractRenderer owner;
    private static Class158_Sub1 target;
    private static NativeSprite color;
    private static Interface8 depth;
    private static UiScale.Geometry geometry;
    private static boolean drawing;
    private static boolean frameOpen;

    private UiScaleFrame() { }

    static void begin(AbstractRenderer renderer) {
        if (drawing) finish(owner);
        UiScale.Geometry next = UiScale.active();
        if (renderer == null || !next.scaled()) {
            release(owner);
            return;
        }
        try {
            boolean newFrame = owner != renderer || geometry == null || geometry.logicalWidth() != next.logicalWidth()
                    || geometry.logicalHeight() != next.logicalHeight();
            if (newFrame) {
                release(owner);
                if (!(renderer instanceof OpenGLRenderer || renderer instanceof HardwareRenderer) || !renderer.method8455())
                    throw new IllegalStateException("Hardware render targets unavailable");
                owner = renderer;
                geometry = next;
                color = renderer.method8654(next.logicalWidth(), next.logicalHeight(), false, true);
                target = renderer.method8418();
                target.method13759(0, color.method2808());
                depth = renderer.method8419(next.logicalWidth(), next.logicalHeight());
                target.method13765(depth);
                renderer.method8637(target);
                if (!target.method13764()) throw new IllegalStateException("Incomplete game scaling target");
                renderer.r(0, 0, next.logicalWidth(), next.logicalHeight());
                renderer.ba(3, 0xff000000);
                java.util.Arrays.fill(client.IF_COMPONENTS_TO_RENDER, true);
            } else {
                geometry = next;
                renderer.method8637(target);
                renderer.r(0, 0, next.logicalWidth(), next.logicalHeight());
                if (!frameOpen) {
                    renderer.ba(3, 0xff000000);
                    java.util.Arrays.fill(client.IF_COMPONENTS_TO_RENDER, true);
                }
            }
            drawing = true;
            frameOpen = true;
        } catch (RuntimeException failure) {
            release(owner);
            UiScale.unavailable(renderer, failure);
            // Keep native canvas/layout/pointer geometry consistent even on allocation failure.
            UiScale.pollPreference();
        }
    }

    /** Called by the common present entry point and the render-frame finally block. */
    static void present(AbstractRenderer renderer) {
        if (!drawing || renderer != owner || target == null) return;
        if (renderer.method8523() != target) return; // A nested map/scene target still owns the stack.
        try {
            renderer.method8416(target);
            drawing = false;
            renderer.L();
            renderer.r(0, 0, geometry.physicalWidth(), geometry.physicalHeight());
            // method2756 tiles at source size; method2789 stretches one image to the surface.
            color.method2789(0, 0, geometry.physicalWidth(), geometry.physicalHeight());
        } catch (RuntimeException failure) {
            release(renderer);
            UiScale.unavailable(renderer, failure);
        }
    }

    /** End-of-frame only: a failed nested scene draw must not strand our target on the stack. */
    static void finish(AbstractRenderer renderer) {
        frameOpen = false;
        if (!drawing || renderer != owner || target == null) return;
        try {
            while (onStack(renderer, target) && renderer.anInt5854 >= 0
                    && renderer.aClass158_Sub1Array5833[renderer.anInt5854] != target)
                renderer.method8416(renderer.aClass158_Sub1Array5833[renderer.anInt5854]);
            present(renderer);
        } catch (RuntimeException failure) {
            release(renderer);
            UiScale.unavailable(renderer, failure);
        }
    }

    /** Loading messages can present mid-frame; any later overlays still use logical coordinates. */
    static void afterPresent(AbstractRenderer renderer) {
        if (frameOpen && !drawing && renderer == owner && target != null) begin(renderer);
    }

    private static boolean onStack(AbstractRenderer renderer, Class158_Sub1 frame) {
        for (int i = 0; i <= renderer.anInt5854; i++)
            if (renderer.aClass158_Sub1Array5833[i] == frame) return true;
        return false;
    }

    private static boolean stillBound(AbstractRenderer renderer, Class158_Sub1 frame) {
        return frame != null && (onStack(renderer, frame) || renderer.method8523() == frame);
    }

    static void release(AbstractRenderer renderer) {
        if (renderer == null || renderer != owner) return;
        drawing = false;
        frameOpen = false;
        Class158_Sub1 oldTarget = target;
        Interface8 oldDepth = depth;
        NativeSprite oldColor = color;
        target = null;
        depth = null;
        color = null;
        geometry = null;
        owner = null;
        // Push changes the renderer stack before native bind can fail; inspect actual ownership.
        RuntimeException failure = FullscreenSupport.cleanup(
                () -> {
                    while (oldTarget != null && onStack(renderer, oldTarget))
                        renderer.method8416(renderer.aClass158_Sub1Array5833[renderer.anInt5854]);
                },
                () -> { if (oldTarget != null && !stillBound(renderer, oldTarget)) oldTarget.method212(); },
                () -> { if (oldDepth != null && !stillBound(renderer, oldTarget)) oldDepth.method26(); },
                () -> {
                    if (stillBound(renderer, oldTarget)) return; // Do not free a resource still bound after a native failure.
                    if (oldColor instanceof OpenGLSprite sprite) sprite.aClass137_Sub1_Sub1_9033.method2378();
                    else if (oldColor instanceof NativeSprite_Sub3 sprite) sprite.anInterface6_9050.method26();
                });
        if (failure != null) System.err.println("Game scaling cleanup: " + failure.getClass().getSimpleName());
    }
}
