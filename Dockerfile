# syntax=docker/dockerfile:1

FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies first for faster rebuilds.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as a non-root user in production containers.
RUN groupadd --system spring && useradd --system --gid spring --create-home --home-dir /home/spring spring

# Keep JVM options configurable without invoking a shell.
ENV JAVA_TOOL_OPTIONS=""

COPY --from=build /app/target/ib-backend-1.0.0.jar /app/app.jar
RUN chown spring:spring /app/app.jar

USER spring:spring

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
