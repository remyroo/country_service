package com.rehema.country_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rehema.country_service.model.CountryInfo;

public interface CountryRepository extends JpaRepository<CountryInfo, Long> {
}
