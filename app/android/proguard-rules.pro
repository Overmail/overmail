# ML Kit's bundled barcode model (barhopper) is protobuf-lite, which reads its message fields
# reflectively by name. R8 renames and merges them, and the scanner then fails with a
# NullPointerException in release builds only.
-keep class com.google.barhopper.** { *; }
-keep class com.google.photos.vision.barhopper.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.mlkit.** { *; }
