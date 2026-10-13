FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY . .

RUN mvn clean package -DskipTests -Dmaven.test.skip=true -pl gateway-service -am \
    && cp gateway-service/target/gateway-service-*.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
ADD https://dtdg.co/latest-java-tracer /app/dd-java-agent.jar
COPY --from=builder /app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
