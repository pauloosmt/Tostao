FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home tostao
COPY --from=build /app/target/*.jar app.jar
USER tostao
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
