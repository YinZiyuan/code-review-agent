# syntax=docker/dockerfile:1.7

ARG SPOTBUGS_VERSION=4.9.8
ARG SPOTBUGS_SHA256=2eb8e0f2b223c22ffa2ce0c1cf1be4127dde19d240b8f7ce69a5fd3ad5c36ff3

FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn --batch-mode package -Dmaven.test.skip=true

FROM maven:3.9.11-eclipse-temurin-17 AS spotbugs

ARG SPOTBUGS_VERSION
ARG SPOTBUGS_SHA256
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    archive="/root/.m2/repository/com/github/spotbugs/spotbugs/${SPOTBUGS_VERSION}/spotbugs-${SPOTBUGS_VERSION}.tgz" \
    && mvn --batch-mode dependency:get \
        -Dartifact="com.github.spotbugs:spotbugs:${SPOTBUGS_VERSION}:tgz" \
        -Dtransitive=false \
    && echo "${SPOTBUGS_SHA256}  ${archive}" | sha256sum -c - \
    && mkdir -p /opt/spotbugs \
    && tar -xzf "${archive}" -C /opt/spotbugs --strip-components=1 \
    && /opt/spotbugs/bin/spotbugs -version

FROM eclipse-temurin:17-jdk

RUN useradd --system --create-home --uid 10001 appuser
WORKDIR /app
COPY --from=spotbugs /opt/spotbugs /opt/spotbugs
COPY --from=build /workspace/target/code-review-agent-1.0.0.jar /app/code-review-agent.jar
ENV PATH="/opt/spotbugs/bin:${PATH}" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=45.0 -XX:MaxDirectMemorySize=128m -XX:+ExitOnOutOfMemoryError"
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/code-review-agent.jar"]
CMD ["serve"]
