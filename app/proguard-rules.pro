# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Credential Manager finds its Google Play services provider by reflection, so R8 can't see that
# it's used. Without this, Google sign-in fails in release builds. See
# https://developer.android.com/identity/sign-in/credential-manager#proguard
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}

# Obfuscated stack traces stay readable with the mapping.txt R8 writes next to the APK, which is
# kept out of the APK itself.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager creates its Room database by reflection through the no-argument constructor of the
# generated WorkDatabase_Impl. R8 strips that constructor from the release build, which crashes the
# app at startup (androidx.startup.InitializationProvider).
-keep class androidx.work.impl.WorkDatabase_Impl {
  <init>();
}
