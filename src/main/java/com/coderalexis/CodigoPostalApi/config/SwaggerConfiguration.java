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
                        .title("API de Códigos Postales")
                        .version("2.0.1")
                        .description("API para consultar códigos postales y entidades federativas.")
                        .contact(new Contact()
                                .name("Jose Alexis")
                                .email("coderalexis@gmail.com")
                                .url("https://www.coderalexis.com"))
                        .license(new License()
                                .name("Licencia MIT")
                                .url("https://opensource.org/licenses/MIT"))
                )
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Servidor de Desarrollo"),
                        new Server().url("https://zipcode.coderalexis.com").description("Servidor de Producción")
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
