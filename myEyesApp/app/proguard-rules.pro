# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep TFLite classes
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**
