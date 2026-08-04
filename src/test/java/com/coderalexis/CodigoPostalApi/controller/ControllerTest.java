package com.coderalexis.CodigoPostalApi.controller;

import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ZipCodeService zipCodeService;

    @Test
    @DisplayName("GET /zip-codes/{zipcode} - Debe retornar código postal válido")
    void shouldReturnValidZipCode() throws Exception {
        mockMvc.perform(get("/zip-codes/01000")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.zip_code").value("01000"))
                .andExpect(jsonPath("$.federal_entity").exists())
                .andExpect(jsonPath("$.municipality").exists())
                .andExpect(jsonPath("$.settlements").isArray())
                .andExpect(jsonPath("$.settlements", hasSize(greaterThan(0))));
    }

    @Test
    @DisplayName("GET /zip-codes/{zipcode} - Debe retornar 404 para código postal inexistente")
    void shouldReturn404ForInvalidZipCode() throws Exception {
        mockMvc.perform(get("/zip-codes/99999")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /zip-codes/{zipcode} - Debe retornar 400 para formato inválido")
    void shouldReturn400ForInvalidFormat() throws Exception {
        mockMvc.perform(get("/zip-codes/123")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /zip-codes/{zipcode} - Debe retornar 400 para código postal con letras")
    void shouldReturn400ForZipCodeWithLetters() throws Exception {
        mockMvc.perform(get("/zip-codes/ABC12")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /zip-codes?federal_entity - Debe buscar por entidad federativa")
    void shouldSearchByFederalEntity() throws Exception {
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "Ciudad de México")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.totalPages").exists())
                .andExpect(jsonPath("$.first").value(true));
    }

    @Test
    @DisplayName("GET /zip-codes?federal_entity - Debe soportar paginación")
    void shouldSupportPaginationForFederalEntity() throws Exception {
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "méxico")
                .param("page", "0")
                .param("size", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.content", hasSize(lessThanOrEqualTo(10))));
    }

    @Test
    @DisplayName("GET /zip-codes?federal_entity - Debe validar tamaño de página máximo")
    void shouldValidateMaxPageSize() throws Exception {
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "méxico")
                .param("size", "150")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /zip-codes?federal_entity - Debe validar número de página negativo")
    void shouldValidateNegativePageNumber() throws Exception {
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "méxico")
                .param("page", "-1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /zip-codes?federal_entity - Debe retornar 400 para búsqueda vacía")
    void shouldReturn400ForEmptyFederalEntitySearch() throws Exception {
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /zip-codes?federal_entity - Debe retornar 404 para entidad no encontrada")
    void shouldReturn404ForNotFoundFederalEntity() throws Exception {
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "EntidadInexistente12345XYZ")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /zip-codes/by-municipality - Debe buscar por municipio")
    void shouldSearchByMunicipality() throws Exception {
        mockMvc.perform(get("/zip-codes/by-municipality")
                .param("municipality", "Álvaro Obregón")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.pageNumber").exists())
                .andExpect(jsonPath("$.pageSize").exists());
    }

    @Test
    @DisplayName("GET /zip-codes/by-municipality - Debe soportar paginación")
    void shouldSupportPaginationForMunicipality() throws Exception {
        mockMvc.perform(get("/zip-codes/by-municipality")
                .param("municipality", "guadalajara")
                .param("page", "0")
                .param("size", "5")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(5))
                .andExpect(jsonPath("$.content", hasSize(lessThanOrEqualTo(5))));
    }

    @Test
    @DisplayName("GET /zip-codes/by-municipality - Debe retornar 400 para búsqueda vacía")
    void shouldReturn400ForEmptyMunicipalitySearch() throws Exception {
        mockMvc.perform(get("/zip-codes/by-municipality")
                .param("municipality", "")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /zip-codes/by-municipality - Debe retornar 404 para municipio no encontrado")
    void shouldReturn404ForNotFoundMunicipality() throws Exception {
        mockMvc.perform(get("/zip-codes/by-municipality")
                .param("municipality", "MunicipioInexistente12345XYZ")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /zip-codes/stats - Debe retornar estadísticas")
    void shouldReturnStatistics() throws Exception {
        mockMvc.perform(get("/zip-codes/stats")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalZipCodes").value(greaterThan(0)))
                .andExpect(jsonPath("$.totalFederalEntities").value(greaterThan(0)))
                .andExpect(jsonPath("$.totalMunicipalities").value(greaterThan(0)))
                .andExpect(jsonPath("$.totalSettlements").value(greaterThan(0)));
    }

    @Test
    @DisplayName("GET /zip-codes?federal_entity - Debe ser case insensitive")
    void shouldBeCaseInsensitiveForFederalEntity() throws Exception {
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "MEXICO")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThan(0))));
    }

    @Test
    @DisplayName("GET /zip-codes/by-municipality - Debe ser case insensitive")
    void shouldBeCaseInsensitiveForMunicipality() throws Exception {
        mockMvc.perform(get("/zip-codes/by-municipality")
                .param("municipality", "ALVARO")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /zip-codes?federal_entity - Navegación entre páginas")
    void shouldNavigateBetweenPages() throws Exception {
        // Primera página
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "méxico")
                .param("page", "0")
                .param("size", "5")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        // Segunda página
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "méxico")
                .param("page", "1")
                .param("size", "5")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first").value(false));
    }

    // ============================================================
    // Tests añadidos en #22: cobertura de los endpoints restantes
    // ============================================================

    @Test
    @DisplayName("GET /zip-codes/search - Debe devolver coincidencias de prefijo")
    void shouldSearchByPartialCode() throws Exception {
        mockMvc.perform(get("/zip-codes/search")
                .param("code", "010")
                .param("limit", "5")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(lessThanOrEqualTo(5))));
    }

    @Test
    @DisplayName("GET /zip-codes/search?simplified=true - Debe responder formato compacto")
    void shouldSearchByPartialCodeSimplified() throws Exception {
        mockMvc.perform(get("/zip-codes/search")
                .param("code", "010")
                .param("limit", "3")
                .param("simplified", "true")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].zip_code").exists())
                // En la respuesta simplified no debe aparecer la lista de asentamientos
                .andExpect(jsonPath("$[0].settlements").doesNotExist());
    }

    @Test
    @DisplayName("GET /zip-codes/search - Debe rechazar prefijo no numérico")
    void shouldRejectNonNumericPartialCode() throws Exception {
        mockMvc.perform(get("/zip-codes/search")
                .param("code", "01a")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /zip-codes/search - Debe respetar el límite máximo (50)")
    void shouldRejectLimitGreaterThanFifty() throws Exception {
        mockMvc.perform(get("/zip-codes/search")
                .param("code", "01")
                .param("limit", "999")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /zip-codes/federal-entities - Debe listar entidades")
    void shouldListFederalEntities() throws Exception {
        mockMvc.perform(get("/zip-codes/federal-entities")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].zip_codes_count").exists());
    }

    @Test
    @DisplayName("GET /zip-codes/federal-entities/{x}/municipalities - Debe devolver municipios")
    void shouldListMunicipalitiesByFederalEntity() throws Exception {
        mockMvc.perform(get("/zip-codes/federal-entities/Jalisco/municipalities")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))));
    }

    @Test
    @DisplayName("GET /zip-codes/federal-entities/{x}/municipalities - 404 si no existe")
    void shouldReturn404ForUnknownFederalEntityMunicipalities() throws Exception {
        mockMvc.perform(get("/zip-codes/federal-entities/EntidadInexistente12345XYZ/municipalities")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /zip-codes/{cp}/settlements - Debe devolver colonias")
    void shouldListSettlementsByZipCode() throws Exception {
        mockMvc.perform(get("/zip-codes/01000/settlements")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    @DisplayName("GET /zip-codes/{cp}/settlements - 404 si código postal no existe")
    void shouldReturn404ForUnknownZipCodeSettlements() throws Exception {
        mockMvc.perform(get("/zip-codes/99999/settlements")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /zip-codes/advanced - Debe combinar filtros con paginación")
    void shouldRunAdvancedSearch() throws Exception {
        mockMvc.perform(get("/zip-codes/advanced")
                .param("federal_entity", "Jalisco")
                .param("municipality", "Guadalajara")
                .param("page", "0")
                .param("size", "5")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(lessThanOrEqualTo(5))))
                .andExpect(jsonPath("$.pageNumber").value(0));
    }

    @Test
    @DisplayName("GET /zip-codes/advanced?simplified=true - Debe responder formato compacto")
    void shouldRunAdvancedSearchSimplified() throws Exception {
        mockMvc.perform(get("/zip-codes/advanced")
                .param("federal_entity", "Jalisco")
                .param("municipality", "Guadalajara")
                .param("simplified", "true")
                .param("size", "3")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].settlements_count").exists());
    }

    @Test
    @DisplayName("GET /zip-codes/advanced - 400 si no hay ningún filtro")
    void shouldRejectAdvancedSearchWithNoFilters() throws Exception {
        mockMvc.perform(get("/zip-codes/advanced")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Las respuestas REST deben incluir el header Cache-Control")
    void shouldAddCacheControlToSuccessfulResponses() throws Exception {
        mockMvc.perform(get("/zip-codes/01000")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("Cache-Control"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=")));
    }

    @Test
    @DisplayName("Las respuestas de error NO deben incluir Cache-Control")
    void shouldNotAddCacheControlToErrors() throws Exception {
        mockMvc.perform(get("/zip-codes/99999")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Cache-Control"));
    }

    @Test
    @DisplayName("GET /zip-codes/stats - Debe exponer metadata de frescura del catálogo")
    void shouldExposeCatalogFreshnessInStats() throws Exception {
        mockMvc.perform(get("/zip-codes/stats")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogChecksum").exists())
                .andExpect(jsonPath("$.catalogSource").exists())
                .andExpect(jsonPath("$.loadedAt").exists());
    }

    @Test
    @DisplayName("GET /actuator/health/readiness - Debe reportar UP con el catálogo cargado")
    void shouldReportReadinessUp() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ============================================================
    // Manejo de errores HTTP: parámetro faltante, tipo inválido, 405
    // ============================================================

    @Test
    @DisplayName("GET /zip-codes sin federal_entity - Debe retornar 400 (no 500)")
    void shouldReturn400ForMissingRequiredParameter() throws Exception {
        mockMvc.perform(get("/zip-codes")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("Falta el parámetro requerido: federal_entity")))
                .andExpect(jsonPath("$.path", containsString("/zip-codes")));
    }

    @Test
    @DisplayName("GET /zip-codes?page=abc - Debe retornar 400 por tipo inválido")
    void shouldReturn400ForTypeMismatch() throws Exception {
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "Jalisco")
                .param("page", "abc")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("Valor inválido para el parámetro 'page'")));
    }

    @Test
    @DisplayName("POST /zip-codes/stats - Debe retornar 405 con header Allow")
    void shouldReturn405ForUnsupportedMethod() throws Exception {
        mockMvc.perform(post("/zip-codes/stats")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message", containsString("no soportado")));
    }

    @Test
    @DisplayName("Errores de validación: message describe el error y path contiene la URI")
    void shouldPutValidationErrorsInMessageAndUriInPath() throws Exception {
        mockMvc.perform(get("/zip-codes")
                .param("federal_entity", "méxico")
                .param("size", "150")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Errores de validación")))
                .andExpect(jsonPath("$.path", containsString("/zip-codes")));
    }

    // ============================================================
    // ETag versionado por catálogo / 304 Not Modified
    // ============================================================

    @Test
    @DisplayName("Las respuestas GET deben llevar ETag y honrar If-None-Match con 304")
    void shouldReturnEtagAndHonorIfNoneMatch() throws Exception {
        MvcResult result = mockMvc.perform(get("/zip-codes/01000")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andReturn();

        String etag = result.getResponse().getHeader("ETag");
        assertNotNull(etag);
        assertTrue(etag.startsWith("W/\""), "El ETag debe ser débil (W/) por la compresión");

        // Mismo validador -> 304 sin cuerpo (la búsqueda ni siquiera se ejecuta).
        mockMvc.perform(get("/zip-codes/01000")
                .header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));

        // Validador de otra versión del catálogo -> respuesta completa.
        mockMvc.perform(get("/zip-codes/01000")
                .header("If-None-Match", "W/\"otro-catalogo-0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zip_code").value("01000"));
    }
}
