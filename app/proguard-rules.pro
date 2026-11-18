# Keep JNI native methods for BraveShield
-keepclasseswithmembernames class com.bravemusic.player.adblock.BraveShield {
    native <methods>;
}

# Keep Media3 session classes
-keep class androidx.media3.session.** { *; }
