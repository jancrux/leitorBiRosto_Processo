# Retrofit + kotlinx.serialization
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-keep,includedescriptorclasses class pt.leiturabi.**$$serializer { *; }
-keepclassmembers class pt.leiturabi.** { *** Companion; }
-keepclasseswithmembers class pt.leiturabi.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
