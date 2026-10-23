package com.rehema.country_service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.webservices.client.WebServiceMessageSenderFactory;
import org.springframework.boot.webservices.client.WebServiceTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ws.client.core.WebServiceTemplate;

@Configuration(proxyBeanMethods = false)
public class SoapConfig {

    @Bean
    public WebServiceTemplate countryWebServiceTemplate(WebServiceTemplateBuilder builder,
            @Value("${country.soap.endpoint}") String endpoint,
            @Value("${country.soap.connect-timeout}") Duration connectTimeout,
            @Value("${country.soap.read-timeout}") Duration readTimeout) {
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("SOAP timeouts must be positive");
        }
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(connectTimeout).withReadTimeout(readTimeout);
        return builder.setDefaultUri(endpoint)
                .httpMessageSenderFactory(WebServiceMessageSenderFactory.http(settings)).build();
    }
}
