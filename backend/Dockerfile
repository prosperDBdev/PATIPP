# =============================================================================
# PATIPP API — Spring Boot 4 / Java 21
#
# Two stages so the runtime image carries a JRE and one jar, not Maven and a
# ~500 MB dependency cache. Built for an always-on container host (Fly.io,
# Railway, Render) rather than a stateless function: this application holds a
# JDBC connection pool and runs Flyway at boot, and both want a process that
# stays up.
# =============================================================================

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, as their own layer. pom.xml changes far less often than
# source does, so an edit to a Java file reuses the cached download instead of
# fetching the world again.
COPY pom.xml ./
COPY .mvn/ .mvn/
COPY mvnw ./
RUN mvn -B -ntp dependency:go-offline

COPY src/ src/

# Tests are deliberately skipped here. They need a real PostgreSQL through
# Testcontainers, which means a Docker daemon inside the build — and the suite
# already gates the commit. A build that silently skips tests would be wrong; a
# build that cannot run them and says so is not.
RUN mvn -B -ntp -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Non-root. The application needs no write access to anything but /tmp.
RUN addgroup -S patipp && adduser -S patipp -G patipp

COPY --from=build /build/target/patipp-api-*.jar app.jar
RUN chown -R patipp:patipp /app
USER patipp

# MaxRAMPercentage rather than a fixed -Xmx: the container memory limit is set by
# the host, and hardcoding a heap size here would either waste the allowance or
# get the process killed when the host is resized.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

# Matches PATIPP's fixed port convention, and PATIPP_PORT can still override it.
EXPOSE 8081

# exec form via sh -c so JAVA_OPTS expands, with exec so the JVM is PID 1 and
# receives SIGTERM directly — otherwise the platform's graceful shutdown would
# kill the shell and leave in-flight requests to be dropped.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
