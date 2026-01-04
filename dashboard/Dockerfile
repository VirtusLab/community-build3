FROM virtuslab/scala-cli:1.11.0 AS builder
LABEL org.opencontainers.image.source="https://github.com/VirtusLab/community-build3"

WORKDIR /build

# Step 1: Copy only dependency definition file (changes rarely)
COPY build.scala /build/

# Step 2: Download and cache dependencies (only re-runs when build.scala changes)
RUN scala-cli compile build.scala

# Step 3: Copy source code (changes frequently)
COPY . /build

# Step 4: Package as assembly JAR (uses cached dependencies)
RUN scala-cli --power package . --assembly -o /build/dashboard.jar --force

# Runtime image - minimal JRE
FROM eclipse-temurin:25-jre-alpine
LABEL org.opencontainers.image.source="https://github.com/VirtusLab/community-build3"

WORKDIR /app

# Copy the fat JAR from builder
COPY --from=builder /build/dashboard.jar /app/dashboard.jar

# Create directory for SQLite database
RUN mkdir -p /data

# Expose HTTP port (TLS terminated at ingress)
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "/app/dashboard.jar"]
