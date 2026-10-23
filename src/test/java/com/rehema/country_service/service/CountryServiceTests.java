package com.rehema.country_service.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Locale;

import com.rehema.country_service.client.CountryDetails;
import com.rehema.country_service.client.CountrySoapClient;
import com.rehema.country_service.client.LanguageDetails;
import com.rehema.country_service.exception.InvalidSoapResponseException;
import com.rehema.country_service.exception.UnknownCountryException;
import com.rehema.country_service.model.CountryInfo;
import com.rehema.country_service.model.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class CountryServiceTests {
    private final CountrySoapClient soap = mock(CountrySoapClient.class);
    private final CountryPersistenceService persistence = mock(CountryPersistenceService.class);
    private final CountryService service = new CountryService(soap, persistence);

    @ParameterizedTest
    @CsvSource(value = {"kenya|Kenya", "  kENYA  |Kenya", "UNITED STATES|United states", "éGYPT|Égypt"},
            delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
    void normalizesSentenceCase(String input, String expected) {
        assertThat(CountryService.normalizeName(input)).isEqualTo(expected);
    }

    @Test
    void normalizationDoesNotDependOnMachineLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(CountryService.normalizeName("INDIA")).isEqualTo("India");
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsEmptyNamesBeforeAnyExternalWork(String name) {
        assertThatThrownBy(() -> service.createCountry(name)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(soap, persistence);
    }

    @Test
    void callsSoapInOrderMapsAllFieldsAndReturnsStoredValues() {
        when(soap.getCountryIsoCode("Kenya")).thenReturn("KE");
        when(soap.getFullCountryInfo("KE")).thenReturn(new CountryDetails("KE", "Kenya", "Nairobi", "254",
                "AF", "KES", "https://example.com/ke.png",
                List.of(new LanguageDetails("swa", "Swahili"), new LanguageDetails("en", "English"))));
        when(persistence.create(any())).thenAnswer(call -> {
            CountryInfo country = call.getArgument(0);
            ReflectionTestUtils.setField(country, "id", 42L);
            ReflectionTestUtils.setField(country.getLanguages().get(0), "id", 7L);
            ReflectionTestUtils.setField(country.getLanguages().get(1), "id", 8L);
            return country;
        });

        var response = service.createCountry("  kENYA  ");

        var order = inOrder(soap, persistence);
        order.verify(soap).getCountryIsoCode("Kenya");
        order.verify(soap).getFullCountryInfo("KE");
        ArgumentCaptor<CountryInfo> captured = ArgumentCaptor.forClass(CountryInfo.class);
        order.verify(persistence).create(captured.capture());
        verifyNoMoreInteractions(soap, persistence);
        assertThat(captured.getValue().getLanguages()).extracting(Language::getIsoCode).containsExactly("swa", "en");
        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.isoCode()).isEqualTo("KE");
        assertThat(response.name()).isEqualTo("Kenya");
        assertThat(response.capital()).isEqualTo("Nairobi");
        assertThat(response.phoneCode()).isEqualTo("254");
        assertThat(response.continentCode()).isEqualTo("AF");
        assertThat(response.currencyCode()).isEqualTo("KES");
        assertThat(response.flagUrl()).isEqualTo("https://example.com/ke.png");
        assertThat(response.languages()).containsExactlyInAnyOrder(
                new com.rehema.country_service.dto.LanguageResponse(7L, "swa", "Swahili"),
                new com.rehema.country_service.dto.LanguageResponse(8L, "en", "English"));
    }

    @Test
    void isoFailureStopsFullLookupAndPersistence() {
        when(soap.getCountryIsoCode("Atlantis")).thenThrow(new UnknownCountryException());
        assertThatThrownBy(() -> service.createCountry("atlantis")).isInstanceOf(UnknownCountryException.class);
        verify(soap).getCountryIsoCode("Atlantis");
        verifyNoMoreInteractions(soap);
        verifyNoInteractions(persistence);
    }

    @Test
    void fullLookupFailureStopsPersistence() {
        when(soap.getCountryIsoCode("Kenya")).thenReturn("KE");
        when(soap.getFullCountryInfo("KE")).thenThrow(new InvalidSoapResponseException("Invalid data"));
        assertThatThrownBy(() -> service.createCountry("Kenya")).isInstanceOf(InvalidSoapResponseException.class);
        verifyNoInteractions(persistence);
    }

    @Test
    void storedCrudDelegatesWithoutSoap() {
        var request = new com.rehema.country_service.dto.UpdateCountryRequest(
                "Canada", null, null, null, null, null, List.of());
        var response = new com.rehema.country_service.dto.CountryResponse(
                1L, "CA", "Canada", null, null, null, null, null, List.of());
        when(persistence.findAll()).thenReturn(List.of(response));
        when(persistence.findById(1L)).thenReturn(response);
        when(persistence.update(1L, request)).thenReturn(response);

        assertThat(service.getCountries()).containsExactly(response);
        assertThat(service.getCountry(1L)).isEqualTo(response);
        assertThat(service.updateCountry(1L, request)).isEqualTo(response);
        service.deleteCountry(1L);
        verify(persistence).delete(1L);
        verifyNoInteractions(soap);
    }
}
