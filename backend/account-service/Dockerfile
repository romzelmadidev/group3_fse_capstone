FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY account-service/target/account-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
