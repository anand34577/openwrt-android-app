# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.openwrtmgr.app.**$$serializer { *** INSTANCE; }

# sshj — resolves cipher/kex/mac/signature/compression algorithms by reflection (service-provider
# style), so r8 stripping unused-looking classes silently breaks SSH auth/handshake. Not yet run
# through an actual `assembleRelease` build to confirm this is sufficient — verify before shipping.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
