package com.rehema.country_service.service;

import java.util.Comparator;

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
        try {
            return transaction.execute(status -> saveWithSharedLanguages(details));
        } catch (DataIntegrityViolationException exception) {
            if (!isDuplicateLanguage(exception)) {
                throw exception;
            }
            // A concurrent request inserted a language after our lookup. The first
            // transaction has rolled back; retry once and read the committed language.
            return transaction.execute(status -> saveWithSharedLanguages(details));
        }
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

    private boolean isDuplicateLanguage(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                // MySQL may qualify the index name with its table name.
                return "uk_language_iso_code".equals(constraint)
                        || "language.uk_language_iso_code".equals(constraint);
            }
        }
        return false;
    }
}
