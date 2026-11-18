#!/usr/bin/env bash
set -e

# Automatically detect Java from Android Studio JBR if not set
if [ -z "$JAVA_HOME" ]; then
    if [ -d "$HOME/android-studio/jbr" ]; then
        export JAVA_HOME="$HOME/android-studio/jbr"
    elif [ -d "/opt/android-studio/jbr" ]; then
        export JAVA_HOME="/opt/android-studio/jbr"
    fi
fi

# Automatically detect Android SDK if not set
if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi

echo "============================================="
echo " Building Brave Music Release APK"
echo " JAVA_HOME:    $JAVA_HOME"
echo " ANDROID_HOME: $ANDROID_HOME"
echo "============================================="

# Build the release APK
./gradlew assembleRelease

# Free RAM immediately by stopping Gradle daemons
./gradlew --stop

APK_PATH="app/build/outputs/apk/release/app-release.apk"

if [ -f "$APK_PATH" ]; then
    APK_SIZE=$(ls -lh "$APK_PATH" | awk '{print $5}')
    echo ""
    echo "============================================="
    echo "  BUILD SUCCESSFUL!"
    echo "  APK Location: $APK_PATH"
    echo "  Size:         $APK_SIZE"
    echo "============================================="
    echo ""
    echo "To install directly onto your connected phone, run:"
    echo "  $ANDROID_HOME/platform-tools/adb install -r $APK_PATH"
fi
