package com.rehema.country_service.service;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import com.rehema.country_service.dto.CountryResponse;
import com.rehema.country_service.dto.UpdateCountryRequest;
import com.rehema.country_service.exception.CountryNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CountryPersistenceService.class);
    private final TransactionTemplate readTransaction;
    private final CountryRepository countryRepository;
    private final LanguageRepository languageRepository;
    private final TransactionTemplate transaction;

    public CountryPersistenceService(CountryRepository countryRepository,
            LanguageRepository languageRepository, PlatformTransactionManager transactionManager) {
        this.countryRepository = countryRepository;
        this.languageRepository = languageRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.transaction = new TransactionTemplate(transactionManager);
        // Each attempt needs its own transaction, including when called by another service.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Creates a new stored country from already-fetched data; performs no SOAP calls. */
    public CountryInfo create(CountryInfo details) {
        if (details.getId() != null) {
            throw new IllegalArgumentException("Country must be new");
        }
        return withLanguageRetry(() -> saveWithSharedLanguages(details));
    }

    public List<CountryResponse> findAll() {
        return readTransaction.execute(status -> countryRepository.findAllWithLanguages().stream()
                .map(CountryResponse::from).toList());
    }

    public CountryResponse findById(Long id) {
        return readTransaction.execute(status -> CountryResponse.from(requireCountry(id)));
    }

    public CountryResponse update(Long id, UpdateCountryRequest request) {
        CountryResponse result = withLanguageRetry(() -> {
            CountryInfo country = requireCountry(id);
            country.setName(request.name().strip());
            country.setCapital(request.capital());
            country.setPhoneCode(request.phoneCode());
            country.setContinentCode(request.continentCode());
            country.setCurrencyCode(request.currencyCode());
            country.setFlagUrl(request.flagUrl());
            List.copyOf(country.getLanguages()).forEach(country::removeLanguage);
            List<Language> requested = request.languages().stream()
                    .map(language -> new Language(language.isoCode(), language.name().strip())).toList();
            attachSharedLanguages(country, requested);
            countryRepository.flush();
            return CountryResponse.from(country);
        });
        log.info("Country updated id={}", id);
        return result;
    }

    public void delete(Long id) {
        transaction.executeWithoutResult(status -> {
            countryRepository.delete(requireCountry(id));
            countryRepository.flush();
        });
        log.info("Country deleted id={}", id);
    }

    private CountryInfo requireCountry(Long id) {
        return countryRepository.findById(id).orElseThrow(() -> new CountryNotFoundException(id));
    }

    private <T> T withLanguageRetry(Supplier<T> work) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return transaction.execute(status -> work.get());
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

        attachSharedLanguages(country, details.getLanguages());
        return countryRepository.saveAndFlush(country);
    }

    private void attachSharedLanguages(CountryInfo country, List<Language> languages) {
        // Consistent insertion order reduces deadlock risk when countries share languages.
        languages.stream().sorted(Comparator.comparing(Language::getIsoCode))
                .forEach(language -> {
                    Language shared = languageRepository.findByIsoCode(language.getIsoCode())
                            .orElseGet(() -> languageRepository.saveAndFlush(
                                    new Language(language.getIsoCode(), language.getName())));
                    country.addLanguage(shared);
                });
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
