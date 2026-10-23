package com.rehema.country_service.service;

import java.util.Comparator;
import com.rehema.country_service.exception.DuplicateCountryException;

import com.rehema.country_service.model.CountryInfo;
import com.rehema.country_service.model.Language;
import com.rehema.country_service.repository.CountryRepository;
import com.rehema.country_service.repository.LanguageRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CountryPersistenceService {
    // Hibernate's generated unique-index name for the existing country_info.iso_code mapping.
    // Match this exact index so unrelated integrity failures never become duplicate-country errors.
    private static final String COUNTRY_ISO_CONSTRAINT = "UKt4i713fne36me9v90nbspc23u";

    private final CountryRepository countryRepository;
    private final LanguageRepository languageRepository;
    private final TransactionTemplate transaction;

    public CountryPersistenceService(CountryRepository countryRepository,
            LanguageRepository languageRepository, PlatformTransactionManager transactionManager) {
        this.countryRepository = countryRepository;
        this.languageRepository = languageRepository;
        this.transaction = new TransactionTemplate(transactionManager);
        // Each attempt needs its own transaction, including when called by another service.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Creates a new stored country from already-fetched data; performs no SOAP calls. */
    public CountryInfo create(CountryInfo details) {
        if (details.getId() != null) {
            throw new IllegalArgumentException("Country must be new");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return transaction.execute(status -> saveWithSharedLanguages(details));
            } catch (DataIntegrityViolationException exception) {
                if (violatesConstraint(exception, "country_info", COUNTRY_ISO_CONSTRAINT)) {
                    throw new DuplicateCountryException(exception);
                }
                if (attempt == 1 || !violatesConstraint(exception, "language", "uk_language_iso_code")) {
                    throw exception;
                }
                // Retry only a concurrent language insert, after this transaction rolls back.
            }
        }
        throw new IllegalStateException("Country persistence attempts exhausted");
    }

    private CountryInfo saveWithSharedLanguages(CountryInfo details) {
        // Never reuse entities from a rolled-back attempt: they may have generated IDs.
        CountryInfo country = new CountryInfo(details.getIsoCode(), details.getName());
        country.setCapital(details.getCapital());
        country.setPhoneCode(details.getPhoneCode());
        country.setContinentCode(details.getContinentCode());
        country.setCurrencyCode(details.getCurrencyCode());
        country.setFlagUrl(details.getFlagUrl());

        // Consistent insertion order reduces deadlock risk when countries share languages.
        details.getLanguages().stream().sorted(Comparator.comparing(Language::getIsoCode))
                .forEach(language -> {
                    Language shared = languageRepository.findByIsoCode(language.getIsoCode())
                            .orElseGet(() -> languageRepository.saveAndFlush(
                                    new Language(language.getIsoCode(), language.getName())));
                    country.addLanguage(shared);
                });
        return countryRepository.saveAndFlush(country);
    }

    private boolean violatesConstraint(Throwable exception, String table, String name) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                // MySQL may qualify the index name with its table name.
                return name.equals(constraint) || (table + "." + name).equals(constraint);
            }
        }
        return false;
    }
}
