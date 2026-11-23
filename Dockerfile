FROM eclipse-temurin:21-jdk-noble@sha256:a09e64f4976df9c4f2029c6abe64fc26f069a342ec9a7f147dce7b1933ebda16 AS build
WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle/wrapper/ gradle/wrapper/
COPY src/main/ src/main/
RUN sh ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-noble@sha256:ac595159aa2209f07a2331efa8a8ce4278b77a7b76a9365a0f173786d5067a9e
WORKDIR /app

COPY --from=build /workspace/build/libs/project-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
