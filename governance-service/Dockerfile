# Multi-stage build (CON-004: no local JDK — everything happens in-container; ADR-008 footprint mitigation)
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/governance-service-*.jar app.jar
EXPOSE 8080 7402 7403
ENTRYPOINT ["java", "-jar", "app.jar"]
