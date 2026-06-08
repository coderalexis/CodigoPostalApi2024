package com.coderalexis.CodigoPostalApi.controller;

import com.coderalexis.CodigoPostalApi.model.AdvancedSearchRequest;
import com.coderalexis.CodigoPostalApi.model.FederalEntity;
import com.coderalexis.CodigoPostalApi.model.PagedResponse;
import com.coderalexis.CodigoPostalApi.model.Settlements;
import com.coderalexis.CodigoPostalApi.model.ZipCode;
import com.coderalexis.CodigoPostalApi.model.ZipCodeSimplified;
import com.coderalexis.CodigoPostalApi.model.ZipCodeStats;
import com.coderalexis.CodigoPostalApi.service.ZipCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Implementación de {@link ZipCodeApi}. Tras el refactor #16 esta clase
 * contiene exclusivamente la lógica de orquestación: la documentación OpenAPI,
 * las anotaciones de validación y los mapeos REST viven en la interfaz.
 */
@RestController
@Slf4j
@Validated
public class Controller implements ZipCodeApi {

    private final ZipCodeService zipCodeService;

    public Controller(ZipCodeService zipCodeService) {
        this.zipCodeService = zipCodeService;
    }

    @Override
    public ResponseEntity<ZipCode> getZipCode(String zipcode) {
        return ResponseEntity.ok(zipCodeService.getZipCode(zipcode));
    }

    @Override
    public ResponseEntity<PagedResponse<ZipCode>> searchByFederalEntity(String federalEntity, int page, int size) {
        return ResponseEntity.ok(zipCodeService.searchByFederalEntity(federalEntity, page, size));
    }

    @Override
    public ResponseEntity<PagedResponse<ZipCode>> searchByMunicipality(String municipality, int page, int size) {
        return ResponseEntity.ok(zipCodeService.searchByMunicipality(municipality, page, size));
    }

    @Override
    public ResponseEntity<ZipCodeStats> getStats() {
        return ResponseEntity.ok(zipCodeService.getStatistics());
    }

    @Override
    public ResponseEntity<?> searchByPartialCode(String code, int limit, boolean simplified) {
        List<ZipCode> results = zipCodeService.searchByPartialCode(code, limit);
        if (simplified) {
            return ResponseEntity.ok(results.stream()
                    .map(ZipCodeSimplified::fromZipCode)
                    .toList());
        }
        return ResponseEntity.ok(results);
    }

    @Override
    public ResponseEntity<List<FederalEntity>> getAllFederalEntities() {
        return ResponseEntity.ok(zipCodeService.getAllFederalEntities());
    }

    @Override
    public ResponseEntity<List<String>> getMunicipalitiesByFederalEntity(String federalEntity) {
        return ResponseEntity.ok(zipCodeService.getMunicipalitiesByFederalEntity(federalEntity));
    }

    @Override
    public ResponseEntity<List<Settlements>> getSettlementsByZipCode(String zipcode) {
        return ResponseEntity.ok(zipCodeService.getSettlementsByZipCode(zipcode));
    }

    @Override
    public ResponseEntity<?> advancedSearch(
            String federalEntity,
            String municipality,
            String settlement,
            String settlementType,
            String zoneType,
            int page,
            int size,
            boolean simplified) {

        AdvancedSearchRequest request = AdvancedSearchRequest.builder()
                .federalEntity(federalEntity)
                .municipality(municipality)
                .settlement(settlement)
                .settlementType(settlementType)
                .zoneType(zoneType)
                .build();

        PagedResponse<ZipCode> response = zipCodeService.advancedSearch(request, page, size);

        if (simplified) {
            List<ZipCodeSimplified> simplifiedResults = response.getContent().stream()
                    .map(ZipCodeSimplified::fromZipCode)
                    .toList();

            PagedResponse<ZipCodeSimplified> simplifiedResponse = PagedResponse.<ZipCodeSimplified>builder()
                    .content(simplifiedResults)
                    .pageNumber(response.getPageNumber())
                    .pageSize(response.getPageSize())
                    .totalElements(response.getTotalElements())
                    .totalPages(response.getTotalPages())
                    .first(response.isFirst())
                    .last(response.isLast())
                    .build();

            return ResponseEntity.ok(simplifiedResponse);
        }

        return ResponseEntity.ok(response);
    }
}
