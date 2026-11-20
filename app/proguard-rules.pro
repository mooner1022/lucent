# kotlinx.serialization: 직렬화기가 쓰는 메타데이터를 남긴다.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class dev.mooner.lucent.** { kotlinx.serialization.KSerializer serializer(...); }
