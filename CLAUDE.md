# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Nebula is a Kotlin-based DSL for quickly defining and orchestrating test ecosystems using Docker containers. It allows developers to spin up complex infrastructure stacks (Kafka, databases, HTTP servers, etc.) with associated behaviors for testing and demonstrations.

## Development Commands

### Build and Test
```bash
# Build all modules
mvn clean compile

# Run tests
mvn test

# Package the application
mvn package

# Run a specific test class
mvn test -Dtest=ClassName

# Run tests for a specific module
mvn test -pl nebula-dsl
```

### Documentation
```bash
# In docs/ directory
npm run dev    # Start development server
npm run build  # Build documentation
npm start      # Start production server
```

### Release Management
```bash
./tag-release.sh  # Automated release tagging and version management
```

## Architecture

### Multi-Module Structure
- **nebula-api**: Core API definitions and events
- **nebula-dsl**: Main DSL implementation and infrastructure components 
- **nebula-cli**: Command-line interface using PicoCLI
- **nebula-runtime**: Script execution engine and HTTP server

### Key Technologies
- **Kotlin 2.2.0** on JVM 21
- **TestContainers 1.19.0** for container orchestration
- **Ktor 2.3.12** for HTTP servers and clients
- **Kotest 5.9.1** for testing with descriptive specifications
- **Project Reactor** for reactive programming

### Infrastructure Components
The DSL supports: Kafka, HTTP servers, SQL databases (PostgreSQL/MySQL with JOOQ), MongoDB, S3 (via LocalStack), Hazelcast, Avro/Protobuf schemas.

## Code Patterns

### DSL Scripts
- Files use `.nebula.kts` extension
- Kotlin scripting with fluent builder APIs
- Default imports provide common utilities
- Components implement `InfrastructureComponent<*>` interface

### Testing
- Uses Kotest framework with descriptive specifications
- TestContainers for integration testing
- Tests located in `src/test/kotlin` directories

### Lifecycle Management
- Event-driven architecture with lifecycle events
- Centralized stack runner for orchestration
- Graceful shutdown handling for containers

## Running Nebula

### CLI Usage
```bash
# Execute a script
nebula script.nebula.kts

# Start HTTP server mode
nebula --http=8099
```

### Docker Usage
```bash
docker run -v /var/run/docker.sock:/var/run/docker.sock \
  --privileged --network host \
  orbitalhq/nebula:latest
```

## Development Notes

- Source code in `src/main/kotlin`, tests in `src/test/kotlin`
- Maven manages multi-module dependencies
- Custom S3-based Maven repository at repo.orbitalhq.com
- Multi-platform Docker builds (AMD64/ARM64)
- Requires Docker daemon access for TestContainers
- Uses official Kotlin coding style conventions