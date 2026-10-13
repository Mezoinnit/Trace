# Trace API server (single-process deploy: HTTP API + optional embedded
# worker/scheduler via EMBED_WORKER=true). See DEPLOYMENT.md §5.1.
#
# Two-stage build keeps the runtime image free of the Gradle toolchain and
# sources. The wrapper downloads Gradle 9.4.1 + dependencies at image-build
# time; the final image only carries the JRE and the installDist output.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Layer cache: wrapper + dependency metadata first, so code edits do not
# re-download the world.
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --no-daemon :server:api:dependencies > /dev/null 2>&1 || true

COPY . .
RUN ./gradlew --no-daemon :server:api:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/api/build/install/api ./

# Console logging in containers (logback-prod.json.xml switches to JSON).
ENV LOGBACK_CONFIGURATION_FILE= \
    PORT=8080 \
    EMBED_WORKER=true
EXPOSE 8080

# All-in-one by default (free-tier single process). For the split deployment,
# unset EMBED_WORKER and run server:worker / server:scheduler images instead.
CMD ["./bin/api"]