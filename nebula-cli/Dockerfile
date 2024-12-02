# Use a slim Java base image
FROM eclipse-temurin:21-jre-alpine

# Install Docker for TestContainers and curl for health check
RUN apk add --no-cache \
    docker-cli \
    curl \
    unzip


# Set the working directory
WORKDIR /app

COPY target/nebula.zip /app
RUN unzip /app/nebula.zip

# Expose the port
EXPOSE 8099

# Add health check
HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8099/health || exit 1

# Set the entrypoint
ENTRYPOINT ["/app/nebula/bin/nebula", "--http=8099"]