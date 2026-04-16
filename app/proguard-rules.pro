# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Gson
-keepattributes *Annotation*
-keep class com.rahga.x2rock.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
