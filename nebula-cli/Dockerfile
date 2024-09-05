# Use a slim Java base image
FROM eclipse-temurin:17-jre-alpine

# Install Docker for TestContainers and curl for health check
RUN apk add --no-cache \
    docker-cli \
    curl


# Set the working directory
WORKDIR /app

# Copy the /bin and /lib directories from your Maven build
COPY target/appassembler/bin /app/bin
COPY target/appassembler/lib /app/lib

# Expose the port
EXPOSE 8099

# Add health check
HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8099/health || exit 1

# Set the entrypoint
ENTRYPOINT ["/app/bin/nebula", "--http=8099"]