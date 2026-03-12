# Add project specific ProGuard rules here.

# MediaPipe
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# FFmpegKit
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

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
