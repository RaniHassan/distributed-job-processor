# syntax=docker/dockerfile:1

# ---- Build stage --------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

# Resolve dependencies in their own layer so source changes don't re-download them.
# chmod: the build must not depend on the executable bit surviving the checkout (e.g. ZIP downloads).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests need Docker (Testcontainers) and run on the host, not inside the image build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/distributed-job-processor-*.jar app.jar

# ---- Runtime stage ------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app

ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

# Exec form so SIGTERM reaches the JVM and Spring's graceful shutdown runs.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
