# iBoard R8 rules. Everything not listed here is shrunk and obfuscated.

# Offline speech: the JNI functions in libiboard_whisper.so are bound by class and method name.
-keepclasseswithmembernames,includedescriptorclasses class com.gr4nt.iboard.voice.WhisperLib {
    native <methods>;
}

# MediaPipe LLM Inference (Rambler writing model) calls back into Java from native code
# and reads protobuf messages reflectively.
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# Readable crash reports in Play Console (the mapping file is uploaded with the bundle).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
