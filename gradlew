#!/bin/sh

# Repo Scope Search Gradle launcher for Linux, macOS, WSL and Git Bash.
# Java lookup order:
#   1. gradleJavaHome from project gradle.properties
#   2. <pycharmLocalPath>/jbr from project gradle.properties
#   3. JAVA_HOME
#   4. java from PATH

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
APP_BASE_NAME=gradlew
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
PROPERTIES_FILE="$APP_HOME/gradle.properties"

read_property() {
    key=$1
    [ -f "$PROPERTIES_FILE" ] || return 0
    sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "$PROPERTIES_FILE" \
        | tail -n 1 \
        | tr -d '\r' \
        | sed 's/^"//;s/"$//'
}

GRADLE_JAVA_HOME=$(read_property gradleJavaHome)
PYCHARM_LOCAL_PATH=$(read_property pycharmLocalPath)
JAVA_EXE=
JAVA_SOURCE=

find_java_in_home() {
    candidate_home=$1
    [ -n "$candidate_home" ] || return 1
    if [ -x "$candidate_home/bin/java" ]; then
        JAVA_EXE="$candidate_home/bin/java"
        return 0
    fi
    if [ -f "$candidate_home/bin/java.exe" ]; then
        JAVA_EXE="$candidate_home/bin/java.exe"
        return 0
    fi
    return 1
}

if find_java_in_home "$GRADLE_JAVA_HOME"; then
    JAVA_SOURCE=gradleJavaHome
elif find_java_in_home "$PYCHARM_LOCAL_PATH/jbr"; then
    JAVA_SOURCE=pycharmLocalPath/jbr
elif find_java_in_home "${JAVA_HOME:-}"; then
    JAVA_SOURCE=JAVA_HOME
elif command -v java >/dev/null 2>&1; then
    JAVA_EXE=$(command -v java)
    JAVA_SOURCE=PATH
fi

if [ -z "$JAVA_EXE" ]; then
    cat >&2 <<EOF_ERROR

ERROR: Java was not found.

Checked:
  1. gradleJavaHome in $PROPERTIES_FILE
  2. pycharmLocalPath/jbr in $PROPERTIES_FILE
  3. JAVA_HOME
  4. java in PATH

Configured pycharmLocalPath: ${PYCHARM_LOCAL_PATH:-<not set>}
pycharmLocalPath must point to an installation containing jbr/bin/java.
On Linux/macOS, use a native path rather than C:/... .
EOF_ERROR
    exit 1
fi

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "ERROR: Gradle Wrapper JAR was not found: $WRAPPER_JAR" >&2
    exit 1
fi

if [ -z "${REPO_SCOPE_QUIET:-}" ]; then
    echo "[Repo Scope Search] Java: $JAVA_EXE"
    echo "[Repo Scope Search] Source: $JAVA_SOURCE"
    [ -n "$PYCHARM_LOCAL_PATH" ] && echo "[Repo Scope Search] PyCharm: $PYCHARM_LOCAL_PATH"
fi

# JAVA_OPTS and GRADLE_OPTS intentionally remain shell-split, as in the official wrapper.
# shellcheck disable=SC2086
exec "$JAVA_EXE" ${JAVA_OPTS:-} ${GRADLE_OPTS:-} \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
