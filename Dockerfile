# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from source so `mvn dependency:go-offline`
# is only re-run when pom.xml actually changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as a non-root user rather than the image default root.
RUN addgroup -S mendops && adduser -S mendops -G mendops
USER mendops

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8095

# Matches management.endpoints.web.exposure.include=health,info in application.properties.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8095/actuator/health | grep -q '"status":"UP"' || exit 1

# GEMINI_API_KEY, mendops.telemetry.* DB/Kafka credentials, etc. are expected
# to be supplied at runtime (docker run -e / docker-compose environment /
# k8s secret) - none are baked into the image.
ENTRYPOINT ["java", "-jar", "app.jar"]
