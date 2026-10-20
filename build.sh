#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p .tools build/classes
[ -f .tools/pdfbox-app-3.0.7.jar ] || curl --fail --location --max-time 120 -o .tools/pdfbox-app-3.0.7.jar https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox-app/3.0.7/pdfbox-app-3.0.7.jar
[ -f .tools/gson-2.13.2.jar ] || curl --fail --location --max-time 120 -o .tools/gson-2.13.2.jar https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar
[ -f .tools/onnxruntime-1.23.2.jar ] || curl --fail --location --max-time 300 -o .tools/onnxruntime-1.23.2.jar https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.23.2/onnxruntime-1.23.2.jar
javac --release 17 -encoding UTF-8 -cp '.tools/*' -d build/classes backend/src/formatfactory/*.java
mkdir -p build/classes/web
cp web/* build/classes/web/
if [ "${1:-}" = '--test' ]; then
  javac --release 17 -encoding UTF-8 -cp 'build/classes:.tools/*' -d build/classes tests/ConversionTest.java tests/HttpIntegrationTest.java tests/ImageFormatsTest.java tests/ResourceMonitorTest.java tests/IdPhotoTest.java
  java -Djava.awt.headless=true -cp 'build/classes:.tools/*' formatfactory.ConversionTest
  java -Djava.awt.headless=true -cp 'build/classes:.tools/*' formatfactory.HttpIntegrationTest
  java -Djava.awt.headless=true -cp 'build/classes:.tools/*' formatfactory.ImageFormatsTest
  java -Djava.awt.headless=true -cp 'build/classes:.tools/*' formatfactory.ResourceMonitorTest
  java -Djava.awt.headless=true -cp 'build/classes:.tools/*' formatfactory.IdPhotoTest
  node tests/miniprogram.test.js
  node tests/idphoto-miniprogram.test.js
fi
