# syntax=docker/dockerfile:1
# The jar is platform-independent: build it natively, even for the arm64 image (no emulation).
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI against Testcontainers; the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/production-orders-service-*.jar extract --layers --launcher --destination /out

# Distroless Java 21, non-root, no shell. Dependencies are their own layer, so a code change ships ~300 KB.
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /out/dependencies/ ./
COPY --from=build /out/spring-boot-loader/ ./
COPY --from=build /out/snapshot-dependencies/ ./
COPY --from=build /out/application/ ./
USER 65532:65532
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
