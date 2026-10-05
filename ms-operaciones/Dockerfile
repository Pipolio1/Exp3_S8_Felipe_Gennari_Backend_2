# Imagen de ms-operaciones (puerto 8083). Contexto de build: raiz del proyecto.
# bank-common se instala primero porque es una dependencia interna del modulo.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -N install
COPY bank-common ./bank-common
RUN mvn -q -f bank-common/pom.xml install -DskipTests
COPY ms-operaciones ./ms-operaciones
RUN mvn -q -f ms-operaciones/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/ms-operaciones/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
