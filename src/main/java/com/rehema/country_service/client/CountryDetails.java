package com.rehema.country_service.client;

import java.util.List;

public record CountryDetails(String isoCode, String name, String capital, String phoneCode,
        String continentCode, String currencyCode, String flagUrl, List<LanguageDetails> languages) {
    public CountryDetails {
        languages = List.copyOf(languages);
    }
}
