package com.rehema.country_service.repository;

import java.util.Optional;

import com.rehema.country_service.model.Language;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LanguageRepository extends JpaRepository<Language, Long> {
    Optional<Language> findByIsoCode(String isoCode);
}
