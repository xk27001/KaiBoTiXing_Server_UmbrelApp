# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-25-noble AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests clean package \
    && cp target/KaiBoTiXing-*-fat.jar /workspace/app.jar

FROM eclipse-temurin:25-jre-noble

ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dconsole.charset=UTF-8"
WORKDIR /app

COPY --from=build /workspace/app.jar /app/app.jar
RUN mkdir -p /app/logs && chown -R 1000:1000 /app

USER 1000:1000
EXPOSE 8080
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-cp", "/app/app.jar", "com.kaibotixing.server.ServerLauncher"]
