# KERNELIS keeps Room entities, kotlinx-serializable models and Hilt modules intact.

# kotlinx.serialization: keep generated serializers
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }
-keepclasseswithmembers class dev.kernelis.** {
    public static ** Companion;
}
-keep,includedescriptorclasses class dev.kernelis.**$$serializer { *; }

# Room entities are accessed via reflection in some paths
-keep class dev.kernelis.data.db.** { *; }

# SuProcess root provider detection uses plain Runtime.exec, no reflection to keep.
-dontwarn org.slf4j.**
