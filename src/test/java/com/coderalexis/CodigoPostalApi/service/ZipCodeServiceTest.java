package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.exceptions.ZipCodeNotFoundException;
import com.coderalexis.CodigoPostalApi.model.AdvancedSearchRequest;
import com.coderalexis.CodigoPostalApi.model.PagedResponse;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.model.ZipCodeStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ZipCodeServiceTest {

    @Autowired
    private ZipCodeService zipCodeService;

    @Test
    @DisplayName("Debe cargar los datos al iniciar")
    void shouldLoadDataOnStartup() {
        assertTrue(zipCodeService.isDataLoaded(), "Los datos deberían estar cargados");
        assertTrue(zipCodeService.getZipCodeCount() > 0, "Debe haber códigos postales cargados");
    }

    @Test
    @DisplayName("Debe obtener un código postal válido")
    void shouldGetValidZipCode() {
        // Este test asume que el archivo tiene al menos un código postal
        // Ajusta el código postal según tu archivo de prueba
        ZipCode zipCode = zipCodeService.getZipCode("01000");

        assertNotNull(zipCode, "El código postal no debe ser null");
        assertEquals("01000", zipCode.getZipCode());
        assertNotNull(zipCode.getFederalEntity());
        assertNotNull(zipCode.getMunicipality());
        assertNotNull(zipCode.getSettlements());
        assertFalse(zipCode.getSettlements().isEmpty(), "Debe tener al menos un asentamiento");
    }

    @Test
    @DisplayName("Debe lanzar excepción para código postal inexistente")
    void shouldThrowExceptionForInvalidZipCode() {
        assertThrows(ZipCodeNotFoundException.class, () -> {
            zipCodeService.getZipCode("99999");
        }, "Debe lanzar ZipCodeNotFoundException para código postal inexistente");
    }

    @Test
    @DisplayName("Debe buscar por entidad federativa")
    void shouldSearchByFederalEntity() {
        PagedResponse<ZipCode> results = zipCodeService.searchByFederalEntity("Ciudad de México", 0, 50);

        assertNotNull(results, "Los resultados no deben ser null");
        assertTrue(results.getTotalElements() > 0, "Debe encontrar resultados");
        assertFalse(results.getContent().isEmpty(), "La primera página debe traer contenido");

        // Verificar que todos los resultados contienen la entidad buscada
        results.getContent().forEach(zipCode -> {
            assertNotNull(zipCode.getFederalEntity());
            assertTrue(
                zipCode.getFederalEntity().toLowerCase().contains("ciudad") ||
                zipCode.getFederalEntity().toLowerCase().contains("mexico"),
                "La entidad federativa debe contener el término buscado"
            );
        });
    }

    @Test
    @DisplayName("Debe buscar por entidad federativa sin acentos")
    void shouldSearchByFederalEntityWithoutAccents() {
        PagedResponse<ZipCode> resultsWithAccents = zipCodeService.searchByFederalEntity("México", 0, 1);
        PagedResponse<ZipCode> resultsWithoutAccents = zipCodeService.searchByFederalEntity("Mexico", 0, 1);

        assertNotNull(resultsWithAccents);
        assertNotNull(resultsWithoutAccents);
        assertTrue(resultsWithAccents.getTotalElements() > 0);
        assertTrue(resultsWithoutAccents.getTotalElements() > 0);

        // Ambas búsquedas deberían retornar la misma cantidad (normalización)
        assertEquals(resultsWithAccents.getTotalElements(), resultsWithoutAccents.getTotalElements(),
            "La búsqueda debe ser insensible a acentos");
    }

    @Test
    @DisplayName("Debe lanzar excepción para entidad federativa no encontrada")
    void shouldThrowExceptionForInvalidFederalEntity() {
        assertThrows(ZipCodeNotFoundException.class, () -> {
            zipCodeService.searchByFederalEntity("EntidadInexistente12345", 0, 20);
        }, "Debe lanzar ZipCodeNotFoundException para entidad inexistente");
    }


    @Test
    @DisplayName("Debe validar nulos antes de generar la llave de cache en entidad")
    void shouldValidateNullFederalEntityBeforeCacheKey() {
        assertThrows(IllegalArgumentException.class, () -> {
            zipCodeService.searchByFederalEntity(null, 0, 20);
        }, "Debe lanzar IllegalArgumentException para término null");
    }

    @Test
    @DisplayName("Debe lanzar excepción para término de búsqueda vacío en entidad")
    void shouldThrowExceptionForEmptyFederalEntitySearch() {
        assertThrows(IllegalArgumentException.class, () -> {
            zipCodeService.searchByFederalEntity("", 0, 20);
        }, "Debe lanzar IllegalArgumentException para término vacío");

        assertThrows(IllegalArgumentException.class, () -> {
            zipCodeService.searchByFederalEntity("   ", 0, 20);
        }, "Debe lanzar IllegalArgumentException para término solo con espacios");
    }

    @Test
    @DisplayName("Debe buscar por municipio")
    void shouldSearchByMunicipality() {
        // Ajusta el municipio según tu archivo de prueba
        PagedResponse<ZipCode> results = zipCodeService.searchByMunicipality("Álvaro Obregón", 0, 50);

        assertNotNull(results, "Los resultados no deben ser null");
        assertTrue(results.getTotalElements() > 0, "Debe encontrar resultados");

        results.getContent().forEach(zipCode -> {
            assertNotNull(zipCode.getMunicipality());
        });
    }

    @Test
    @DisplayName("Debe buscar por municipio sin acentos")
    void shouldSearchByMunicipalityWithoutAccents() {
        PagedResponse<ZipCode> resultsWithAccents = zipCodeService.searchByMunicipality("Álvaro", 0, 1);
        PagedResponse<ZipCode> resultsWithoutAccents = zipCodeService.searchByMunicipality("Alvaro", 0, 1);

        assertNotNull(resultsWithAccents);
        assertNotNull(resultsWithoutAccents);

        assertEquals(resultsWithAccents.getTotalElements(), resultsWithoutAccents.getTotalElements(),
            "La búsqueda debe ser insensible a acentos");
    }

    @Test
    @DisplayName("Debe lanzar excepción para municipio no encontrado")
    void shouldThrowExceptionForInvalidMunicipality() {
        assertThrows(ZipCodeNotFoundException.class, () -> {
            zipCodeService.searchByMunicipality("MunicipioInexistente12345", 0, 20);
        }, "Debe lanzar ZipCodeNotFoundException para municipio inexistente");
    }

    @Test
    @DisplayName("Debe lanzar excepción para término de búsqueda vacío en municipio")
    void shouldThrowExceptionForEmptyMunicipalitySearch() {
        assertThrows(IllegalArgumentException.class, () -> {
            zipCodeService.searchByMunicipality("", 0, 20);
        }, "Debe lanzar IllegalArgumentException para término vacío");
    }

    @Test
    @DisplayName("Debe paginar búsqueda por entidad federativa sin perder metadatos")
    void shouldPaginateFederalEntitySearch() {
        PagedResponse<ZipCode> firstPage = zipCodeService.searchByFederalEntity("Ciudad de México", 0, 5);

        assertNotNull(firstPage);
        assertEquals(0, firstPage.getPageNumber());
        assertEquals(5, firstPage.getPageSize());
        assertTrue(firstPage.getTotalElements() > 0, "Debe conservar el total real de elementos");
        assertEquals(Math.min(5, (int) firstPage.getTotalElements()), firstPage.getContent().size());
        assertTrue(firstPage.isFirst());
    }

    @Test
    @DisplayName("Debe devolver página vacía cuando la página solicitada excede resultados")
    void shouldReturnEmptyPageWhenPageExceedsResults() {
        PagedResponse<ZipCode> response = zipCodeService.searchByMunicipality("Guadalajara", 10_000, 100);

        assertNotNull(response);
        assertTrue(response.getContent().isEmpty(), "Una página fuera de rango debe regresar contenido vacío");
        assertTrue(response.isLast(), "Una página fuera de rango debe considerarse la última");
        assertTrue(response.getTotalElements() > 0, "Debe conservar el total real de elementos");
    }

    @Test
    @DisplayName("Debe validar paginación inválida en el servicio")
    void shouldRejectInvalidServicePagination() {
        assertThrows(IllegalArgumentException.class,
                () -> zipCodeService.searchByFederalEntity("Jalisco", -1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> zipCodeService.searchByMunicipality("Guadalajara", 0, 0));
    }

    @Test
    @DisplayName("Debe obtener estadísticas correctas")
    void shouldGetStatistics() {
        ZipCodeStats stats = zipCodeService.getStatistics();

        assertNotNull(stats, "Las estadísticas no deben ser null");
        assertTrue(stats.getTotalZipCodes() > 0, "Debe haber códigos postales");
        assertTrue(stats.getTotalFederalEntities() > 0, "Debe haber entidades federativas");
        assertTrue(stats.getTotalMunicipalities() > 0, "Debe haber municipios");
        assertTrue(stats.getTotalSettlements() > 0, "Debe haber asentamientos");

        // La cantidad de asentamientos debe ser mayor o igual a la de códigos postales
        assertTrue(stats.getTotalSettlements() >= stats.getTotalZipCodes(),
            "Debe haber al menos tantos asentamientos como códigos postales");
    }

    @Test
    @DisplayName("Debe buscar códigos postales por prefijo sin incluir otros rangos")
    void shouldSearchByPartialCodeWithoutLeakingNextRange() {
        List<ZipCode> results = zipCodeService.searchByPartialCode("019", 10);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Debe encontrar códigos que inicien con 019");
        assertTrue(results.stream().allMatch(zipCode -> zipCode.getZipCode().startsWith("019")),
            "Todos los resultados deben iniciar exactamente con el prefijo solicitado");

        assertThrows(ZipCodeNotFoundException.class, () -> zipCodeService.searchByPartialCode("0199", 10),
            "No debe regresar códigos 020xx cuando el prefijo solicitado es 0199");
    }

    @Test
    @DisplayName("Debe validar longitud máxima en búsqueda parcial")
    void shouldRejectPartialCodeLongerThanFiveDigits() {
        assertThrows(IllegalArgumentException.class, () -> zipCodeService.searchByPartialCode("010000", 10),
            "La búsqueda parcial debe aceptar máximo 5 dígitos");
    }

    @Test
    @DisplayName("Debe buscar por búsqueda parcial en entidad federativa")
    void shouldSearchByPartialFederalEntity() {
        PagedResponse<ZipCode> results = zipCodeService.searchByFederalEntity("mex", 0, 20);

        assertNotNull(results);
        assertTrue(results.getTotalElements() > 0, "Búsqueda parcial debe retornar resultados");
    }

    @Test
    @DisplayName("Debe buscar por búsqueda parcial en municipio")
    void shouldSearchByPartialMunicipality() {
        PagedResponse<ZipCode> results = zipCodeService.searchByMunicipality("gua", 0, 20);

        assertNotNull(results);
        // Este test puede pasar o fallar dependiendo del contenido del archivo
        // Si no hay municipios con "gua", ajusta el término de búsqueda
        assertTrue(results.getTotalElements() > 0);
    }

    @Test
    @DisplayName("Debe usar la misma llave de cache avanzada para distintas paginas y formatos")
    void shouldUseSameAdvancedSearchCacheKeyForPaginationAndFormatOptions() {
        AdvancedSearchRequest mixedCase = AdvancedSearchRequest.builder()
                .federalEntity(" Jalisco ")
                .municipality("Guadalajara")
                .zoneType("Urbano")
                .build();

        AdvancedSearchRequest lowercase = AdvancedSearchRequest.builder()
                .federalEntity("jalisco")
                .municipality("guadalajara")
                .zoneType("urbano")
                .build();

        assertEquals(mixedCase.normalizedFilterCacheKey(), lowercase.normalizedFilterCacheKey(),
            "La cache de busqueda avanzada debe depender solo de los filtros normalizados");
    }

    @Test
    @DisplayName("Debe paginar búsqueda avanzada sin materializar desde el controlador")
    void shouldPaginateAdvancedSearchInService() {
        AdvancedSearchRequest request = AdvancedSearchRequest.builder()
                .federalEntity("Jalisco")
                .municipality("Guadalajara")
                .zoneType("Urbano")
                .build();

        PagedResponse<ZipCode> firstPage = zipCodeService.advancedSearch(request, 0, 5);

        assertNotNull(firstPage);
        assertEquals(0, firstPage.getPageNumber());
        assertEquals(5, firstPage.getPageSize());
        assertTrue(firstPage.getTotalElements() > 0, "Debe encontrar resultados con los filtros dados");
        assertEquals(Math.min(5, (int) firstPage.getTotalElements()), firstPage.getContent().size());
        assertTrue(firstPage.getContent().stream()
                .allMatch(zipCode -> zipCode.getZipCode().compareTo("00000") >= 0));
    }

    @Test
    @DisplayName("Debe validar paginación inválida en búsqueda avanzada paginada")
    void shouldRejectInvalidAdvancedSearchPagination() {
        AdvancedSearchRequest request = AdvancedSearchRequest.builder()
                .federalEntity("Jalisco")
                .build();

        assertThrows(IllegalArgumentException.class, () -> zipCodeService.advancedSearch(request, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> zipCodeService.advancedSearch(request, 0, 0));
    }

}
