# vr-mode-shim

An LSPosed/Xposed module scoped to the system framework. Android 16 removed `VrManagerService` but kept `Activity.setVrModeEnabled`, so on a device that declares the VR features (nothing1, for Google's Daydream View) the call reaches `ActivityClientController.setVrMode`, which dereferences the missing `VrManagerInternal` and throws a `NullPointerException` back into the app. Daydream's VR launcher and every GVR title call it in `onCreate`. The hook answers the call with success when no VR manager is registered and leaves it alone otherwise.

Build on volt (x86_64 only, for aapt2): `nix develop -c ./gradlew assembleDebug`, APK at `app/build/outputs/apk/debug/app-debug.apk`. Enable it in Vector with scope `system`, then restart the framework.
