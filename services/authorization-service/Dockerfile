# syntax=docker/dockerfile:1
# ---- Build stage: full JDK + Maven wrapper -------------------------------
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src

# Download dependencies first so they're cached unless pom.xml changes.
# The cache mount keeps ~/.m2 between builds, so even a pom change only fetches what's new.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline

COPY src src
# Tests run in CI (they need Docker for Testcontainers), not inside the image build
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package -DskipTests

# ---- Runtime stage: JRE only, non-root -----------------------------------
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /src/target/authorization-service-*.jar app.jar
USER app

EXPOSE 8082
# Size the heap from the container's memory limit rather than the host's
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
