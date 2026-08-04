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

# Crear usuario no-root para seguridad. El directorio de logs se crea aquí porque
# el perfil prod escribe a /var/log/codigopostal-api y el usuario spring no puede
# crearlo en runtime (Logback fallaba con FileNotFoundException en cada arranque).
RUN addgroup -S spring && adduser -S spring -G spring && \
    mkdir -p /var/log/codigopostal-api && \
    chown -R spring:spring /var/log/codigopostal-api

# Flags de GC/heap compartidos entre el training run y el runtime: los archives
# CDS son sensibles a los flags de la JVM y deben coincidir.
# - UseCompactObjectHeaders: reduce headers de 12 a 8 bytes (ahorra ~20% heap)
# - UseZGC: GC de baja latencia (generacional por defecto desde JDK 23; el flag
#   ZGenerational fue eliminado y aborta el arranque en JDK 25)
# - UseContainerSupport es default desde JDK 10, no hace falta pasarlo.
ENV JVM_GC_OPTS="-XX:MaxRAMPercentage=70.0 -XX:+UseZGC -XX:+UseCompactObjectHeaders"

# Extraer el JAR (el layout expandido arranca más rápido que el fat JAR) y
# generar el archive CDS con un training run: arranca el contexto Spring
# completo (incluida la carga del catálogo, lo que de paso valida el archivo en
# build time) y sale limpiamente al terminar el refresh.
COPY --from=build /workspace/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /app/application && \
    rm app.jar && \
    java $JVM_GC_OPTS \
      -XX:ArchiveClassesAtExit=/app/application/app.jsa \
      -Dspring.context.exit=onRefresh \
      -Dcache.warmup.enabled=false \
      -jar /app/application/app.jar

USER spring:spring

# El runtime carga el archive CDS generado arriba (clases pre-parseadas: 1-3s
# menos de arranque en frío).
ENV JAVA_OPTS="-XX:SharedArchiveFile=/app/application/app.jsa -Djava.security.egd=file:/dev/./urandom"

# Puerto dinámico (Railway usa $PORT, default 8080)
ENV PORT=8080
EXPOSE ${PORT}

# Puerto donde vive el actuator. El perfil prod lo separa a 9090; railway/dev lo
# dejan en $PORT. Se deja vacío por defecto (= usar $PORT) y el healthcheck cae
# al 9090 si el primero no responde, para que la imagen quede sana con cualquier
# perfil aunque el operador no configure nada.
ENV MANAGEMENT_PORT=""

# Health check (start-period aumentado para carga de datos)
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider "http://localhost:${MANAGEMENT_PORT:-$PORT}/actuator/health" || \
      wget --no-verbose --tries=1 --spider http://localhost:9090/actuator/health || exit 1

# Usar shell form para expandir variables de entorno
ENTRYPOINT ["sh", "-c", "exec java $JVM_GC_OPTS $JAVA_OPTS -Dserver.port=${PORT} -jar /app/application/app.jar"]
