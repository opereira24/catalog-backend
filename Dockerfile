# syntax=docker/dockerfile:1

# --- build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Dependências primeiro, para a camada de cache do Docker sobreviver a mudanças só em src/.
COPY .mvn ./.mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src ./src
RUN ./mvnw -B clean package -DskipTests

# --- runtime stage ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN addgroup --system app && adduser --system --ingroup app app
COPY --from=build /app/target/*.jar app.jar
RUN chown app:app app.jar
USER app

# O Render injeta a sua própria $PORT; application.yml já lê server.port: ${PORT:8081}.
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
