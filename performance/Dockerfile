FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg=7:8.0.1-3ubuntu2 curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar /app/backend.jar
ENTRYPOINT ["java", "-Xms512m", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-jar", "/app/backend.jar"]
