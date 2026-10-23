package com.rehema.country_service.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "country_info")
public class CountryInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 2, updatable = false)
    private String isoCode;

    @Column(nullable = false)
    private String name;

    private String capital;
    private String phoneCode;
    private String continentCode;
    private String currencyCode;

    @Column(length = 2048)
    private String flagUrl;

    // Languages are shared; saving/deleting a country must not cascade to them.
    @ManyToMany
    @JoinTable(name = "country_language",
            joinColumns = @JoinColumn(name = "country_id"),
            inverseJoinColumns = @JoinColumn(name = "language_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_country_language",
                    columnNames = {"country_id", "language_id"}))
    private List<Language> languages = new ArrayList<>();

    // JPA needs a no-argument constructor to load entities from the database.
    protected CountryInfo() {
    }

    public CountryInfo(String isoCode, String name) {
        this.isoCode = isoCode;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getIsoCode() { return isoCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCapital() { return capital; }
    public void setCapital(String capital) { this.capital = capital; }
    public String getPhoneCode() { return phoneCode; }
    public void setPhoneCode(String phoneCode) { this.phoneCode = phoneCode; }
    public String getContinentCode() { return continentCode; }
    public void setContinentCode(String continentCode) { this.continentCode = continentCode; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public String getFlagUrl() { return flagUrl; }
    public void setFlagUrl(String flagUrl) { this.flagUrl = flagUrl; }

    public List<Language> getLanguages() {
        return Collections.unmodifiableList(languages);
    }

    // Compare stable language codes, including when given different Java objects.
    public void addLanguage(Language language) {
        Objects.requireNonNull(language, "Language must not be null");
        if (languages.stream().noneMatch(existing -> existing.getIsoCode().equals(language.getIsoCode()))) {
            languages.add(language);
        }
    }

    public void removeLanguage(Language language) {
        Objects.requireNonNull(language, "Language must not be null");
        languages.removeIf(existing -> existing.getIsoCode().equals(language.getIsoCode()));
    }
}
