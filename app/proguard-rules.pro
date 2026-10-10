# Fingerprint 混淆规则
-keep class com.limao.fingerprint.** { *; }
# Gson 反射用的 data class 保留字段名
-keepclassmembers class com.limao.fingerprint.data.** { *; }
# DataStore / WebView 反射相关不混淆
-keep class androidx.datastore.** { *; }
-dontwarn android.net.**
