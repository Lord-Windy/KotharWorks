#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

set -euf

usage() {
  cat <<'EOF'
Usage: ./mvnw [options] [goals]
Apache Maven Wrapper (only-script) — downloads Maven automatically into ~/.m2/wrapper/dists if not installed.
EOF
}

BASE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
WRAPPER_PROPS="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$WRAPPER_PROPS" ]; then
  echo "Error: cannot find $WRAPPER_PROPS" >&2
  exit 1
fi

distributionUrl=""
distributionSha256Sum=""
while IFS='=' read -r key value; do
  case "$key" in
    \#*|"") continue ;;
    distributionUrl) distributionUrl="$value" ;;
    distributionSha256Sum) distributionSha256Sum="$value" ;;
  esac
done < "$WRAPPER_PROPS"

# Auto-select SDKMAN Java from .sdkmanrc when JAVA_HOME is not already set to JDK 25+
if [ -f "$BASE_DIR/.sdkmanrc" ] && [ -d "$HOME/.sdkman/candidates/java" ]; then
  sdk_java="$(sed -n 's/^java=//p' "$BASE_DIR/.sdkmanrc" | head -n 1)"
  if [ -n "$sdk_java" ] && [ -d "$HOME/.sdkman/candidates/java/$sdk_java" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/$sdk_java"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

# Prefer mvnd or mvn if user explicitly opts in via MVNW_USE_SYSTEM=1, otherwise use pinned Maven dist
DIST_FILENAME="${distributionUrl##*/}"
DIST_NAME="${DIST_FILENAME%-bin.zip}"
MAVEN_USER_HOME="${MAVEN_USER_HOME:-$HOME/.m2}"
DIST_DIR="$MAVEN_USER_HOME/wrapper/dists/$DIST_NAME"
MAVEN_HOME_DIR="$DIST_DIR/$DIST_NAME"

if [ ! -x "$MAVEN_HOME_DIR/bin/mvn" ]; then
  mkdir -p "$DIST_DIR"
  TMP_ZIP="$DIST_DIR/$DIST_FILENAME.part"
  echo "Downloading $distributionUrl ..." >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$TMP_ZIP" "$distributionUrl"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$TMP_ZIP" "$distributionUrl"
  else
    echo "Error: curl or wget is required to download Maven." >&2
    exit 1
  fi

  if [ -n "$distributionSha256Sum" ] && command -v sha256sum >/dev/null 2>&1; then
    actual_sha="$(sha256sum "$TMP_ZIP" | awk '{print $1}')"
    if [ "$actual_sha" != "$distributionSha256Sum" ]; then
      rm -f "$TMP_ZIP"
      echo "Error: SHA-256 mismatch for $DIST_FILENAME (expected $distributionSha256Sum, got $actual_sha)" >&2
      exit 1
    fi
  fi

  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$TMP_ZIP" -d "$DIST_DIR"
  elif command -v jar >/dev/null 2>&1; then
    (cd "$DIST_DIR" && jar xf "$TMP_ZIP")
  else
    echo "Error: unzip or jar is required to extract Maven." >&2
    exit 1
  fi
  rm -f "$TMP_ZIP"
  chmod +x "$MAVEN_HOME_DIR/bin/mvn"
fi

exec "$MAVEN_HOME_DIR/bin/mvn" "$@"
