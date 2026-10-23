package com.rehema.country_service.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import java.util.List;
import java.util.stream.Stream;

import com.rehema.country_service.dto.CountryResponse;
import com.rehema.country_service.dto.LanguageResponse;
import com.rehema.country_service.exception.*;
import com.rehema.country_service.service.CountryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CountryController.class)
class CountryControllerTests {
    private final MockMvc mvc;

    @MockitoBean
    private CountryService service;

    @Autowired
    CountryControllerTests(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void createsCountryWithLocationAndStoredResponse() throws Exception {
        when(service.createCountry("kenya")).thenReturn(new CountryResponse(42L, "KE", "Kenya", "Nairobi",
                "254", "AF", "KES", "https://example.com/ke.png",
                List.of(new LanguageResponse(7L, "swa", "Swahili"))));

        mvc.perform(post("/countries").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"kenya\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/countries/42"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(42)).andExpect(jsonPath("$.isoCode").value("KE"))
                .andExpect(jsonPath("$.name").value("Kenya")).andExpect(jsonPath("$.capital").value("Nairobi"))
                .andExpect(jsonPath("$.phoneCode").value("254")).andExpect(jsonPath("$.continentCode").value("AF"))
                .andExpect(jsonPath("$.currencyCode").value("KES"))
                .andExpect(jsonPath("$.flagUrl").value("https://example.com/ke.png"))
                .andExpect(jsonPath("$.languages[0].id").value(7))
                .andExpect(jsonPath("$.languages[0].isoCode").value("swa"))
                .andExpect(jsonPath("$.languages[0].name").value("Swahili"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"  \"}",
            "{", "", "null", "{\"name\":{}}"})
    void invalidRequestsNeverReachService(String body) throws Exception {
        mvc.perform(post("/countries").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.trace").doesNotExist());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOverlongName() throws Exception {
        mvc.perform(post("/countries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "a".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void mapsFailuresToSafeProblemResponses(RuntimeException failure, int expectedStatus) throws Exception {
        when(service.createCountry("Kenya")).thenThrow(failure);
        mvc.perform(post("/countries").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kenya\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString("internal-secret"))))
                .andExpect(header().doesNotExist("Location"));
    }

    static Stream<Arguments> failures() {
        RuntimeException cause = new RuntimeException("internal-secret");
        return Stream.of(
                Arguments.of(new UnknownCountryException(), 404),
                Arguments.of(new DuplicateCountryException(cause), 409),
                Arguments.of(new InvalidSoapResponseException("internal-secret"), 502),
                Arguments.of(new SoapServiceException(SoapServiceException.Reason.FAULT, cause), 502),
                Arguments.of(new SoapServiceException(SoapServiceException.Reason.UNAVAILABLE, cause), 503),
                Arguments.of(new SoapServiceException(SoapServiceException.Reason.TIMEOUT, cause), 504),
                Arguments.of(new DataIntegrityViolationException("internal-secret"), 500),
                Arguments.of(cause, 500));
    }

    @Test
    void frameworkErrorsKeepTheirHttpStatus() throws Exception {
        mvc.perform(put("/countries").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/countries").contentType(MediaType.TEXT_PLAIN).content("Kenya"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }
}
