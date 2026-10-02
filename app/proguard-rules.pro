# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.openwrtmgr.app.**$$serializer { *** INSTANCE; }

# androidx.security-crypto pulls in Tink, which references compile-time-only annotations.
-dontwarn com.google.errorprone.annotations.**
