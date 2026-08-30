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

# --- Open FPV Cast ---
# Minification is disabled (minifyEnabled false in build.gradle). It was tried and reverted:
# with R8 full mode (the AGP 8 default) the release build crashed the moment the app asked for
# the microphone permission --
#
#   NullPointerException at Plugin.requestPermissionForAliases
#     <- DjiAoaPlugin.requestAudioPermission
#
# because requestPermissionForAlias("microphone", ...) resolves the alias by reading the
# @CapacitorPlugin(permissions = [@Permission(alias = "microphone", ...)]) annotation at runtime.
# Capacitor's consumer rules keep the plugin class and its methods, and the default config keeps
# RuntimeVisibleAnnotations, but full mode still drops the annotation because the annotation type
# itself is not kept -- so the alias lookup returned null.
#
# It was never worth much anyway: R8 shrank classes.dex from 2.73 MB to 0.56 MB, but that is 2 MB
# out of a 47 MB APK -- the other 43 MB is libwebrtc's native libraries, which R8 does not touch.
#
# To try again, these are the rules it needs (and a hardware pass over connect / camera settings /
# record with mic / WebRTC broadcast to confirm nothing else reflective broke):
#
#   -keep @interface com.getcapacitor.annotation.**
#   -keep class com.getcapacitor.annotation.** { *; }
#   -keepattributes *Annotation*
