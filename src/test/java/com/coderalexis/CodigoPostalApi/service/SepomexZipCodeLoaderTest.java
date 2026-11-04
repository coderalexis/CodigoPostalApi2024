package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.config.MetricsConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.*;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class SepomexZipCodeLoaderTest {
    @TempDir Path dir;
    static final String HEADER = "d_codigo|d_asenta|d_tipo_asenta|D_mnpio|d_estado|d_ciudad|d_CP|c_estado|c_oficina|c_CP|c_tipo_asenta|c_mnpio|id_asenta_cpcons|d_zona|c_cve_ciudad";
    static final String ROW = "01000|San Ángel|Colonia|Benito Juárez|Ciudad de México||01001|09|01001||09|010|0001|Urbano|01";

    private SepomexZipCodeLoader loader(String rows, Charset encoding) throws IOException {
        Path file = dir.resolve("catalog.txt");
        Files.writeString(file, "A".repeat(5000) + "\n" + HEADER + "\n" + rows + "\n", encoding);
        var loader = new SepomexZipCodeLoader();
        ReflectionTestUtils.setField(loader, "filePath", file.toString());
        return loader;
    }

    @Test void shouldDecodeUtf8AndLatin1AfterLongAsciiPrefix() throws Exception {
        for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1}) {
            assertEquals("San Ángel", loader(ROW, encoding).load().byCode().get("01000").getSettlements().getFirst().name());
        }
    }

    @Test void shouldRejectPartialCorruptDuplicateAndConflictingCatalogs() throws Exception {
        for (String rows : new String[]{ROW + "\nbad", ROW + "\n" + ROW,
                ROW + "\n" + ROW.replace("|09|01001|", "|32|01001|").replace("|0001|", "|0002|"), ""}) {
            var loader = loader(rows, StandardCharsets.UTF_8);
            assertThrows(IOException.class, loader::load);
        }
    }

    @Test void municipalityIdentityMustIncludeState() throws Exception {
        String second = ROW.replace("01000|", "98000|").replace("Ciudad de México", "Zacatecas").replace("|09|01001|", "|32|01001|");
        var loader = loader(ROW + "\n" + second, StandardCharsets.UTF_8);
        var registry = new SimpleMeterRegistry();
        try {
            var service = new ZipCodeService(new MetricsConfiguration(registry), loader, registry);
            service.loadZipCodes();
            assertEquals(2, service.getStatistics().getTotalMunicipalities());
            assertEquals(2, service.searchByMunicipality("Benito Juarez", 0, 20).getTotalElements());
        } finally { registry.close(); }
    }

    @Test void shouldCheckActiveVersionChecksumAndManifest() throws Exception {
        var loader = loader(ROW, StandardCharsets.UTF_8);
        String checksum = loader.load().checksum();
        Files.createDirectories(dir.resolve("versions"));
        Files.copy(dir.resolve("catalog.txt"), dir.resolve("versions/" + checksum + ".txt"));
        Files.writeString(dir.resolve("active.json"), "{\"current\":\"" + checksum + "\"}");
        ReflectionTestUtils.setField(loader, "catalogDirectory", dir.toString());
        assertEquals(checksum, loader.load().checksum());
        Path version = dir.resolve("versions/" + checksum + ".txt");
        Files.writeString(version, Files.readString(version).replace("San Ángel", "San Juan"));
        assertTrue(assertThrows(IOException.class, loader::load).getMessage().contains("Checksum incorrecto"));
        Files.writeString(dir.resolve("active.json"), "{\"current\":\"../escape\"}");
        assertThrows(IOException.class, loader::load);
    }
}
