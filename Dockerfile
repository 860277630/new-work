FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY backend ./backend
COPY web ./web
ADD https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox-app/3.0.7/pdfbox-app-3.0.7.jar /app/lib/pdfbox.jar
ADD https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar /app/lib/gson.jar
ADD https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.23.2/onnxruntime-1.23.2.jar /app/lib/onnxruntime.jar
RUN mkdir -p classes/web && javac --release 17 -encoding UTF-8 -cp 'lib/*' -d classes backend/src/formatfactory/*.java && cp web/* classes/web/
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libheif-examples && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/classes ./classes
COPY --from=build /app/lib ./lib
RUN mkdir data && chown -R 10001:10001 /app
USER 10001:10001
ENV HOST=0.0.0.0 PORT=8088 AUTH_MODE=wechat
ENV IDPHOTO_MODELS_DIR=/app/models
EXPOSE 8088
CMD ["java", "-Xmx512m", "-Djava.awt.headless=true", "-cp", "classes:lib/*", "formatfactory.Server"]
