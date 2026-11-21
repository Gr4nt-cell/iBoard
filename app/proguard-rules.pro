# iBoard R8 rules. Everything not listed here is shrunk and obfuscated.

# Readable crash reports in Play Console (the mapping file is uploaded with the bundle).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.gr4nt.iboard.voice.WhisperLib { *; }

# Gemini Nano (ML Kit GenAI): keep its classes so R8 doesn't strip anything AICore calls back into.
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**
