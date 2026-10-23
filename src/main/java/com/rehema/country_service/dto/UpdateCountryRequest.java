package com.rehema.country_service.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateCountryRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String capital,
        @Size(max = 255) String phoneCode,
        @Size(max = 255) String continentCode,
        @Size(max = 255) String currencyCode,
        @Size(max = 2048) String flagUrl,
        @NotNull List<@NotNull @Valid LanguageRequest> languages) {
}
