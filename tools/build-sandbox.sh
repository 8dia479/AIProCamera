#!/usr/bin/env bash
# Sandbox-only build helper.
#
# Why this exists: the workspace snapshot only persists ~128 MB / 10k files and excludes
# directories named .cache, build, node_modules, ... but NOT ~/.gradle. A Gradle cache is
# several hundred MB to ~1 GB, so keeping it inside the workspace makes the snapshot fail.
# This script therefore keeps every heavyweight by-product outside the persisted tree.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)

export JAVA_HOME=/home/user/.cache/toolchain/jdk17
export ANDROID_HOME=${ANDROID_HOME:-/home/user/.cache/toolchain/sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=/home/user/.cache/gradle-home   # NOT persisted on purpose
export TMPDIR=/home/user/.cache/tmp
mkdir -p "$GRADLE_USER_HOME" "$TMPDIR"

cd "$ROOT"
./gradlew "$@" --no-daemon --console=plain \
  -Dorg.gradle.jvmargs="-Xmx1350m -XX:+UseSerialGC -Djava.io.tmpdir=$TMPDIR"

# .cxx holds CMake output and would count towards the snapshot; rebuilds regenerate it.
rm -rf "$ROOT/core/native/.cxx"
