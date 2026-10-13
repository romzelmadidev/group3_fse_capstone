FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY . .

RUN mvn clean package -DskipTests -Dmaven.test.skip=true -pl t24-mock-cbs -am \
    && cp t24-mock-cbs/target/t24-mock-cbs-*.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY pom.xml datadog/dd-java-agent.jar* /app/
RUN if [ ! -f /app/dd-java-agent.jar ]; then wget -q --timeout=10 -O /app/dd-java-agent.jar https://dtdg.co/latest-java-tracer || echo "Placeholder" > /app/dd-java-agent.jar; fi
COPY --from=builder /app.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
