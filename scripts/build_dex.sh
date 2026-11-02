#!/bin/bash
# Компиляция кастомного Java-кода (src/ + stub/) в classes.dex
# Требования: JDK 17, Android SDK (build-tools 34, platforms/android-34)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BT=${BT:-/opt/android-sdk/build-tools/34.0.0}
PLATFORM=${PLATFORM:-/opt/android-sdk/platforms/android-34/android.jar}
cd "$ROOT"
rm -rf build/classes build/dex
mkdir -p build/classes build/dex
find stub src -name '*.java' > /tmp/wxsrcs.txt
javac -source 8 -target 8 -bootclasspath "$PLATFORM" -d build/classes @/tmp/wxsrcs.txt 2>&1 | grep -v "warning" || true
# в dex кладём ТОЛЬКО com/nevo (стабы нужны только javac для типов)
find build/classes/com/nevo -name '*.class' | sort > /tmp/wxclasses.txt
"$BT/d8" --release --min-api 30 --lib "$PLATFORM" --classpath build/classes --output build/dex $(cat /tmp/wxclasses.txt) 2>&1 | tail -2
ls -la build/dex/
