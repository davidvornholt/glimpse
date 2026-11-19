# kotlinx.serialization, OkHttp, Coil, CameraX and WorkManager ship their own
# consumer rules, so the app needs none of its own for them.

# ML Kit makes its component registrars by reflection, from the class names in
# the manifest. firebase-components' rule keeps the classes but not their
# constructors, which R8's full mode then removes, so BarcodeScanning.getClient
# crashes the release build.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
