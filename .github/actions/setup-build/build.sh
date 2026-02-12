#!/usr/bin/env bash
set -euo pipefail

# Required env:
#   SCALA_VERSION
#   MAVEN_URL
# Optional env:
#   COMPILER_DIR (default: /compiler)

: "${SCALA_VERSION:?Missing required env SCALA_VERSION}"
: "${MAVEN_URL:?Missing required env MAVEN_URL}"
COMPILER_DIR="${COMPILER_DIR:-/compiler}"

echo '##################################'
echo "Release Scala in version: ${SCALA_VERSION}"
echo "Maven repo at: ${MAVEN_URL}"
echo '##################################'

# Extract major.minor (first two segments)
# Example: "3.8.3-RC1-..." -> "3.8"
if ! major_minor="$(printf '%s' "$SCALA_VERSION" | grep -oE '^[0-9]+\.[0-9]+')"; then
  echo "ERROR: Could not parse major.minor from version: ${SCALA_VERSION}" >&2
  exit 2
fi

major="${major_minor%%.*}"
minor="${major_minor#*.}"

echo "Compiler binary version: ${major}.${minor}"

# If building Scala 3.6 or earlier use Java 8 (i.e. < 3.7)
JDKVersion=17
if [[ "$major" -lt 3 ]] || { [[ "$major" -eq 3 ]] && [[ "$minor" -lt 7 ]]; }; then
  JDKVersion=8
fi

echo "Would try to use JDK ${JDKVersion} to build compiler"
export PATH="/usr/lib/jvm/java-${JDKVersion}-openjdk-amd64/bin:${PATH}"

echo "Using java:"
java -version

if [[ ! -d "$COMPILER_DIR" ]]; then
  echo "ERROR: compiler_dir does not exist: ${COMPILER_DIR}" >&2
  exit 3
fi

cd "$COMPILER_DIR"

compilerProject="scala3-bootstrapped"
if [[ "$SCALA_VERSION" == 3.8.0* \
   || "$SCALA_VERSION" == 3.8.1* \
   || "$SCALA_VERSION" == 3.8.2-RC* ]]; then
  compilerProject="scala3-bootstrapped-new"
fi

echo "Would publish ${compilerProject}"

# Keep this as a single command (no trailing "\" line continuations)
# so it is safe even if a runner/action flattens whitespace.
sbt -verbose \
  "set every publishTo := Some(\"Community Build Repo\" at \"${MAVEN_URL}\")" \
  ";set every version := \"${SCALA_VERSION}\"" \
  ";${compilerProject}/publish"
