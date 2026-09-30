# Burial Grounds — Android

The old public **0.2.11-dev** APK is withdrawn and should not be used. It was not built from the complete current Burial Grounds customized client source.

## Current replacement candidate

The current private development candidate is **0.2.14-dev**, built from the current Burial Grounds client source.

It includes:

- ordered native pointer down/move/up input;
- long-press/right-click held correctly until finger release;
- native context-menu hover and row selection tracking;
- camera/menu gesture arbitration;
- atomic renderer/input viewport geometry;
- normal-frame diagnostic scans disabled unless explicitly enabled;
- bounded/coalesced Android motion input;
- Android-only enlarged native context-menu row spacing and touch mapping;
- a collapsible Burial Grounds mobile action strip for Inventory, Equipment, Prayer, Magic, Skills, and Quests, routed through the native revision-727 tab script on the engine thread.

The source preparation, Java compile, APK assembly, artifact upload, checksum generation, and GitHub prerelease publication all pass.

The public APK remains withheld until this candidate is smoke-tested on a real device against the live Main World. Once it passes, the direct `.apk` here will be replaced with the verified current build.
