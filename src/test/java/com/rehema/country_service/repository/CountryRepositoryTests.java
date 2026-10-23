package com.rehema.country_service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rehema.country_service.model.CountryInfo;
import com.rehema.country_service.model.Language;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CountryRepositoryTests {

    private final CountryRepository repository;
    private final EntityManager entityManager;
    private final LanguageRepository languageRepository;

    @Autowired
    CountryRepositoryTests(CountryRepository repository, EntityManager entityManager,
            LanguageRepository languageRepository) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.languageRepository = languageRepository;
    }

    @Test
    void savesAndReloadsCountryWithLanguages() {
        CountryInfo country = new CountryInfo("ZZ", "Test country");
        country.setCapital("Test capital");
        country.setPhoneCode("254");
        country.setContinentCode("AF");
        country.setCurrencyCode("KES");
        country.setFlagUrl("https://example.com/flag.png");
        country.addLanguage(languageRepository.save(new Language("test-swa", "Swahili")));
        country.addLanguage(languageRepository.save(new Language("test-eng", "English")));

        repository.saveAndFlush(country);
        entityManager.clear(); // Force a database read instead of returning the cached entity.

        CountryInfo stored = repository.findById(country.getId()).orElseThrow();
        assertThat(stored.getIsoCode()).isEqualTo("ZZ");
        assertThat(stored.getName()).isEqualTo("Test country");
        assertThat(stored.getCapital()).isEqualTo("Test capital");
        assertThat(stored.getPhoneCode()).isEqualTo("254");
        assertThat(stored.getContinentCode()).isEqualTo("AF");
        assertThat(stored.getCurrencyCode()).isEqualTo("KES");
        assertThat(stored.getFlagUrl()).isEqualTo("https://example.com/flag.png");
        assertThat(stored.getLanguages()).extracting(Language::getIsoCode)
                .containsExactlyInAnyOrder("test-swa", "test-eng");
        assertThat(stored.getLanguages()).extracting(Language::getName)
                .containsExactlyInAnyOrder("Swahili", "English");
        assertThat(stored.getLanguages()).allSatisfy(language -> {
            assertThat(language.getId()).isNotNull();
        });
    }

    @Test
    void sharesLanguagesAndPreservesThemWhenAssociationsOrCountriesAreDeleted() {
        CountryInfo country = new CountryInfo("ZZ", "Test country");
        Language swahili = languageRepository.save(new Language("test-swa", "Swahili"));
        Language english = languageRepository.save(new Language("test-eng", "English"));
        country.addLanguage(swahili);
        country.addLanguage(english);
        repository.saveAndFlush(country);
        CountryInfo otherCountry = new CountryInfo("ZX", "Other country");
        otherCountry.addLanguage(swahili);
        otherCountry.addLanguage(new Language(" TEST-SWA ", "Duplicate association"));
        repository.saveAndFlush(otherCountry);
        Long countryId = country.getId();
        Long swahiliId = swahili.getId();
        Long englishId = english.getId();
        entityManager.clear();

        CountryInfo stored = repository.findById(countryId).orElseThrow();
        Language removed = stored.getLanguages().stream()
                .filter(language -> language.getId().equals(swahiliId)).findFirst().orElseThrow();
        stored.removeLanguage(removed);
        stored.setCapital("Updated capital");
        repository.flush();
        entityManager.clear();

        assertThat(entityManager.find(Language.class, swahiliId)).isNotNull();
        assertThat(entityManager.find(Language.class, englishId)).isNotNull();
        assertThat(repository.findById(countryId).orElseThrow().getCapital()).isEqualTo("Updated capital");

        repository.deleteById(countryId);
        repository.flush();
        entityManager.clear();

        assertThat(repository.findById(countryId)).isEmpty();
        assertThat(entityManager.find(Language.class, englishId)).isNotNull();
        CountryInfo remaining = repository.findById(otherCountry.getId()).orElseThrow();
        assertThat(remaining.getLanguages()).extracting(Language::getId).containsExactly(swahiliId);
        assertThat(entityManager.createNativeQuery(
                "select count(*) from country_language where country_id = :id", Long.class)
                .setParameter("id", countryId).getSingleResult()).isEqualTo(0L);
    }

    @Test
    void rejectsDuplicateCountryIsoCodes() {
        repository.saveAndFlush(new CountryInfo("ZZ", "First country"));

        assertThatThrownBy(() -> repository.saveAndFlush(new CountryInfo("ZZ", "Duplicate country")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateLanguageCodes() {
        languageRepository.saveAndFlush(new Language("test-fr", "French"));
        assertThatThrownBy(() -> languageRepository.saveAndFlush(new Language(" TEST-FR ", "French")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateCountryLanguageLinksAtDatabaseLevel() {
        CountryInfo country = new CountryInfo("ZZ", "Test country");
        Language language = languageRepository.save(new Language("test-fr", "French"));
        country.addLanguage(language);
        repository.saveAndFlush(country);

        assertThatThrownBy(() -> entityManager.createNativeQuery(
                "insert into country_language (country_id, language_id) values (:country, :language)")
                .setParameter("country", country.getId())
                .setParameter("language", language.getId()).executeUpdate())
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }
}
