FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

FROM eclipse-temurin:17-jre

RUN useradd --system --create-home --uid 10001 appuser
WORKDIR /app
COPY --from=build /workspace/target/code-review-agent-1.0.0.jar /app/code-review-agent.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/code-review-agent.jar"]
CMD ["serve"]
