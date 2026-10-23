# Example requests and responses.

These examples were copied over from the SoapUI WSDL project.

### `CountryISOCode` Request object

```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:web="http://www.oorsprong.org/websamples.countryinfo">
   <soapenv:Header/>
   <soapenv:Body>
      <web:CountryISOCode>
         <web:sCountryName>Netherlands</web:sCountryName>
      </web:CountryISOCode>
   </soapenv:Body>
</soapenv:Envelope>
```

### `CountryISOCode` Success Response object

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
   <soap:Body>
      <m:CountryISOCodeResponse xmlns:m="http://www.oorsprong.org/websamples.countryinfo">
         <m:CountryISOCodeResult>NL</m:CountryISOCodeResult>
      </m:CountryISOCodeResponse>
   </soap:Body>
</soap:Envelope>
```

### `CountryISOCode` Invalid Data Request object

```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:web="http://www.oorsprong.org/websamples.countryinfo">
   <soapenv:Header/>
   <soapenv:Body>
      <web:CountryISOCode>
         <web:sCountryName>The Netherlands</web:sCountryName>
      </web:CountryISOCode>
   </soapenv:Body>
</soapenv:Envelope>
```

### `CountryISOCode` Invalid Data Response object

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
   <soap:Body>
      <m:CountryISOCodeResponse xmlns:m="http://www.oorsprong.org/websamples.countryinfo">
         <m:CountryISOCodeResult>No country found by that name</m:CountryISOCodeResult>
      </m:CountryISOCodeResponse>
   </soap:Body>
</soap:Envelope>
```

### `FullCountryInfo` Request object

```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:web="http://www.oorsprong.org/websamples.countryinfo">
   <soapenv:Header/>
   <soapenv:Body>
      <web:FullCountryInfo>
         <web:sCountryISOCode>NL</web:sCountryISOCode>
      </web:FullCountryInfo>
   </soapenv:Body>
</soapenv:Envelope>
```

### `FullCountryInfo` Response object

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
   <soap:Body>
      <m:FullCountryInfoResponse xmlns:m="http://www.oorsprong.org/websamples.countryinfo">
         <m:FullCountryInfoResult>
            <m:sISOCode>NL</m:sISOCode>
            <m:sName>Netherlands</m:sName>
            <m:sCapitalCity>Amsterdam</m:sCapitalCity>
            <m:sPhoneCode>31</m:sPhoneCode>
            <m:sContinentCode>EU</m:sContinentCode>
            <m:sCurrencyISOCode>EUR</m:sCurrencyISOCode>
            <m:sCountryFlag>http://www.oorsprong.org/WebSamples.CountryInfo/Flags/Netherlands.jpg</m:sCountryFlag>
            <m:Languages>
               <m:tLanguage>
                  <m:sISOCode>fry</m:sISOCode>
                  <m:sName>Frisian</m:sName>
               </m:tLanguage>
               <m:tLanguage>
                  <m:sISOCode>nl</m:sISOCode>
                  <m:sName>Dutch</m:sName>
               </m:tLanguage>
            </m:Languages>
         </m:FullCountryInfoResult>
      </m:FullCountryInfoResponse>
   </soap:Body>
</soap:Envelope>
```

### `FullCountryInfo` Invalid Data Request object

```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:web="http://www.oorsprong.org/websamples.countryinfo">
   <soapenv:Header/>
   <soapenv:Body>
      <web:FullCountryInfo>
         <web:sCountryISOCode>123</web:sCountryISOCode>
      </web:FullCountryInfo>
   </soapenv:Body>
</soapenv:Envelope>
```

### `FullCountryInfo` Invalid Data Response object

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
   <soap:Body>
      <m:FullCountryInfoResponse xmlns:m="http://www.oorsprong.org/websamples.countryinfo">
         <m:FullCountryInfoResult>
            <m:sISOCode/>
            <m:sName>Country not found in the database</m:sName>
            <m:sCapitalCity/>
            <m:sPhoneCode/>
            <m:sContinentCode/>
            <m:sCurrencyISOCode/>
            <m:sCountryFlag/>
            <m:Languages></m:Languages>
         </m:FullCountryInfoResult>
      </m:FullCountryInfoResponse>
   </soap:Body>
</soap:Envelope>
```
