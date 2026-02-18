# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/ryan/sdk/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard/index.html

# Add any project specific keep rules here:

# If you use Gson, add these rules:
-keep class com.google.gson.** { *; }

# If you use Jsoup, add these rules:
-keep class org.jsoup.** { *; }

# Coil usually works out of the box, but these might be needed:
-keep class coil.** { *; }
-dontwarn coil.**
-dontwarn okio.**
-dontwarn org.jspecify.annotations.**
