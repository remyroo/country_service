package com.rehema.country_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LanguageRequest(
        @NotBlank @Size(max = 255) String isoCode,
        @NotBlank @Size(max = 255) String name) {
}
