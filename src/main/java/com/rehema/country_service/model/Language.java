package com.rehema.country_service.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Locale;

@Entity
@Table(name = "language", uniqueConstraints =
        @UniqueConstraint(name = "uk_language_iso_code", columnNames = "iso_code"))
public class Language {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String isoCode;

    @Column(nullable = false)
    private String name;

    protected Language() {
    }

    public Language(String isoCode, String name) {
        if (isoCode == null || isoCode.isBlank()) {
            throw new IllegalArgumentException("Language ISO code must not be blank");
        }
        this.isoCode = isoCode.strip().toLowerCase(Locale.ROOT);
        this.name = name;
    }

    public Long getId() { return id; }
    public String getIsoCode() { return isoCode; }
    public String getName() { return name; }
}
