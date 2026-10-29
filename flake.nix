{
  description = "vr-mode-shim — LSPosed/Xposed module; Nix devShell providing the Android SDK + JDK 17, Gradle driven by the repo's own wrapper";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    # x86_64-linux only, on purpose: volt is the Android build host. The SDK's
    # aapt2 is an x86_64 binary with no working aarch64 build on Asahi (16KB
    # pages reject the static aarch64 aapt2), so building on riva is a dead end.
    # Build on volt, deploy the APK over the tailnet.
    flake-utils.lib.eachSystem [ "x86_64-linux" ] (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Matches app/build.gradle.kts: compileSdk 35 / buildTools 35.0.0. The
        # module compiles against SDK 35 and runs fine on the SDK-36 handset —
        # an Xposed module's compileSdk is not bound to the device API.
        buildToolsVersion = "35.0.0";

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "35" ];
          buildToolsVersions = [ buildToolsVersion ];
          includeNDK = false;
          includeSystemImages = false;
          includeEmulator = false;
        };

        androidSdk = androidComposition.androidsdk;
        sdkRoot = "${androidSdk}/libexec/android-sdk";
        jdk = pkgs.jdk17_headless;
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            androidSdk
            jdk
            pkgs.android-tools
          ];

          env = {
            JAVA_HOME = "${jdk}";
            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;
            # Point AGP at the SDK's aapt2 instead of the x86 one it unpacks from
            # the Gradle-cached Maven jar, so nothing tries to mutate the store.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";
          };

          shellHook = ''
            export PATH="${sdkRoot}/platform-tools:$PATH"
            echo "vr-mode-shim toolchain (nix):"
            echo "  ANDROID_HOME = $ANDROID_HOME"
            echo "  build-tools  = ${buildToolsVersion}   platform = android-35"
            echo "  JAVA_HOME    = $JAVA_HOME"
            echo
            echo "  build the module:  ./gradlew assembleDebug"
            echo "  APK:               app/build/outputs/apk/debug/app-debug.apk"
          '';
        };
      }
    );
}
