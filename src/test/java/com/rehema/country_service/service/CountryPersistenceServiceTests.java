package com.rehema.country_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.util.Optional;

import com.rehema.country_service.model.CountryInfo;
import com.rehema.country_service.model.Language;
import com.rehema.country_service.repository.CountryRepository;
import com.rehema.country_service.repository.LanguageRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CountryPersistenceServiceTests {

    private final CountryRepository countries = mock(CountryRepository.class);
    private final LanguageRepository languages = mock(LanguageRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final CountryPersistenceService service = new CountryPersistenceService(countries, languages, transactions);

    @Test
    void reusesExistingLanguageAndCreatesOnlyMissingLanguages() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        Language french = new Language("fr", "French");
        when(languages.findByIsoCode("fr")).thenReturn(Optional.of(french));
        when(languages.findByIsoCode("en")).thenReturn(Optional.empty());
        when(languages.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(countries.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        CountryInfo canada = new CountryInfo("CA", "Canada");
        canada.addLanguage(new Language(" FR ", "Different spelling"));
        canada.addLanguage(new Language("en", "English"));
        canada.addLanguage(new Language("EN", "Duplicate"));

        CountryInfo saved = service.create(canada);

        assertThat(saved.getLanguages()).hasSize(2).contains(french);
        assertThat(french.getName()).isEqualTo("French");
        verify(languages, times(1)).saveAndFlush(any());
        verify(transactions).commit(any());
    }

    @Test
    void retriesLanguageConflictInAFreshTransactionWithoutReusingAttemptEntities() {
        SimpleTransactionStatus first = new SimpleTransactionStatus();
        SimpleTransactionStatus second = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(first, second);
        Language concurrentFrench = new Language("fr", "French");
        when(languages.findByIsoCode("fr")).thenReturn(Optional.empty()).thenReturn(Optional.of(concurrentFrench));
        when(languages.saveAndFlush(any())).thenThrow(duplicateLanguage());
        when(countries.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        CountryInfo france = new CountryInfo("FR", "France");
        france.addLanguage(new Language("fr", "French"));

        CountryInfo saved = service.create(france);

        assertThat(saved).isNotSameAs(france);
        assertThat(saved.getLanguages()).containsExactly(concurrentFrench);
        var order = inOrder(transactions);
        order.verify(transactions).getTransaction(any());
        order.verify(transactions).rollback(first);
        order.verify(transactions).getTransaction(any());
        order.verify(transactions).commit(second);
        verify(languages, times(1)).saveAndFlush(any());
    }

    @Test
    void stopsAfterOneRetry() {
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(languages.findByIsoCode("fr")).thenReturn(Optional.empty());
        when(languages.saveAndFlush(any())).thenThrow(duplicateLanguage());
        CountryInfo france = new CountryInfo("FR", "France");
        france.addLanguage(new Language("fr", "French"));

        assertThatThrownBy(() -> service.create(france)).isInstanceOf(DataIntegrityViolationException.class);
        verify(transactions, times(2)).rollback(any());
        verify(transactions, times(2)).getTransaction(any());
    }

    @Test
    void doesNotRetryOtherDatabaseFailures() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(countries.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Duplicate country"));

        assertThatThrownBy(() -> service.create(new CountryInfo("FR", "France")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(transactions, times(1)).getTransaction(any());
    }

    private DataIntegrityViolationException duplicateLanguage() {
        return new DataIntegrityViolationException("Duplicate language", new ConstraintViolationException(
                "Duplicate language", new SQLException("Duplicate key", "23000", 1062),
                "language.uk_language_iso_code"));
    }
}
