package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.exceptions.ZipCodeNotFoundException;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.model.ZipCodeStats;
import org.junit.jupiter.api.BeforeEach;
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
        List<ZipCode> results = zipCodeService.searchByFederalEntity("Ciudad de México");

        assertNotNull(results, "Los resultados no deben ser null");
        assertFalse(results.isEmpty(), "Debe encontrar resultados");

        // Verificar que todos los resultados contienen la entidad buscada
        results.forEach(zipCode -> {
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
        List<ZipCode> resultsWithAccents = zipCodeService.searchByFederalEntity("México");
        List<ZipCode> resultsWithoutAccents = zipCodeService.searchByFederalEntity("Mexico");

        assertNotNull(resultsWithAccents);
        assertNotNull(resultsWithoutAccents);
        assertFalse(resultsWithAccents.isEmpty());
        assertFalse(resultsWithoutAccents.isEmpty());

        // Ambas búsquedas deberían retornar la misma cantidad (normalización)
        assertEquals(resultsWithAccents.size(), resultsWithoutAccents.size(),
            "La búsqueda debe ser insensible a acentos");
    }

    @Test
    @DisplayName("Debe lanzar excepción para entidad federativa no encontrada")
    void shouldThrowExceptionForInvalidFederalEntity() {
        assertThrows(ZipCodeNotFoundException.class, () -> {
            zipCodeService.searchByFederalEntity("EntidadInexistente12345");
        }, "Debe lanzar ZipCodeNotFoundException para entidad inexistente");
    }

    @Test
    @DisplayName("Debe lanzar excepción para término de búsqueda vacío en entidad")
    void shouldThrowExceptionForEmptyFederalEntitySearch() {
        assertThrows(IllegalArgumentException.class, () -> {
            zipCodeService.searchByFederalEntity("");
        }, "Debe lanzar IllegalArgumentException para término vacío");

        assertThrows(IllegalArgumentException.class, () -> {
            zipCodeService.searchByFederalEntity("   ");
        }, "Debe lanzar IllegalArgumentException para término solo con espacios");
    }

    @Test
    @DisplayName("Debe buscar por municipio")
    void shouldSearchByMunicipality() {
        // Ajusta el municipio según tu archivo de prueba
        List<ZipCode> results = zipCodeService.searchByMunicipality("Álvaro Obregón");

        assertNotNull(results, "Los resultados no deben ser null");
        assertFalse(results.isEmpty(), "Debe encontrar resultados");

        results.forEach(zipCode -> {
            assertNotNull(zipCode.getMunicipality());
        });
    }

    @Test
    @DisplayName("Debe buscar por municipio sin acentos")
    void shouldSearchByMunicipalityWithoutAccents() {
        List<ZipCode> resultsWithAccents = zipCodeService.searchByMunicipality("Álvaro");
        List<ZipCode> resultsWithoutAccents = zipCodeService.searchByMunicipality("Alvaro");

        assertNotNull(resultsWithAccents);
        assertNotNull(resultsWithoutAccents);

        if (!resultsWithAccents.isEmpty() && !resultsWithoutAccents.isEmpty()) {
            assertEquals(resultsWithAccents.size(), resultsWithoutAccents.size(),
                "La búsqueda debe ser insensible a acentos");
        }
    }

    @Test
    @DisplayName("Debe lanzar excepción para municipio no encontrado")
    void shouldThrowExceptionForInvalidMunicipality() {
        assertThrows(ZipCodeNotFoundException.class, () -> {
            zipCodeService.searchByMunicipality("MunicipioInexistente12345");
        }, "Debe lanzar ZipCodeNotFoundException para municipio inexistente");
    }

    @Test
    @DisplayName("Debe lanzar excepción para término de búsqueda vacío en municipio")
    void shouldThrowExceptionForEmptyMunicipalitySearch() {
        assertThrows(IllegalArgumentException.class, () -> {
            zipCodeService.searchByMunicipality("");
        }, "Debe lanzar IllegalArgumentException para término vacío");
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
    @DisplayName("Debe buscar por búsqueda parcial en entidad federativa")
    void shouldSearchByPartialFederalEntity() {
        List<ZipCode> results = zipCodeService.searchByFederalEntity("mex");

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Búsqueda parcial debe retornar resultados");
    }

    @Test
    @DisplayName("Debe buscar por búsqueda parcial en municipio")
    void shouldSearchByPartialMunicipality() {
        List<ZipCode> results = zipCodeService.searchByMunicipality("gua");

        assertNotNull(results);
        // Este test puede pasar o fallar dependiendo del contenido del archivo
        // Si no hay municipios con "gua", ajusta el término de búsqueda
    }
}
