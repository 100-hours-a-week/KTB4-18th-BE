FROM eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f
WORKDIR /app
COPY --chown=10001:10001 .runtime/app.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-Xms256m", "-Xmx768m", "-XX:ActiveProcessorCount=2", "-jar", "/app/app.jar"]
