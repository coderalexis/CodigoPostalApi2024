package com.coderalexis.CodigoPostalApi.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class AddressControllerTest {
    @Autowired MockMvc mvc;

    @Test void shouldServeWorkingExampleResources() throws Exception {
        mvc.perform(get("/examples/address.html")).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"address-form\"")));
        mvc.perform(get("/examples/address.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/zip-codes/validate-address")));
        mvc.perform(get("/examples/address.css")).andExpect(status().isOk());
    }

    @Test void benchmarkControlsMustNotExistInNormalProfiles() throws Exception {
        mvc.perform(post("/internal/benchmark/reset")).andExpect(status().isNotFound());
    }

    @Test void shouldReturnCompactOptionsWithOfficialCodes() throws Exception {
        mvc.perform(get("/zip-codes/01000/address")).andExpect(status().isOk())
                .andExpect(jsonPath("$.federal_entity_code").value("09"))
                .andExpect(jsonPath("$.municipality_code").value("010"))
                .andExpect(jsonPath("$.settlements", hasItem("San Ángel")))
                .andExpect(header().exists("ETag")).andExpect(header().exists("Cache-Control"));
        mvc.perform(get("/zip-codes/99999/address")).andExpect(status().isNotFound());
        mvc.perform(get("/zip-codes/abc/address")).andExpect(status().isBadRequest());
    }

    @Test void shouldValidateWithoutAccentsAndPreserveLeadingZeros() throws Exception {
        mvc.perform(post("/zip-codes/validate-address").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"zip_code":"01000","federal_entity":" CIUDAD DE MEXICO ","municipality":"alvaro obregon"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.canonical.zip_code").value("01000"))
                .andExpect(jsonPath("$.issues", hasSize(0)))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("ETag"));
    }

    @Test void mismatchesShouldOfferBoundedRealAlternatives() throws Exception {
        mvc.perform(post("/zip-codes/validate-address").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"zip_code":"01000","federal_entity":"Jalisco","municipality":"Guadalajara"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues", containsInAnyOrder("federal_entity_mismatch", "municipality_mismatch")))
                .andExpect(jsonPath("$.suggestions", hasSize(5)))
                .andExpect(jsonPath("$.suggestions[0].zip_code").value("01000"))
                .andExpect(jsonPath("$.suggestions[1].municipality").value("Guadalajara"))
                .andExpect(jsonPath("$.suggestions_truncated").value(true));
    }

    @Test void unknownZipAndLocationShouldReturnNoInventedSuggestion() throws Exception {
        mvc.perform(post("/zip-codes/validate-address").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"zip_code":"99999","federal_entity":"No existe","municipality":"No existe"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues", hasItem("zip_code_not_found")))
                .andExpect(jsonPath("$.suggestions", hasSize(0)));
    }

    @Test void malformedOrIncompleteRequestsShouldBe400() throws Exception {
        for (String body : new String[]{"{", "{}", "{\"zip_code\":\"abc\"}"}) {
            mvc.perform(post("/zip-codes/validate-address").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }
}
