package com.rehema.country_service.dto;

import java.util.List;
import java.util.Comparator;
import com.rehema.country_service.model.Language;
import com.rehema.country_service.model.CountryInfo;

public record CountryResponse(Long id, String isoCode, String name, String capital,
        String phoneCode, String continentCode, String currencyCode, String flagUrl,
        List<LanguageResponse> languages) {
    // Call while languages are loaded or while the database transaction is open.
    public static CountryResponse from(CountryInfo country) {
        return new CountryResponse(country.getId(), country.getIsoCode(), country.getName(), country.getCapital(),
                country.getPhoneCode(), country.getContinentCode(), country.getCurrencyCode(), country.getFlagUrl(),
                country.getLanguages().stream().sorted(Comparator.comparing(Language::getIsoCode)).map(language -> new LanguageResponse(
                        language.getId(), language.getIsoCode(), language.getName())).toList());
    }

    public CountryResponse {
        languages = List.copyOf(languages);
    }
}
