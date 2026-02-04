# Etapa 1: Build con cacheo de dependencias
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copiar solo archivos de dependencias primero para aprovechar caché de Docker
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn

# Descargar dependencias (esta capa se cachea si pom.xml no cambia)
RUN ./mvnw dependency:go-offline -B

# Copiar código fuente
COPY src src

# Compilar aplicación
RUN ./mvnw package -DskipTests -B

# Etapa 2: Runtime optimizado con Java 25
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Crear usuario no-root para seguridad
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copiar JAR desde etapa de build
COPY --from=build /workspace/target/*.jar app.jar

# Configuración de JVM optimizada para Java 25
# - UseCompactObjectHeaders: Reduce headers de 12 a 8 bytes (ahorra ~20% heap)
# - UseZGC: GC de baja latencia optimizado en Java 25
# - ZGenerational: Habilita el modo generacional de ZGC
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:+UseZGC -XX:+ZGenerational -XX:+UseCompactObjectHeaders -Djava.security.egd=file:/dev/./urandom"

# Puerto dinámico (Railway usa $PORT, default 8080)
ENV PORT=8080
EXPOSE ${PORT}

# Health check (start-period aumentado para carga de datos)
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT}/actuator/health || exit 1

# Usar shell form para expandir variables de entorno
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT} -jar /app/app.jar"]
