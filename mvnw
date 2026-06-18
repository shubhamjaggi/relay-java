#!/bin/sh
MAVEN_VERSION="3.9.6"
MAVEN_HOME="$HOME/.m2/wrapper/apache-maven-$MAVEN_VERSION"
MAVEN_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.tar.gz"

if [ ! -f "$MAVEN_HOME/bin/mvn" ]; then
    echo "Downloading Maven $MAVEN_VERSION..."
    mkdir -p "$HOME/.m2/wrapper"
    curl -fsSL "$MAVEN_URL" | tar -xz -C "$HOME/.m2/wrapper"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
