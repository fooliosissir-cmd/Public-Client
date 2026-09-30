# Burial Grounds — Android

## Current development APK

**0.2.17-dev**

[Download the direct APK](https://github.com/fooliosissir-cmd/Public-Client/releases/download/android-v0.2.17-dev/BurialGrounds-Android-0.2.17-dev.apk)

[Release notes, SHA-256, and candidate manifest](https://github.com/fooliosissir-cmd/Public-Client/releases/tag/android-v0.2.17-dev)

This build is produced from the current customized Burial Grounds client source. It includes:

- ordered native pointer down/move/up input;
- long-press/right-click held correctly until finger release;
- native context-menu hover and row selection tracking;
- camera/menu gesture arbitration;
- atomic renderer/input viewport geometry;
- normal-frame diagnostic scans disabled unless explicitly enabled;
- bounded/coalesced Android motion input;
- enlarged Android-only native context-menu row spacing and touch mapping;
- a collapsible Burial Grounds mobile action strip for Inventory, Equipment, Prayer, Magic, Skills, and Quests;
- persistent native logical-canvas scale profiles;
- a live verified in-app updater channel restricted to Burial Grounds `Public-Client` release APKs;
- renderer instrumentation for submitted/copied/dropped/swapped/uploaded/presented frames and copy/upload timing;
- duplicate Android draws reuse the existing GLES texture instead of re-uploading the same full framebuffer.

The live updater manifest now points to **0.2.17-dev**.

The build pipeline, APK assembly, checksum generation, GitHub publication, and updater manifest publication passed. This remains a development candidate until real-device gameplay smoke testing is complete.

The old **0.2.11-dev** APK is withdrawn and should not be used.
