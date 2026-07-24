# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Kotlinx Serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class xyz.chenmilin.ankimcpbridge.**$$serializer { *; }
-keepclassmembers class xyz.chenmilin.ankimcpbridge.** { *** Companion; }
-keepclasseswithmembers class xyz.chenmilin.ankimcpbridge.** { kotlinx.serialization.KSerializer serializer(...); }
