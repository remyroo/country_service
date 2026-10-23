package com.rehema.country_service.dto;

import java.util.List;

public record CountryResponse(Long id, String isoCode, String name, String capital,
        String phoneCode, String continentCode, String currencyCode, String flagUrl,
        List<LanguageResponse> languages) {
    public CountryResponse {
        languages = List.copyOf(languages);
    }
}
