package com.coderalexis.CodigoPostalApi.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.*;

import java.util.List;

@Configuration
public class SwaggerConfiguration {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("API de Códigos Postales de México")
                        .version("2.1.0")
                        .description("""
                                # API de Códigos Postales de México

                                API REST de alto rendimiento para consultar códigos postales mexicanos con:

                                ## Características
                                - 🔍 Búsqueda por código postal, entidad federativa y municipio
                                - 📊 Paginación en todas las búsquedas
                                - ⚡ Caché inteligente con Caffeine
                                - 📈 Métricas con Prometheus
                                - 🔒 Búsquedas insensibles a acentos y mayúsculas
                                - 🏥 Health checks integrados

                                ## Rendimiento
                                - **6,389 req/s** en búsquedas directas
                                - **156ms** tiempo de respuesta promedio
                                - **99%** de requests bajo 190ms

                                ## Datos
                                Basado en el Catálogo Nacional de Códigos Postales de Correos de México.
                                Más de 145,000 códigos postales disponibles.

                                ## Endpoints Principales
                                - `GET /zip-codes/{codigo}` - Buscar por código postal
                                - `GET /zip-codes?federal_entity={nombre}` - Buscar por estado
                                - `GET /zip-codes/by-municipality?municipality={nombre}` - Buscar por municipio
                                - `GET /zip-codes/stats` - Estadísticas generales
                                """)
                        .contact(new Contact()
                                .name("Jose Alexis")
                                .email("coderalexis@gmail.com")
                                .url("https://www.coderalexis.com"))
                        .license(new License()
                                .name("Licencia MIT")
                                .url("https://opensource.org/licenses/MIT"))
                )
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("🛠️ Desarrollo Local"),
                        new Server()
                                .url("https://zipcode.coderalexis.com")
                                .description("🚀 Producción")
                ));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/zip-codes/**")
                .build();
    }
}
