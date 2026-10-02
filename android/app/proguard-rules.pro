# Keep Filament JNI and Native Bindings
-keep class com.google.android.filament.** { *; }
-keep class com.google.android.filament.utils.** { *; }
-keep class com.google.android.filament.gltfio.** { *; }

# Keep Supabase & Ktor Serialization
-keepattributes *Annotation*, InnerClasses
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**
