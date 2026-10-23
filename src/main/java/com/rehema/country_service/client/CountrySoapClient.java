package com.rehema.country_service.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;

import com.rehema.country_service.exception.InvalidSoapResponseException;
import com.rehema.country_service.exception.SoapServiceException;
import com.rehema.country_service.exception.SoapServiceException.Reason;
import com.rehema.country_service.exception.UnknownCountryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.ws.WebServiceException;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.client.core.SoapActionCallback;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

@Component
public class CountrySoapClient {
    private static final String NAMESPACE = "http://www.oorsprong.org/websamples.countryinfo";
    private static final Logger log = LoggerFactory.getLogger(CountrySoapClient.class);

    private final WebServiceTemplate template;
    private final SoapResilience resilience;

    public CountrySoapClient(WebServiceTemplate template, SoapResilience resilience) {
        this.template = template;
        this.resilience = resilience;
    }

    public String getCountryIsoCode(String countryName) {
        if (countryName == null || countryName.isBlank()) {
            throw new IllegalArgumentException("Country name must not be blank");
        }
        return resilience.execute("CountryISOCode", () -> lookupIsoCode(countryName));
    }

    private String lookupIsoCode(String countryName) {
        Element response = exchange("CountryISOCode", "sCountryName", countryName);
        String code = text(response, "CountryISOCodeResult");
        if ("No country found by that name".equals(code)) {
            throw new UnknownCountryException();
        }
        requireCountryCode(code);
        return code;
    }

    public CountryDetails getFullCountryInfo(String isoCode) {
        if (isoCode == null || !isoCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("Country ISO code must be two uppercase letters");
        }
        return resilience.execute("FullCountryInfo", () -> lookupFullCountry(isoCode));
    }

    private CountryDetails lookupFullCountry(String isoCode) {
        Element response = exchange("FullCountryInfo", "sCountryISOCode", isoCode);
        Element result = child(response, "FullCountryInfoResult");
        String code = text(result, "sISOCode");
        String name = text(result, "sName");
        if (code.isEmpty() && "Country not found in the database".equals(name)) {
            throw new UnknownCountryException();
        }
        requireCountryCode(code);
        if (!isoCode.equals(code)) {
            throw new InvalidSoapResponseException("Response country code does not match request");
        }
        requireNonBlank(name, "sName");
        List<LanguageDetails> languages = new ArrayList<>();
        Element container = child(result, "Languages");
        for (Node node = container.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element language) {
                requireElement(language, "tLanguage");
                // The WSDL permits null items in the language array.
                String nil = language.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil");
                if ("true".equals(nil) || "1".equals(nil)) {
                    continue;
                }
                String languageCode = text(language, "sISOCode");
                String languageName = text(language, "sName");
                requireNonBlank(languageCode, "language sISOCode");
                requireNonBlank(languageName, "language sName");
                languages.add(new LanguageDetails(languageCode.toLowerCase(Locale.ROOT), languageName));
            }
        }
        return new CountryDetails(code, name, text(result, "sCapitalCity"), text(result, "sPhoneCode"),
                text(result, "sContinentCode"), text(result, "sCurrencyISOCode"),
                text(result, "sCountryFlag"), languages);
    }

    private Element exchange(String operation, String parameter, String value) {
        DOMSource request = request(operation, parameter, value);
        DOMResult response = new DOMResult();
        log.atInfo().addKeyValue("operation", operation).log("Calling country SOAP");
        try {
            // Both SOAP 1.1 operations declare soapAction="" in the provider's WSDL.
            boolean received = template.sendSourceAndReceiveToResult(request, new SoapActionCallback(""), response);
            if (!received || !(response.getNode() instanceof Document document)) {
                throw new InvalidSoapResponseException("SOAP response body is missing");
            }
            Element root = document.getDocumentElement();
            requireElement(root, operation + "Response");
            log.debug("Received country SOAP operation={}", operation);
            return root;
        } catch (SoapFaultClientException exception) {
            throw new SoapServiceException(Reason.FAULT, exception);
        } catch (WebServiceIOException exception) {
            Reason reason = isTimeout(exception) ? Reason.TIMEOUT : Reason.UNAVAILABLE;
            throw new SoapServiceException(reason, exception);
        } catch (WebServiceException exception) {
            throw new InvalidSoapResponseException("Could not read SOAP response", exception);
        }
    }

    private DOMSource request(String operation, String parameter, String value) {
        try {
            // Build nodes rather than interpolating input into XML. No XML is parsed here.
            Document document = DocumentBuilderFactory.newNSInstance().newDocumentBuilder().newDocument();
            Element root = document.createElementNS(NAMESPACE, operation);
            Element argument = document.createElementNS(NAMESPACE, parameter);
            argument.setTextContent(value);
            root.appendChild(argument);
            document.appendChild(root);
            return new DOMSource(document);
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException("Could not initialize XML document builder", exception);
        }
    }

    private static Element child(Element parent, String name) {
        Element found = null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())
                    && NAMESPACE.equals(element.getNamespaceURI())) {
                if (found != null) {
                    throw new InvalidSoapResponseException("Duplicate SOAP field: " + name);
                }
                found = element;
            }
        }
        if (found == null) {
            throw new InvalidSoapResponseException("Missing SOAP field: " + name);
        }
        return found;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) {
                throw new InvalidSoapResponseException("Expected text in SOAP field: " + name);
            }
        }
        return element.getTextContent().strip();
    }

    private static void requireElement(Element element, String name) {
        if (element == null || !name.equals(element.getLocalName())
                || !NAMESPACE.equals(element.getNamespaceURI())) {
            throw new InvalidSoapResponseException("Unexpected SOAP element; expected " + name);
        }
    }

    private static void requireCountryCode(String code) {
        if (!code.matches("[A-Z]{2}")) {
            throw new InvalidSoapResponseException("SOAP response contains an invalid country ISO code");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value.isBlank()) {
            throw new InvalidSoapResponseException("Empty SOAP field: " + field);
        }
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
