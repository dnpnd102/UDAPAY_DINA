# =============================================================================
# Udapay Ledger Service — multi-stage build
#   stage 1 (build):   JDK 25 + Maven, produces the fat jar
#   stage 2 (runtime): slim JRE 25 on Alpine, non-root user, Generational ZGC
# =============================================================================

# ---------- Stage 1: build ----------------------------------------------------
FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is cached between source changes
COPY pom.xml .
RUN mvn -B -q -e dependency:go-offline || true

COPY src ./src
RUN mvn -B -q clean package -DskipTests \
 && cp target/*.jar /workspace/app.jar

# ---------- Stage 2: runtime --------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

LABEL org.opencontainers.image.title="udapay-ledger" \
      org.opencontainers.image.description="Udapay core-banking ledger API" \
      org.opencontainers.image.vendor="Udapay"

# Minimal, non-root runtime user
RUN addgroup -S udapay && adduser -S -G udapay -h /app udapay \
 && apk add --no-cache curl
WORKDIR /app
COPY --from=build --chown=udapay:udapay /workspace/app.jar /app/app.jar
USER udapay

# JDK 25: ZGC is generational by default. Size the heap from the container limit.
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=dev

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
