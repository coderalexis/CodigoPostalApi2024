package com.coderalexis.CodigoPostalApi.controller;

import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.context.ActiveProfiles;

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
}
