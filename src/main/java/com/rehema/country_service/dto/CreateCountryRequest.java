package com.rehema.country_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCountryRequest(
        @NotBlank(message = "Country name must not be blank")
        @Size(max = 255, message = "Country name must be at most 255 characters") String name) {
}
