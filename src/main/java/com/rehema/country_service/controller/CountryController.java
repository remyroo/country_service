package com.rehema.country_service.controller;

import java.net.URI;

import com.rehema.country_service.dto.CountryResponse;
import com.rehema.country_service.dto.CreateCountryRequest;
import com.rehema.country_service.service.CountryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/countries")
public class CountryController {
    private final CountryService service;

    public CountryController(CountryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CountryResponse> create(@Valid @RequestBody CreateCountryRequest request) {
        CountryResponse response = service.createCountry(request.name());
        return ResponseEntity.created(URI.create("/countries/" + response.id())).body(response);
    }
}
