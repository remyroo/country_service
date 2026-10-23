package com.rehema.country_service.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.ws.test.client.RequestMatchers.*;
import static org.springframework.ws.test.client.ResponseCreators.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.rehema.country_service.exception.InvalidSoapResponseException;
import com.rehema.country_service.exception.SoapServiceException;
import com.rehema.country_service.exception.SoapServiceException.Reason;
import com.rehema.country_service.exception.UnknownCountryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.xml.transform.StringSource;

class CountrySoapClientTests {
    private static final String NAMESPACE = "http://www.oorsprong.org/websamples.countryinfo";
    private static final String ENDPOINT = "http://example.test/country-service";
    private CountrySoapClient client;
    private MockWebServiceServer server;

    @BeforeEach
    void setUp() {
        WebServiceTemplate template = new WebServiceTemplate();
        template.setDefaultUri(ENDPOINT);
        server = MockWebServiceServer.createServer(template);
        client = new CountrySoapClient(template, new SoapResilience(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("test"),
                io.github.resilience4j.retry.RetryConfig.custom().maxAttempts(1).build(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void sendsIsoRequestWithCorrectNamespaceEndpointAndEmptySoapAction() throws IOException {
        server.expect(soapEnvelope(resource("iso-request")))
                .andExpect(connectionTo(ENDPOINT))
                .andExpect((uri, message) -> assertThat(((SoapMessage) message).getSoapAction()).isEqualTo("\"\""))
                .andRespond(withSoapEnvelope(resource("iso-success")));

        assertThat(client.getCountryIsoCode("Netherlands")).isEqualTo("NL");
    }

    @Test
    void sendsFullRequestAndMapsEveryFieldAndMultipleLanguages() throws IOException {
        server.expect(soapEnvelope(resource("full-request")))
                .andExpect((uri, message) -> assertThat(((SoapMessage) message).getSoapAction()).isEqualTo("\"\""))
                .andRespond(withSoapEnvelope(resource("full-success")));

        CountryDetails details = client.getFullCountryInfo("NL");

        assertThat(details).isEqualTo(new CountryDetails("NL", "Netherlands", "Amsterdam", "31", "EU", "EUR",
                "http://www.oorsprong.org/WebSamples.CountryInfo/Flags/Netherlands.jpg",
                java.util.List.of(new LanguageDetails("fry", "Frisian"), new LanguageDetails("nl", "Dutch"))));
        assertThatThrownBy(() -> details.languages().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void escapesInputAsTextRatherThanXml() throws IOException {
        String name = "A & B <country>\"'";
        server.expect(xpath("/c:CountryISOCode/c:sCountryName", Map.of("c", NAMESPACE)).evaluatesTo(name))
                .andRespond(withSoapEnvelope(resource("iso-success")));

        assertThat(client.getCountryIsoCode(name)).isEqualTo("NL");
    }

    @Test
    void acceptsDifferentNamespacePrefixes() throws IOException {
        String response = xml("full-success").replace("m:", "country:").replace("xmlns:m=", "xmlns:country=");
        server.expect(anything()).andRespond(withSoapEnvelope(new StringSource(response)));

        assertThat(client.getFullCountryInfo("NL").languages()).hasSize(2);
    }

    @Test
    void acceptsEmptyLanguagesAndEmptyOptionalValues() throws IOException {
        String response = xml("full-success").replaceAll("(?s)<m:Languages>.*?</m:Languages>", "<m:Languages/>")
                .replace("Amsterdam", "");
        server.expect(anything()).andRespond(withSoapEnvelope(new StringSource(response)));

        CountryDetails details = client.getFullCountryInfo("NL");
        assertThat(details.languages()).isEmpty();
        assertThat(details.capital()).isEmpty();
    }

    @Test
    void skipsNullLanguageItemsAllowedByWsdl() throws IOException {
        String response = xml("full-success").replace("<m:Languages>",
                "<m:Languages><m:tLanguage xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:nil=\"true\"/>");
        server.expect(anything()).andRespond(withSoapEnvelope(new StringSource(response)));
        assertThat(client.getFullCountryInfo("NL").languages()).hasSize(2);
    }

    @Test
    void recognizesUnknownCountryFromIsoLookup() throws IOException {
        server.expect(soapEnvelope(resource("iso-unknown-request")))
                .andRespond(withSoapEnvelope(resource("iso-unknown")));
        assertThatThrownBy(() -> client.getCountryIsoCode("The Netherlands"))
                .isInstanceOf(UnknownCountryException.class);
    }

    @Test
    void recognizesUnknownCountryFromFullLookup() throws IOException {
        server.expect(anything()).andRespond(withSoapEnvelope(resource("full-unknown")));
        assertThatThrownBy(() -> client.getFullCountryInfo("ZZ"))
                .isInstanceOf(UnknownCountryException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidIsoResponses")
    void rejectsInvalidIsoResponses(String response) {
        server.expect(anything()).andRespond(withSoapEnvelope(new StringSource(response)));
        assertThatThrownBy(() -> client.getCountryIsoCode("Netherlands"))
                .isInstanceOf(InvalidSoapResponseException.class);
    }

    static Stream<String> invalidIsoResponses() throws IOException {
        String valid = xml("iso-success");
        return Stream.of(
                valid.replace(NAMESPACE, "urn:wrong-namespace"),
                valid.replace("<m:CountryISOCodeResult>NL</m:CountryISOCodeResult>", ""),
                valid.replace(">NL<", "><"),
                valid.replace(">NL<", ">Unexpected error<"),
                valid.replace("CountryISOCodeResponse", "WrongOperationResponse"),
                valid.replace("</m:CountryISOCodeResult>", "</m:CountryISOCodeResult><m:CountryISOCodeResult>FR</m:CountryISOCodeResult>"));
    }

    @ParameterizedTest
    @MethodSource("invalidFullResponses")
    void rejectsIncompleteOrInconsistentCountryData(String response) {
        server.expect(anything()).andRespond(withSoapEnvelope(new StringSource(response)));
        assertThatThrownBy(() -> client.getFullCountryInfo("NL"))
                .isInstanceOf(InvalidSoapResponseException.class);
    }

    static Stream<String> invalidFullResponses() throws IOException {
        String valid = xml("full-success");
        return Stream.of(
                valid.replaceAll("(?s)<m:FullCountryInfoResult>.*?</m:FullCountryInfoResult>", ""),
                valid.replace("<m:sISOCode>NL</m:sISOCode>", "<m:sISOCode/>"),
                valid.replace(">NL<", ">CA<"),
                valid.replace("<m:sName>Netherlands</m:sName>", ""),
                valid.replace("<m:sName>Netherlands</m:sName>", "<m:sName/>"),
                valid.replace("<m:sISOCode>fry</m:sISOCode>", "<m:sISOCode/>"),
                valid.replace("<m:sName>Dutch</m:sName>", "<m:sName/>"),
                valid.replace("<m:sCapitalCity>Amsterdam</m:sCapitalCity>", "<sCapitalCity>Amsterdam</sCapitalCity>"));
    }

    @Test
    void rejectsMalformedRawSoapXml() {
        server.expect(anything()).andRespond((uri, request, factory) -> factory.createWebServiceMessage(
                new ByteArrayInputStream("<broken".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> client.getCountryIsoCode("Netherlands"))
                .isInstanceOf(InvalidSoapResponseException.class);
    }

    @Test
    void distinguishesSoapFaultWithoutExposingFaultText() {
        server.expect(anything()).andRespond(withServerOrReceiverFault("Internal implementation detail", Locale.ENGLISH));
        assertThatThrownBy(() -> client.getCountryIsoCode("Netherlands"))
                .isInstanceOfSatisfying(SoapServiceException.class, exception -> {
                    assertThat(exception.getReason()).isEqualTo(Reason.FAULT);
                    assertThat(exception.getMessage()).doesNotContain("Internal implementation detail");
                });
    }

    @Test
    void distinguishesUnavailableService() {
        server.expect(anything()).andRespond(withException(new IOException("Connection refused")));
        assertThatThrownBy(() -> client.getCountryIsoCode("Netherlands"))
                .isInstanceOfSatisfying(SoapServiceException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(Reason.UNAVAILABLE));
    }

    @ParameterizedTest
    @MethodSource("timeoutExceptions")
    void distinguishesTimeouts(IOException failure) {
        server.expect(anything()).andRespond(withException(failure));
        assertThatThrownBy(() -> client.getFullCountryInfo("NL"))
                .isInstanceOfSatisfying(SoapServiceException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(Reason.TIMEOUT));
    }

    static Stream<IOException> timeoutExceptions() {
        return Stream.of(new SocketTimeoutException("Read timed out"), new HttpTimeoutException("Timed out"));
    }

    @Test
    void rejectsInvalidInputBeforeSendingRequests() {
        assertThatThrownBy(() -> client.getCountryIsoCode(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getCountryIsoCode(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getFullCountryInfo("123")).isInstanceOf(IllegalArgumentException.class);
    }

    private static ClassPathResource resource(String name) {
        return new ClassPathResource("soap/" + name + ".xml");
    }

    private static String xml(String name) throws IOException {
        return resource(name).getContentAsString(StandardCharsets.UTF_8);
    }
}
