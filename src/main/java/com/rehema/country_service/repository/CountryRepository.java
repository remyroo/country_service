package com.rehema.country_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rehema.country_service.model.CountryInfo;

import java.util.List;
import org.springframework.data.jpa.repository.Query;

public interface CountryRepository extends JpaRepository<CountryInfo, Long> {
    @Query("select distinct country from CountryInfo country left join fetch country.languages order by country.id")
    List<CountryInfo> findAllWithLanguages();
}
