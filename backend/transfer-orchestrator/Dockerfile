FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY transfer-orchestrator/target/transfer-orchestrator-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
