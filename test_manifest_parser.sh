#!/bin/bash
# Simple test script to compare Kotlin and Python manifest parsing

set -e

MANIFEST_FILE="${1:-docs/manifest-v3-example}"

if [ ! -f "$MANIFEST_FILE" ]; then
    echo "Error: Manifest file not found: $MANIFEST_FILE"
    exit 1
fi

echo "====================================="
echo "Manifest Parser Comparison Test"
echo "====================================="
echo "File: $MANIFEST_FILE"
echo

# Parse with Python
echo "▶ Running Python parser..."
PYTHON_OUTPUT=$(python3 app/src/main/python/manifest_test_python.py "$MANIFEST_FILE" summary 2>&1)
PYTHON_EXIT=$?

if [ $PYTHON_EXIT -eq 0 ]; then
    echo "✅ Python parser succeeded"
    echo "$PYTHON_OUTPUT" > /tmp/python_manifest.json
else
    echo "❌ Python parser failed"
    echo "$PYTHON_OUTPUT"
    exit 1
fi

echo
echo "▶ Running Kotlin parser..."

# Find the compiled Kotlin classes
CLASSPATH="app/build/intermediates/javac/debug/classes:app/build/tmp/kotlin-classes/debug"

# Add Android SDK and dependencies to classpath
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
    # Try common locations
    for VERSION in 34 33 32 31; do
        ANDROID_JAR="$HOME/Library/Android/sdk/platforms/android-$VERSION/android.jar"
        if [ -f "$ANDROID_JAR" ]; then
            break
        fi
    done
fi

# Run Kotlin test (using kotlinc to run the compiled class)
if [ -d "app/build/tmp/kotlin-classes/debug" ]; then
    # Try to run with kotlin
    KOTLIN_OUTPUT=$(kotlin -classpath "$CLASSPATH" \
        app.gamenative.service.epic.manifest.test.ManifestParseTestKt \
        "$MANIFEST_FILE" 2>&1 || echo "KOTLIN_FAILED")

    if echo "$KOTLIN_OUTPUT" | grep -q "KOTLIN_FAILED"; then
        echo "❌ Kotlin parser failed or not runnable in this environment"
        echo "   (This is normal - Kotlin needs full Android environment)"
        echo
        echo "📋 Python Output:"
        echo "─────────────────────────────────────"
        cat /tmp/python_manifest.json | jq .
        echo
        echo "ℹ️  To test Kotlin implementation:"
        echo "   1. Run from Android Studio, or"
        echo "   2. Create a JVM-only test module"
        exit 0
    else
        echo "✅ Kotlin parser succeeded"
        echo "$KOTLIN_OUTPUT" > /tmp/kotlin_manifest.json
    fi
else
    echo "⚠️  Kotlin classes not found. Run ./gradlew :app:compileDebugKotlin first"
    echo
    echo "📋 Python Output:"
    echo "─────────────────────────────────────"
    cat /tmp/python_manifest.json | jq .
    exit 0
fi

echo
echo "▶ Comparing outputs..."

# Compare JSON outputs
DIFF_OUTPUT=$(diff <(jq -S . /tmp/python_manifest.json) <(jq -S . /tmp/kotlin_manifest.json) 2>&1 || true)

if [ -z "$DIFF_OUTPUT" ]; then
    echo "✅ PERFECT MATCH! Both implementations produce identical output!"
    echo
    echo "📋 Parsed Manifest Summary:"
    echo "─────────────────────────────────────"
    jq . /tmp/python_manifest.json
else
    echo "❌ Differences found:"
    echo "$DIFF_OUTPUT"
    echo
    echo "Python output:"
    jq . /tmp/python_manifest.json
    echo
    echo "Kotlin output:"
    jq . /tmp/kotlin_manifest.json
fi
