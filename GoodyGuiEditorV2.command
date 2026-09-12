#!/bin/bash
# Double-click in Finder to start Goody's GUI Text Editor V2.
# macOS has no working Jar Launcher for a plain .jar; this wrapper
# opens Terminal and runs java -jar.

cd "$(dirname "$0")" || exit 1

if [ ! -f GoodyGuiEditorV2.jar ]; then
    echo "GoodyGuiEditorV2.jar is missing from this folder. Build it first:"
    echo "  javac -d out src/goodysgui/*.java"
    echo "  jar cfm GoodyGuiEditorV2.jar Manifest.txt -C out ."
    echo
    read -r -p "Press Return to close..."
    exit 1
fi

JAVA_BIN="java"
if JAVA_HOME_DIR="$(/usr/libexec/java_home 2>/dev/null)"; then
    JAVA_BIN="$JAVA_HOME_DIR/bin/java"
fi

"$JAVA_BIN" -jar GoodyGuiEditorV2.jar
status=$?

if [ "$status" -ne 0 ]; then
    echo
    echo "Java failed (exit $status). Is a JDK installed?"
    read -r -p "Press Return to close..."
    exit "$status"
fi
