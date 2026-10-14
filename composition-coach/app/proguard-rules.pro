# Composition Coach release (R8/ProGuard) rules.
#
# Applied on top of the AGP-bundled proguard-android-optimize.txt. Most of the app's own code
# (Compose, ViewModels, the :composition/:vision contracts) needs nothing extra — it is called
# directly, never via reflection. The rules below cover the places that genuinely do reach across a
# boundary R8 can't see through: enum-name round-tripping in settings persistence, and the small set
# of Google/AndroidX libraries whose Kotlin/Java interop or JNI native code isn't visible to the
# shrinker from bytecode alone.

# --- :composition model classes ------------------------------------------------------------------
# CoachSettings persists GuidanceLevel/SceneIntent as plain strings (DataStore) and reads them back
# with Enum.valueOf(Class, name) via GuidanceLevelCodec/SceneIntentCodec. R8's default enum
# optimizations can rename/strip values()/valueOf() when it doesn't see a direct call site through the
# generic Enum API, so keep every :composition model enum's name-based members explicitly. Everything
# else in :composition/:vision is called by direct reference (data classes, pure functions) and needs
# no keep rules — verified: no Gson/kotlinx.serialization/Class.forName/Parcelable usage anywhere in
# either module.
-keepclassmembers enum com.compositioncoach.composition.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- CameraX ---------------------------------------------------------------------------------------
# CameraX's own AARs ship consumer-rules that cover the bulk of this, but its Camera2 interop layer
# and some extensions classes are looked up by class name from native/vendor code on certain OEMs.
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# --- ML Kit (face, pose, object detection, selfie segmentation) ------------------------------------
# ML Kit's native (JNI) detector implementations look up their Java model/option classes by name and
# are not visible to R8 as bytecode call sites.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_pose.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_objects.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_segmentation_selfie.** { *; }
-keep class com.google.mlkit.vision.face.internal.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# --- kotlinx.coroutines ------------------------------------------------------------------------------
# The coroutines-core/android artifacts ship their own consumer rules for the ServiceLoader-based
# MainDispatcherFactory; this only silences shrinker warnings about optional JVM-only internals that
# never run on Android (there is no android.os.Build check dead-code path to warn about).
-dontwarn kotlinx.coroutines.debug.**
-dontwarn kotlinx.coroutines.internal.ExceptionsConstructorKt

# --- AndroidX DataStore (Preferences) ---------------------------------------------------------------
# DataStore's Preferences implementation is backed by a small generated protobuf schema.
-keep class androidx.datastore.*.** { *; }
-dontwarn androidx.datastore.**

# --- Compose ------------------------------------------------------------------------------------------
# Compose's own libraries ship consumer-rules (they keep @Composable function metadata etc.); nothing
# additional is needed here. Kept as an explicit, documented no-op so a future R8 "missing_rules.txt"
# warning about Compose internals is easy to route to the right place.
-dontwarn androidx.compose.**
