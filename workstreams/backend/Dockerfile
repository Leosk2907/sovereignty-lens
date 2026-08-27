# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1: build the Spring Boot fat jar with Maven.
# pom.xml is copied on its own first so the dependency layer is only rebuilt
# when the dependency declarations actually change.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2: minimal JRE runtime, non-root.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# BusyBox wget (already present in the Alpine base) is used by the healthcheck.
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

COPY --from=build --chown=app:app /build/target/sovereignty-lens-api-0.1.0.jar /app/app.jar

USER app

EXPOSE 8080

# Give Spring Boot + Flyway time to start before the container is marked
# unhealthy; the demo database migrates on first boot.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -q --spider http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
