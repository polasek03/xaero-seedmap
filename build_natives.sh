#!/usr/bin/env bash
set -e

cd "${0%/*}" 2>/dev/null || true
export TEMP="C:/Users/gergo/AppData/Local/Temp"
export TMP="$TEMP"

JDK=""
for d in /c/Users/gergo/.jdks/*/; do
    if [ -f "${d}include/jni.h" ]; then
        JDK="${d%/}"
        break
    fi
done
if [ -z "$JDK" ]; then
    echo "ERROR: Could not find JDK with jni.h under ~/.jdks"
    exit 1
fi
echo "Using JDK: $JDK"

OUT=src/main/resources/natives/windows/cubiomes.dll
echo "Building $OUT ..."

/ucrt64/bin/gcc -O2 -shared \
  src/main/c/cubiomes_jni.c \
  cubiomes/generator.c cubiomes/biomes.c cubiomes/layers.c cubiomes/noise.c \
  cubiomes/finders.c cubiomes/util.c cubiomes/quadbase.c cubiomes/biomenoise.c \
  -I cubiomes \
  -I "$JDK/include" \
  -I "$JDK/include/win32" \
  -lm \
  -o "$OUT"

echo "Build successful: $OUT"
