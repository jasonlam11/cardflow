# syntax=docker/dockerfile:1
# ---- Build stage: full JDK + Maven wrapper -------------------------------
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src

# Download dependencies first so they're cached unless pom.xml changes.
# The cache mount keeps ~/.m2 between builds, so even a pom change only fetches what's new.
# sharing=locked: parallel builds (compose builds services concurrently) take turns,
# otherwise two Maven wrappers race to unpack Maven into the same directory.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp dependency:go-offline

COPY src src
# Tests run in CI (they need Docker for Testcontainers), not inside the image build
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp package -DskipTests

# ---- Runtime stage: JRE only, non-root -----------------------------------
# Alpine JRE: ~75 MB compressed vs ~180 MB for the Ubuntu one, and fewer OS packages to patch
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app -H app
COPY --from=build /src/target/ledger-service-*.jar app.jar
USER app

EXPOSE 8081
# Size the heap from the container's memory limit rather than the host's
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
