#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$PROJECT_DIR/adblock-core"
JNI_LIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs"

echo "=== Building BraveShield Core for Android ==="

# Supported ABIs
TARGETS=(
    "aarch64-linux-android:arm64-v8a"
    "armv7-linux-androideabi:armeabi-v7a"
    "x86_64-linux-android:x86_64"
)

# Check if cargo-ndk is installed
if ! command -v cargo-ndk &> /dev/null; then
    echo "cargo-ndk not found. Installing cargo-ndk..."
    cargo install cargo-ndk || true
fi

cd "$CORE_DIR"

for item in "${TARGETS[@]}"; do
    TARGET="${item%%:*}"
    ABI="${item##*:}"
    TARGET_DIR="$JNI_LIBS_DIR/$ABI"

    mkdir -p "$TARGET_DIR"

    echo "Compiling for ABI: $ABI ($TARGET)..."
    if command -v cargo-ndk &> /dev/null; then
        cargo ndk --target "$TARGET" --platform 26 build --release
        cp "$PROJECT_DIR/adblock-core/target/$TARGET/release/libbrave_adblock_core.so" "$TARGET_DIR/" || true
    else
        echo "Note: Install Android NDK and cargo-ndk to generate native .so libraries."
    fi
done

echo "=== Native build step complete ==="
