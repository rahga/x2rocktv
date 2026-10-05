# Gson reads the speakers' JSON into this project's own classes by reflection, across
# :core's lan, model and radio packages and :app's smapi, so their field names are the wire
# format and must survive shrinking. Kept wholesale rather than per class: a class missed
# here does not fail, it parses to nulls, which is far harder to find than a crash.
-keep class com.rahga.x2rock.** { <fields>; <init>(...); }

# Gson's TypeToken reads its type argument from the generic signature. Without these the
# release build died creating the Application ("TypeToken must be created with a type
# argument"), found 2026-10-05 the first time one was run.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
