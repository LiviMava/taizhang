FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY static ./static
COPY migrations ./migrations
RUN mvn -B -DskipTests package
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/systemlog-1.0.0.jar app.jar
EXPOSE 8000
ENV APP_HOST=0.0.0.0
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "app.jar"]
