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

# sherpa-onnx: Keep classes that native code accesses via reflection
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# LiteRT-LM: Keep classes that native code accesses via reflection/JNI
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
# Explicitly keep SamplerConfig getters/setters (JNI access)
-keepclassmembers class com.google.ai.edge.litertlm.SamplerConfig {
    public int getTopK();
    public float getTemperature();
    public int getTopP();
}
# Also keep all public methods in LiteRtLmJni
-keepclassmembers class com.google.ai.edge.litertlm.LiteRtLmJni {
    public native <methods>;
}
-dontwarn com.google.ai.edge.litertlm.**

# Preserve Kotlin metadata for JNI reflection (sherpa-onnx native bridge)
-keepattributes *Annotation*, InnerClasses, Signature
-keepattributes Exceptions

# Keep transcription backend classes (registered dynamically via map)
-keep class com.antivocale.app.transcription.** { *; }

# Keep WorkManager workers (instantiated by class name via WorkManager/Hilt at runtime,
# so R8's static tracer can't see them) — e.g. SubtitleChoiceTimeoutWorker (TASK-228.9)
-keep class com.antivocale.app.work.** { *; }

# Preserve @Keep annotations that native libraries rely on
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * { @androidx.annotation.Keep *; }

# GGUF: disabled — uncomment all below when re-enabling llama-bro
# -keep class com.antivocale.app.llm.** { *; }
# -keepclassmembers class com.antivocale.app.llm.** { *; }
# -keep class com.suhel.llamabro.** { *; }
# -keepclassmembers class com.suhel.llamabro.** { *; }
# -dontwarn com.suhel.llamabro.**
