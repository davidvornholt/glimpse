# Android toolchain for apps/android. The flake passes its nixpkgs, which
# doesn't accept the Android SDK license, so this file imports the same
# nixpkgs again with the license accepted.
{ pkgs }:
let
  androidPkgs = import pkgs.path {
    inherit (pkgs.stdenv.hostPlatform) system;
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  };
  # Keep these in step with compileSdk and the build-tools that the GitHub
  # runner image ships, so local and CI builds use the same SDK.
  buildToolsVersion = "37.0.0";
  android = androidPkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "37.0" ];
    buildToolsVersions = [ buildToolsVersion ];
    includeCmake = false;
    includeEmulator = false;
    includeNDK = false;
    includeSystemImages = false;
  };
  sdk = "${android.androidsdk}/libexec/android-sdk";
  jdk = androidPkgs.jdk21_headless;
in
{
  packages = [
    android.androidsdk
    jdk
  ];
  ANDROID_HOME = sdk;
  ANDROID_SDK_ROOT = sdk;
  JAVA_HOME = jdk.home;
  # AGP otherwise downloads a dynamically linked aapt2 from Maven, which runs
  # on NixOS only through nix-ld. The SDK's aapt2 is patched for Nix.
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
}
