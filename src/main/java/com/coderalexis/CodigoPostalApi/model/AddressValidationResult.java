package com.coderalexis.CodigoPostalApi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AddressValidationResult(boolean valid, List<String> issues,
        AddressOptions canonical, List<AddressOptions> suggestions,
        @JsonProperty("total_suggestions") int totalSuggestions,
        @JsonProperty("suggestions_truncated") boolean suggestionsTruncated) {
}
