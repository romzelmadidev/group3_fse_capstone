FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY t24-mock-cbs/target/t24-mock-cbs-*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
