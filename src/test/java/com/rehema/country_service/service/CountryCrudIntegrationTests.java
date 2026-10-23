package com.rehema.country_service.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.rehema.country_service.client.CountrySoapClient;
import com.rehema.country_service.dto.*;
import com.rehema.country_service.exception.CountryNotFoundException;
import com.rehema.country_service.model.*;
import com.rehema.country_service.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class CountryCrudIntegrationTests {
    @MockitoBean
    private CountrySoapClient soap;
    private final CountryService service;
    private final CountryPersistenceService persistence;
    private final CountryRepository countries;
    private final LanguageRepository languages;
    private final TransactionTemplate transaction;
    private final List<Long> createdIds = new ArrayList<>();
    private final String prefix = "crud-" + UUID.randomUUID();
    private final List<String> languageCodes = new ArrayList<>();

    @Autowired
    CountryCrudIntegrationTests(CountryService service, CountryPersistenceService persistence,
            CountryRepository countries, LanguageRepository languages, PlatformTransactionManager manager) {
        this.service = service;
        this.persistence = persistence;
        this.countries = countries;
        this.languages = languages;
        this.transaction = new TransactionTemplate(manager);
    }

    @AfterEach
    void cleanUp() {
        transaction.executeWithoutResult(status -> {
            countries.deleteAllById(createdIds);
            countries.flush();
            languageCodes.forEach(code -> languages.findByIsoCode(code).ifPresent(languages::delete));
        });
        verifyNoInteractions(soap);
    }

    @Test
    void readsUpdatesReplacesAndDeletesWithoutAffectingSharedLanguages() {
        String french = code("fr");
        String english = code("en");
        Long canada = create("ZZ", french);
        Long france = create("ZX", french);
        Long frenchId = service.getCountry(france).languages().getFirst().id();
        assertThat(service.getCountries()).extracting(CountryResponse::id).contains(canada, france);

        var request = new UpdateCountryRequest(" Updated Canada ", "Ottawa", "1", "NA", "CAD", "https://example.com/ca",
                List.of(new LanguageRequest(french.toUpperCase(), "Do not rename French"),
                        new LanguageRequest(english, "English"), new LanguageRequest(english, "Duplicate")));
        var result = service.updateCountry(canada, request);
        assertThat(result.id()).isEqualTo(canada);
        assertThat(result.isoCode()).isEqualTo("ZZ");
        assertThat(result.name()).isEqualTo("Updated Canada");
        assertThat(result.capital()).isEqualTo("Ottawa");
        assertThat(result.phoneCode()).isEqualTo("1");
        assertThat(result.continentCode()).isEqualTo("NA");
        assertThat(result.currencyCode()).isEqualTo("CAD");
        assertThat(result.flagUrl()).isEqualTo("https://example.com/ca");
        assertThat(result.languages()).hasSize(2).contains(new LanguageResponse(frenchId, french, "French"));
        assertThat(service.getCountry(canada)).isEqualTo(result);

        service.updateCountry(canada, new UpdateCountryRequest("Canada", null, null, null, null, null,
                List.of(new LanguageRequest(english, "English"))));
        assertThat(service.getCountry(canada).languages()).extracting(LanguageResponse::isoCode).containsExactly(english);
        assertThat(service.getCountry(france).languages()).extracting(LanguageResponse::id).containsExactly(frenchId);

        service.updateCountry(canada, new UpdateCountryRequest("Canada", null, null, null, null, null, List.of()));
        assertThat(service.getCountry(canada).languages()).isEmpty();
        assertThat(service.getCountry(canada).capital()).isNull();
        service.deleteCountry(france);
        assertThatThrownBy(() -> service.getCountry(france)).isInstanceOf(CountryNotFoundException.class);
        assertThat(languages.findByIsoCode(french)).isPresent();
        assertThat(languages.findByIsoCode(english)).isPresent();
        assertThat(service.getCountries()).extracting(CountryResponse::id).doesNotContain(france);
    }

    @Test
    void missingIdsFailForReadUpdateAndDelete() {
        assertThatThrownBy(() -> service.getCountry(-1L)).isInstanceOf(CountryNotFoundException.class);
        assertThatThrownBy(() -> service.updateCountry(-1L,
                new UpdateCountryRequest("Missing", null, null, null, null, null, List.of())))
                .isInstanceOf(CountryNotFoundException.class);
        assertThatThrownBy(() -> service.deleteCountry(-1L)).isInstanceOf(CountryNotFoundException.class);
    }

    @Test
    void failedUpdateRollsBackFieldsAssociationsAndNewLanguages() {
        String original = code("original");
        String added = code("a-new");
        String invalid = code("z-invalid");
        Long id = create("ZZ", original);
        CountryResponse before = service.getCountry(id);
        // Bypass REST validation deliberately to force a real database failure.
        assertThatThrownBy(() -> service.updateCountry(id, new UpdateCountryRequest("Changed", "Changed", null,
                null, null, null, List.of(new LanguageRequest(added, "New"),
                        new LanguageRequest(invalid, "x".repeat(256))))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(service.getCountry(id)).isEqualTo(before);
        assertThat(languages.findByIsoCode(added)).isEmpty();
        assertThat(languages.findByIsoCode(invalid)).isEmpty();
    }

    private String code(String suffix) {
        String code = prefix + "-" + suffix;
        languageCodes.add(code);
        return code;
    }

    private Long create(String isoCode, String languageCode) {
        CountryInfo country = new CountryInfo(isoCode, "Test country");
        country.addLanguage(new Language(languageCode, "French"));
        Long id = persistence.create(country).getId();
        createdIds.add(id);
        return id;
    }
}
