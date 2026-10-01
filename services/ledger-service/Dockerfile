# ---- Build stage: full JDK + Maven wrapper -------------------------------
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src

# Download dependencies first so they're cached unless pom.xml changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -ntp dependency:go-offline

COPY src src
# Tests run in CI (they need Docker for Testcontainers), not inside the image build
RUN ./mvnw -B -ntp package -DskipTests

# ---- Runtime stage: JRE only, non-root -----------------------------------
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /src/target/ledger-service-*.jar app.jar
USER app

EXPOSE 8081
# Size the heap from the container's memory limit rather than the host's
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
