package com.rehema.country_service.service;

import java.util.Locale;
import java.util.List;
import com.rehema.country_service.dto.UpdateCountryRequest;

import com.rehema.country_service.client.CountryDetails;
import com.rehema.country_service.client.CountrySoapClient;
import com.rehema.country_service.dto.CountryResponse;
import com.rehema.country_service.model.CountryInfo;
import com.rehema.country_service.model.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CountryService {
    private static final Logger log = LoggerFactory.getLogger(CountryService.class);
    private final CountrySoapClient soapClient;
    private final CountryPersistenceService persistenceService;

    public CountryService(CountrySoapClient soapClient, CountryPersistenceService persistenceService) {
        this.soapClient = soapClient;
        this.persistenceService = persistenceService;
    }

    // No transaction here: network calls must finish before database work starts.
    public CountryResponse createCountry(String name) {
        String normalizedName = normalizeName(name);
        log.info("Starting country lookup");
        String isoCode = soapClient.getCountryIsoCode(normalizedName);
        CountryDetails details = soapClient.getFullCountryInfo(isoCode);
        log.info("Country lookup succeeded isoCode={}", isoCode);

        CountryInfo country = new CountryInfo(details.isoCode(), details.name());
        country.setCapital(details.capital());
        country.setPhoneCode(details.phoneCode());
        country.setContinentCode(details.continentCode());
        country.setCurrencyCode(details.currencyCode());
        country.setFlagUrl(details.flagUrl());
        details.languages().forEach(language -> country.addLanguage(new Language(language.isoCode(), language.name())));

        CountryInfo saved = persistenceService.create(country);
        log.info("Country saved id={} isoCode={}", saved.getId(), saved.getIsoCode());
        return CountryResponse.from(saved);
    }

    public List<CountryResponse> getCountries() {
        return persistenceService.findAll();
    }

    public CountryResponse getCountry(Long id) {
        return persistenceService.findById(id);
    }

    public CountryResponse updateCountry(Long id, UpdateCountryRequest request) {
        return persistenceService.update(id, request);
    }

    public void deleteCountry(Long id) {
        persistenceService.delete(id);
    }

    static String normalizeName(String name) {
        if (name == null || name.isBlank() || name.length() > 255) {
            throw new IllegalArgumentException("Country name must contain 1 to 255 characters");
        }
        String lower = name.strip().toLowerCase(Locale.ROOT);
        int firstCharacterEnd = lower.offsetByCodePoints(0, 1);
        return lower.substring(0, firstCharacterEnd).toUpperCase(Locale.ROOT) + lower.substring(firstCharacterEnd);
    }
}
