FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -Dmaven.test.skip=true package \
    && cp target/agendamento-conversa-*.jar app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app mandalore
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER mandalore

ENV TZ=America/Sao_Paulo \
    HTTP_PORT=8080 \
    MANDALORE_CLIENTES_FILE=/app/config/clientes.properties \
    JAVA_OPTS="-Xms256m -Xmx1g -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${HTTP_PORT}/actuator/health/liveness" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
