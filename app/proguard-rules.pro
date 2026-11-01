# iBoard R8 rules. Everything not listed here is shrunk and obfuscated.

# Readable crash reports in Play Console (the mapping file is uploaded with the bundle).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.gr4nt.iboard.voice.WhisperLib { *; }
