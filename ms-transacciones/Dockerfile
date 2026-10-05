# Imagen de ms-transacciones (puerto 8082). Contexto de build: raiz del proyecto.
# bank-common se instala primero porque es una dependencia interna del modulo.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -N install
COPY bank-common ./bank-common
RUN mvn -q -f bank-common/pom.xml install -DskipTests
COPY ms-transacciones ./ms-transacciones
RUN mvn -q -f ms-transacciones/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/ms-transacciones/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
