# Third-party binaries in `local-dream`

Transplanted from **Off Grid AI** (`https://github.com/off-grid-ai/OGAM`, MIT License) at
commit `0957b72`, fetched 2026-11-08. The Kotlin bridge (`LocalDreamEngine.kt`) is a port of
their `android/app/src/main/java/ai/offgridmobile/localdream/LocalDreamModule.kt` under the
same MIT grant; modifications: RN bridge → plain engine class + coroutine Flow.

## Prebuilt binaries (not built by our CI)

SHA-256 of every shipped blob, as vendored:

| file | bytes | sha256 |
|---|---|---|
| `jniLibs/arm64-v8a/libstable_diffusion_core.so` | 12,050,832 | `794127b7b6ef26273abbbe9d366ac4d46ef534d5be699a968e7b52f0c1d8879f` |
| `assets/qnnlibs/libQnnHtp.so` | 2,433,400 | `5020af677e221f1673271ac7be32efbf861b152ec71c021bd13827ec94fd10e1` |
| `assets/qnnlibs/libQnnHtpV73.so` | 12,006,592 | `a0e9f8b303ccd3317be867d1476576593f0f2f00399d505b5664511e726e45ac` |
| `assets/qnnlibs/libQnnHtpV73Skel.so` | 9,495,800 | `ad90c8689cceae8284a51dcf86d7b5140a33b6d089c85187b62014e76be45c5d` |
| `assets/qnnlibs/libQnnHtpV73Stub.so` | 536,960 | `f9ba0c8234e3d75083d989660fda56dd9c7b813e61a0c5140681f106c27ed04c` |
| `assets/qnnlibs/libQnnSystem.so` | 2,445,944 | `e31c8e0b6e2d38be46b21fc3ce9c26d8532e8d0623dc7407f4c90248b040d795` |

`libstable_diffusion_core.so` is the MIT-licensed engine subprocess (QNN + MNN runtimes
linked in). The `libQnn*` set is the Qualcomm AI Engine Direct (QNN) runtime redistribution
under Qualcomm's license terms; we ship **only the v73 generation** (the Fold's SM8650 /
Snapdragon 8 Gen 3 class) instead of OGAM's full v68–v81 matrix, saving ~123 MB per APK.

## Model packs

Downloaded at runtime from `https://huggingface.co/xororz/*` (see `LocalDreamCatalog.kt`);
not vendored here.
