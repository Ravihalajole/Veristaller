# libsu
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.veristaller.data.** {
    *** Companion;
}
-keepclasseswithmembers class dev.veristaller.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
