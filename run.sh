#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec java -Xmx512m -Djava.awt.headless=true -cp 'build/classes:.tools/*' formatfactory.Server
