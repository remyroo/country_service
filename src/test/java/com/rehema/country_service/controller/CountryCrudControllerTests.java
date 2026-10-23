package com.rehema.country_service.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import com.rehema.country_service.dto.*;
import com.rehema.country_service.exception.CountryNotFoundException;
import com.rehema.country_service.service.CountryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CountryController.class)
class CountryCrudControllerTests {
    private final MockMvc mvc;
    @MockitoBean
    private CountryService service;

    @Autowired
    CountryCrudControllerTests(MockMvc mvc) { this.mvc = mvc; }

    private CountryResponse response() {
        return new CountryResponse(1L, "CA", "Canada", "Ottawa", "1", "NA", "CAD", null,
                List.of(new LanguageResponse(2L, "fr", "French")));
    }

    @Test
    void listsCountriesAndReturnsEmptyArrayWhenEmpty() throws Exception {
        when(service.getCountries()).thenReturn(List.of(response()), List.of());
        mvc.perform(get("/countries")).andExpect(status().isOk()).andExpect(jsonPath("$[0].languages[0].isoCode").value("fr"));
        mvc.perform(get("/countries")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void readsOneCountry() throws Exception {
        when(service.getCountry(1L)).thenReturn(response());
        mvc.perform(get("/countries/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.isoCode").value("CA")).andExpect(jsonPath("$.languages[0].id").value(2));
    }

    @Test
    void updatesAndDeletes() throws Exception {
        when(service.updateCountry(eq(1L), any())).thenReturn(response());
        mvc.perform(put("/countries/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Canada\",\"languages\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
        verify(service).updateCountry(1L, new UpdateCountryRequest("Canada", null, null, null, null, null, List.of()));
        mvc.perform(delete("/countries/1")).andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).deleteCountry(1L);
    }

    @Test
    void missingIdsReturn404() throws Exception {
        when(service.getCountry(99L)).thenThrow(new CountryNotFoundException(99L));
        when(service.updateCountry(eq(99L), any())).thenThrow(new CountryNotFoundException(99L));
        doThrow(new CountryNotFoundException(99L)).when(service).deleteCountry(99L);
        mvc.perform(get("/countries/99")).andExpect(status().isNotFound());
        mvc.perform(put("/countries/99").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Canada\",\"languages\":[]}")).andExpect(status().isNotFound());
        mvc.perform(delete("/countries/99")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\"Canada\"}", "{\"name\":\" \",\"languages\":[]}",
            "{\"name\":\"Canada\",\"languages\":null}", "{\"name\":\"Canada\",\"languages\":[null]}",
            "{\"name\":\"Canada\",\"languages\":[{\"isoCode\":\"\",\"name\":\"French\"}]}",
            "{\"name\":\"Canada\",\"languages\":[{\"isoCode\":\"fr\",\"name\":\" \"}]}", "{"})
    void rejectsInvalidUpdates(String body) throws Exception {
        mvc.perform(put("/countries/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOversizedFieldsAndInvalidIdFormats() throws Exception {
        mvc.perform(put("/countries/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Canada\",\"capital\":\"" + "x".repeat(256) + "\",\"languages\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        mvc.perform(get("/countries/not-a-number")).andExpect(status().isBadRequest());
        mvc.perform(delete("/countries/not-a-number")).andExpect(status().isBadRequest());
        mvc.perform(put("/countries/not-a-number").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Canada\",\"languages\":[]}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
