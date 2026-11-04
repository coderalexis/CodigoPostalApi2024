package com.coderalexis.CodigoPostalApi.service;

import com.coderalexis.CodigoPostalApi.exceptions.ZipCodeNotFoundException;
import com.coderalexis.CodigoPostalApi.model.*;
import com.coderalexis.CodigoPostalApi.util.Util;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class AddressService {
    private static final int MAX_SUGGESTIONS = 5;
    private final ZipCodeService zipCodes;

    public AddressService(ZipCodeService zipCodes) { this.zipCodes = zipCodes; }

    public AddressOptions options(String code) { return AddressOptions.from(zipCodes.getZipCode(code)); }

    public AddressValidationResult validate(AddressValidationRequest request) {
        String entity = Util.normalizeSearchTerm(request.federalEntity());
        String municipality = Util.normalizeSearchTerm(request.municipality());
        List<String> issues = new ArrayList<>();
        ZipCode code = null;
        try { code = zipCodes.getZipCode(request.zipCode()); }
        catch (ZipCodeNotFoundException ignored) { issues.add("zip_code_not_found"); }
        if (code != null) {
            if (!code.getNormalizedFederalEntity().equals(entity)) issues.add("federal_entity_mismatch");
            if (!code.getNormalizedMunicipality().equals(municipality)) issues.add("municipality_mismatch");
        }
        AddressOptions canonical = code == null ? null : AddressOptions.from(code);
        if (issues.isEmpty()) return new AddressValidationResult(true, List.of(), canonical, List.of(), 0, false);

        // Primero el domicilio del CP proporcionado; después CPs de la ubicación exacta solicitada.
        var alternatives = new LinkedHashMap<String, ZipCode>();
        if (code != null) alternatives.put(code.getZipCode(), code);
        for (ZipCode alternative : zipCodes.findByExactLocation(entity, municipality)) {
            alternatives.putIfAbsent(alternative.getZipCode(), alternative);
        }
        return new AddressValidationResult(false, List.copyOf(issues), canonical,
                alternatives.values().stream().limit(MAX_SUGGESTIONS).map(AddressOptions::from).toList(),
                alternatives.size(), alternatives.size() > MAX_SUGGESTIONS);
    }
}
