package com.coderalexis.CodigoPostalApi.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.headers.Header;
import org.springframework.context.annotation.*;

import java.util.List;

@Configuration
public class SwaggerConfiguration {

    @Bean
    public OpenAPI customOpenAPI(org.springframework.beans.factory.ObjectProvider<org.springframework.boot.info.BuildProperties> builds) {
        var build = builds.getIfAvailable();
        return new OpenAPI()
                .info(new Info()
                        .title("API de Códigos Postales de México")
                        .version(build == null ? "dev" : build.getVersion())
                        .description("""
                                Consulta códigos postales mexicanos, valida la relación CP/estado/municipio
                                y obtiene opciones de domicilio para formularios. Los nombres se comparan
                                sin acentos ni distinción de mayúsculas. No verifica calles ni números.
                                Consulta /zip-codes/stats para identificar el catálogo activo y sus totales.
                                Los límites de peticiones se comunican mediante X-RateLimit-* y Retry-After.
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
    public GlobalOpenApiCustomizer rateLimitDocumentation() {
        return api -> api.getPaths().forEach((path, item) -> {
            if (path.startsWith("/zip-codes")) item.readOperations().forEach(operation ->
                operation.getResponses().addApiResponse("429", new ApiResponse()
                    .description("Límite de peticiones alcanzado; espera Retry-After segundos")
                    .addHeaderObject("Retry-After", new Header().schema(new IntegerSchema()).description("Segundos hasta el siguiente token"))
                    .content(new Content().addMediaType("application/json", new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ErrorResponse"))))));
        });
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/zip-codes/**")
                .build();
    }
}
