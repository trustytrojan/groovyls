#!/bin/sh
set -e
./gradlew build -x test
cp build/libs/groovyls-all.jar ~/.vscode/extensions/trustytrojan.groovyls-0.0.0/bin/