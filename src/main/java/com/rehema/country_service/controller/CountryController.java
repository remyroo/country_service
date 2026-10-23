package com.rehema.country_service.controller;

import java.net.URI;
import java.util.List;
import com.rehema.country_service.dto.UpdateCountryRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

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

    @GetMapping
    public List<CountryResponse> findAll() {
        return service.getCountries();
    }

    @GetMapping("/{id}")
    public CountryResponse findById(@PathVariable Long id) {
        return service.getCountry(id);
    }

    @PutMapping("/{id}")
    public CountryResponse update(@PathVariable Long id, @Valid @RequestBody UpdateCountryRequest request) {
        return service.updateCountry(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteCountry(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    public ResponseEntity<CountryResponse> create(@Valid @RequestBody CreateCountryRequest request) {
        CountryResponse response = service.createCountry(request.name());
        return ResponseEntity.created(URI.create("/countries/" + response.id())).body(response);
    }
}
