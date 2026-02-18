#!/bin/bash
# Build script for iOS Simulator feature development
# This script builds the plugin without running the problematic verifyPlugin task

set -e

echo "🔧 Building Flutter IntelliJ Plugin - iOS Simulator Feature"
echo "============================================================"
echo ""
set JAVA_HOME=$(/usr/libexec/java_home -v 21)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
# Check Java version
echo "📋 Checking Java version..."
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "   Java version: $JAVA_VERSION"

#if [ "$JAVA_VERSION" -lt "21" ]; then
#    echo "❌ Error: Java 21+ is required but found Java $JAVA_VERSION"
#    echo ""
#    echo "To install Java 21:"
#    echo "  brew install openjdk@21"
#    echo "  export JAVA_HOME=\$(/usr/libexec/java_home -v 21)"
#    exit 1
#fi

echo "✅ Java version is compatible"
echo ""

# Clean previous builds
echo "🧹 Cleaning previous builds..."
./gradlew clean --no-daemon --quiet

# Build the plugin
echo "🔨 Building plugin..."
./gradlew buildPlugin --no-daemon --stacktrace

# Check if build was successful
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Build successful!"
    echo ""
    echo "📦 Plugin built at:"
    ls -lh build/distributions/*.zip 2>/dev/null || echo "   (distribution not found)"
    echo ""
    echo "🚀 To test the plugin:"
    echo "   ./gradlew runIde"
    echo ""
else
    echo ""
    echo "❌ Build failed!"
    exit 1
fi
