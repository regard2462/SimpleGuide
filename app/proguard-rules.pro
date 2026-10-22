# sherpa-onnx JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }
# Commons Compress: optionale Kompressionsbibliotheken
-dontwarn org.tukaani.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
# MapLibre: SupportMapFragment wird nicht benutzt, androidx.fragment ist ausgeschlossen
-dontwarn androidx.fragment.**
