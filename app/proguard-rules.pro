# Add project specific ProGuard rules here.

# MediaPipe
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep data classes used in intents
-keep class com.antivocale.app.data.** { *; }

# Annotation processing classes (not needed at runtime)
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**

# sherpa-onnx: Keep classes that native code accesses via reflection.
# TASK-883: this rule's keepclassmembers twin was removed; the R8 config
# analyzer proved it subsumed by this rule on both flavors.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# LiteRT-LM: Keep classes that native code accesses via reflection/JNI.
# TASK-883: the package-wide keepclassmembers and the two per-class member
# rules that lived here were removed; the R8 config analyzer proved all three
# subsumed by this section's class rule on both flavors (a keepclassmembers on
# the same pattern cannot keep anything the class rule does not already).
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Preserve Kotlin metadata for JNI reflection (sherpa-onnx native bridge)
-keepattributes *Annotation*, InnerClasses, Signature
-keepattributes Exceptions

# Keep transcription backend classes (registered dynamically via map)
-keep class com.antivocale.app.transcription.** { *; }

# Keep WorkManager workers (instantiated by class name via WorkManager/Hilt at runtime,
# so R8's static tracer can't see them) — e.g. SubtitleChoiceTimeoutWorker (TASK-228.9)
-keep class com.antivocale.app.work.** { *; }

# Preserve @Keep annotations that native libraries rely on.
# TASK-883: the keepclassmembers-on-@Keep-members companion rule was removed;
# the R8 config analyzer showed it keeping zero items on both flavors, and the
# androidx.annotation consumer rules (present in the merged R8 config) already
# pin @Keep members, so the annotation's protection does not rest on this file.
-keep @androidx.annotation.Keep class * { *; }

# TASK-785: the Open Transcribe contract surface. The framework (and the
# client app across the binder) resolves each Parcelable's CREATOR
# reflectively, so the generated classes must survive R8 untouched.
-keep class org.opentranscribe.api.** { *; }
