package com.rehema.country_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.rehema.country_service.model.CountryInfo;
import com.rehema.country_service.model.Language;
import com.rehema.country_service.repository.CountryRepository;
import com.rehema.country_service.repository.LanguageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class CountryPersistenceIntegrationTests {

    @MockitoSpyBean
    private LanguageRepository languages;

    private final CountryPersistenceService service;
    private final CountryRepository countries;
    private final TransactionTemplate transaction;

    @Autowired
    CountryPersistenceIntegrationTests(CountryPersistenceService service,
            CountryRepository countries, PlatformTransactionManager transactionManager) {
        this.service = service;
        this.countries = countries;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void concurrentCountriesReuseOneLanguageAfterBothInitiallyFindItMissing() throws Exception {
        String languageCode = "test-fr-" + UUID.randomUUID();
        CountDownLatch lookups = new CountDownLatch(2);
        ConcurrentLinkedQueue<Long> createdIds = new ConcurrentLinkedQueue<>();
        var repositoryDelegate = mockingDetails(languages).getMockCreationSettings().getDefaultAnswer();
        // Force the race: both real database lookups must finish before either inserts.
        doAnswer(call -> {
            Optional<?> result = (Optional<?>) repositoryDelegate.answer(call);
            if (result.isEmpty()) {
                lookups.countDown();
                if (!lookups.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent lookup did not arrive");
                }
            }
            return result;
        }).when(languages).findByIsoCode(languageCode);

        try {
            try (var executor = Executors.newFixedThreadPool(2)) {
                var results = List.of("ZZ", "ZX").stream().map(code -> executor.submit(() -> {
                    CountryInfo details = new CountryInfo(code, "Concurrent test country");
                    details.addLanguage(new Language(languageCode, "French"));
                    CountryInfo saved = service.create(details);
                    createdIds.add(saved.getId());
                    return saved.getId();
                })).toList();
                for (var result : results) {
                    result.get(20, TimeUnit.SECONDS);
                }
            }

            transaction.executeWithoutResult(status -> {
                Language shared = languages.findByIsoCode(languageCode).orElseThrow();
                assertThat(createdIds).hasSize(2);
                for (Long id : createdIds) {
                    assertThat(countries.findById(id).orElseThrow().getLanguages())
                            .extracting(Language::getId).containsExactly(shared.getId());
                }
            });
            assertThatThrownBy(() -> service.create(new CountryInfo("ZZ", "Duplicate test country")))
                    .isInstanceOf(com.rehema.country_service.exception.DuplicateCountryException.class);
        } finally {
            // These transactions commit to exercise the race; remove only this test's rows.
            transaction.executeWithoutResult(status -> {
                countries.deleteAllById(createdIds);
                countries.flush();
                languages.findByIsoCode(languageCode).ifPresent(languages::delete);
            });
        }
    }
}
