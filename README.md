# Country service

A Java 21 / Spring Boot application that will look up country information through
SOAP, store it in MySQL, and expose REST CRUD operations.

## Run locally

Prerequisites: JDK 21 and Docker with Docker Compose.

Copy `.env.example` to `.env` and replace the password placeholders.

```sh
cp .env.example .env
```

Compose reads `.env` automatically. Spring Boot does not, so export the values
into your shell before running Maven:

```sh
docker compose up -d mysql
docker compose ps
set -a
. ./.env
set +a
./mvnw spring-boot:run
```

Hibernate (the JPA implementation) creates/updates the tables on startup because
`spring.jpa.hibernate.ddl-auto=update`. This is convenient for the assessment but
production schema changes would need controlled migrations.

## Run with Docker Compose

Only Docker with Compose is required for this path. Configure `.env` as described
above. Compose reads it automatically, so no shell export is needed.

```sh
docker compose build app
docker compose up -d --wait
docker compose ps
curl http://localhost:8080/actuator/health
```

For API verification, use the creation and CRUD examples below with a country not
already stored. Confirm POST returns 201, repeat POST returns 409, GET returns the
saved data, PUT changes fields/languages, and DELETE returns 204 followed by 404 on
GET. A blank name should return 400. Leave pre-existing records untouched.

Troubleshooting:

- **Port already allocated:** stop the host Java process or conflicting container;
  check `docker compose ps` before retrying.
- **MySQL access denied:** `.env` must match the credentials used to initialize the
  existing volume; changing environment variables does not rotate stored passwords.
- **App unhealthy:** inspect app and MySQL logs and health; confirm the container's
  database URL uses `mysql`, not `localhost`.
- **Image download/build fails:** check Docker's network/proxy access to Docker Hub,
  Maven Central, and Ubuntu package repositories. The first build downloads Java
  images and dependencies and can take several minutes.
- **POST returns 503/504 while GET works:** inspect SOAP metrics and correlated logs;
  Docker needs outbound DNS/network access to the public SOAP service. This does
  not imply that MySQL or the image is broken.

For the application replicas, persistent MySQL, probes, and deployment/troubleshooting commands, see [this project's Kubernetes guide](k8s/README.md).

## Create a country

With MySQL healthy and the application running:

```sh
curl -i http://localhost:8080/countries \
  -H 'Content-Type: application/json' \
  -d '{"name":"  kENYA  "}'
```

The flow is: validate the name, trim and convert to sentence case (`Kenya`), call
`CountryISOCode`, call `FullCountryInfo`, map the data, save the country with shared
languages, then return `201 Created`.

The response includes the generated country and language IDs and a `Location: /countries/{id}` header.
A GET request to that location retrieves the stored record.

Repeating a successful creation returns `409 Conflict` and does not update the stored country.

Example response:

```json
{
  "id": 1,
  "isoCode": "KE",
  "name": "Kenya",
  "capital": "Nairobi",
  "phoneCode": "254",
  "continentCode": "AF",
  "currencyCode": "KES",
  "flagUrl": "http://www.oorsprong.org/WebSamples.CountryInfo/Flags/Kenya.jpg",
  "languages": [{ "id": 1, "isoCode": "swa", "name": "Swahili" }]
}
```

- `CountryController` handles HTTP and `@Valid` request validation.
- `CountryService` coordinates the SOAP calls and mapping.
- `CountryPersistenceService` owns the database transaction. SOAP failures therefore cannot create partial database rows. Sentence case is literal and locale-independent: `UNITED STATES` becomes
  `United states`. Names must be nonblank and at most 255 characters.

| Failure                                       | HTTP status |
| --------------------------------------------- | ----------- |
| Missing/blank/overlong name or malformed JSON | 400         |
| Unknown country                               | 404         |
| Country already stored                        | 409         |
| Invalid upstream response or SOAP fault       | 502         |
| SOAP unavailable or circuit open              | 503         |
| SOAP timeout                                  | 504         |
| Other database or unexpected failure          | 500         |

Only the existing country ISO unique index is translated to a duplicate-country
error, other integrity violations remain server errors. Database/stack-trace details are logged, not returned to callers.

To verify validation without making a SOAP call:

```sh
curl -i http://localhost:8080/countries \
  -H 'Content-Type: application/json' -d '{"name":" "}'
```

### Run service and controller tests

Run just the service and controller tests without MySQL or public-service access:

```sh
./mvnw -Dtest=CountryServiceTests,CountryControllerTests test
```

Service tests mock SOAP and persistence to check normalization, ordering, mapping,
and early termination on failure. MockMvc tests send simulated HTTP requests and
verify validation, status codes, the Location header, and safe response bodies.

## Stored-data CRUD

These endpoints never call SOAP, so stored data remains accessible during a SOAP outage:

| Endpoint                 | Success                              | Missing record |
| ------------------------ | ------------------------------------ | -------------- |
| `GET /countries`         | 200 - JSON array, or `[]` when empty | Not applicable |
| `GET /countries/{id}`    | 200 - country and languages          | 404            |
| `PUT /countries/{id}`    | 200 - updated country                | 404            |
| `DELETE /countries/{id}` | 204 - empty body                     | 404            |

### Example querires to test functionality

```sh
curl -i http://localhost:8080/countries
curl -i http://localhost:8080/countries/52
curl -i -X PUT http://localhost:8080/countries/52 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Kenya","capital":"Nairobi","phoneCode":"254","continentCode":"AF","currencyCode":"KES","flagUrl":null,"languages":[{"isoCode":"swa","name":"Swahili"}]}'
# Replace 52 with a disposable record ID before deleting:
curl -i -X DELETE http://localhost:8080/countries/52
```

- For PUT requests, a nonblank name and a non-null language list are required. Omitted/null descriptive fields are cleared. `"languages":[]` removes all associations.

- Language codes are normalized and reused. Repeated codes produce one association. Replacing a country's languages or deleting the country preserves language rows and other countries' associations.

- `CountryPersistenceService` loads and maps read results inside read-only
  transactions. A fetch join loads the country list and languages together with results ordered by country ID. A failed update rolls back fields, associations, and newly created languages.

- Tests cover missing IDs, validation, replacement and clearing of languages, safe
  deletion, rollback, and explicitly verify no SOAP calls from stored-data CRUD.

## SOAP client

`CountrySoapClient` provides two independent methods:

1. `getCountryIsoCode(countryName)` sends `CountryISOCode` with `sCountryName` and
   returns a validated two-letter country code.
2. `getFullCountryInfo(isoCode)` sends `FullCountryInfo` with `sCountryISOCode` and
   returns country fields and a list of languages.

`CountryService` normalizes the country name, calls these operations in sequence,
and passes the mapped details into persistence.

`SoapConfig` registers a `WebServiceTemplate` bean (an object managed by Spring). This uses existing dependencies, no generated WSDL classes or additional XML mapping library are needed for these two operations. See [Spring's SOAP client configuration](https://docs.spring.io/spring-boot/reference/io/webservices.html).

| Environment variable           | Default                                                                          |
| ------------------------------ | -------------------------------------------------------------------------------- |
| `COUNTRY_SOAP_ENDPOINT`        | `http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso` |
| `COUNTRY_SOAP_CONNECT_TIMEOUT` | `2s`                                                                             |
| `COUNTRY_SOAP_READ_TIMEOUT`    | `5s`                                                                             |

The client validates required elements, country codes, country/language names,
and that the returned country code matches the request. Response fixtures
come from `docs/example-responses.md`.

Failures are represented by exceptions:

- `UnknownCountryException`: either documented country-not-found response.
- `InvalidSoapResponseException`: malformed XML, wrong namespaces, missing or
  duplicate required fields, or inconsistent data.
- `SoapServiceException`: distinguishes `UNAVAILABLE`, `TIMEOUT`, and `FAULT`.

### Run SOAP tests

Run just the SOAP tests without MySQL or public-service access:

```sh
./mvnw -Dtest=CountrySoapClientTests test
```

Tests cover mapping, multiple/empty languages, alternate prefixes, XML escaping, unknown countries,
malformed responses, faults, and simulated transport/timeouts.

## Resilience and observability

Resilience4j's retry and circuit-breaker core modules provide the state management.
`SoapResilienceConfig` defines the policies and `SoapResilience.execute` applies them
around each complete SOAP operation, including response mapping. We use explicit
Java calls, not annotation interception or Spring Cloud. See the official
[retry](https://resilience4j.readme.io/docs/retry) and
[circuit-breaker](https://resilience4j.readme.io/docs/circuitbreaker) documentation.

- Only unavailability and timeouts are retried: one initial attempt plus one retry.
- Unknown countries, invalid responses, and SOAP faults are neither retried nor
  included in the circuit's failure-rate calculation.
- Each SOAP operation retries independently. A full-country retry never repeats
  the ISO lookup. Database operations are outside this policy.
- One circuit is shared by both SOAP operations. It counts final operation outcomes
  after retry exhaustion, not individual network attempts. Once open, new
  operations fail immediately without retrying or sending a request.
- After the open interval, the next request may make one recovery probe. Success
  closes the circuit; another transient failure reopens it. The probe itself uses
  the same bounded retry policy. No background polling is required.
- Fallback means a safe error: unavailable/open circuit returns 503; exhausted
  timeouts return 504. No fabricated or stale country data is returned. Stored-data
  CRUD remains usable, and SOAP failure prevents persistence.

| Property                             | Default                                         |
| ------------------------------------ | ----------------------------------------------- |
| `country.soap.retry.max-attempts`    | 2, including initial attempt; allowed range 1–3 |
| `country.soap.retry.delay`           | 200ms                                           |
| `country.soap.circuit.window-size`   | 10 final operations                             |
| `country.soap.circuit.minimum-calls` | 4 recorded operations                           |
| `country.soap.circuit.failure-rate`  | 50 percent                                      |
| `country.soap.circuit.open-duration` | 10s                                             |

### Actuator

Actuator exposes `/actuator/health` and `/actuator/metrics`:

```sh
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics/country.soap.operation
curl http://localhost:8080/actuator/metrics/country.soap.attempts
curl http://localhost:8080/actuator/metrics/country.soap.retries
curl http://localhost:8080/actuator/metrics/country.soap.rejected
curl 'http://localhost:8080/actuator/metrics/country.soap.circuit.state?tag=state:OPEN'
curl http://localhost:8080/actuator/metrics/http.server.requests
```

For a local failure drill, export `.env` as above and start with
`COUNTRY_SOAP_ENDPOINT=http://127.0.0.1:1 ./mvnw spring-boot:run`. Send five POST
requests within ten seconds. With the defaults, expect eight outbound attempts,
four retries, then one circuit rejection, with all responses 503. GET should still
work and no country should be created. Inspect metrics and correlate the response
header with the JSON logs. Restart with the normal endpoint afterward.

Actuator metrics are exposed without authentication in this local assessment.
Restrict their network exposure or add authentication for deployment, do not expose
all Actuator endpoints publicly.

Focused resilience tests cover retry recovery/exhaustion, non-transient exclusions, open-circuit rejection,
recovery, and metric updates. Existing tests cover timeout classification, HTTP fallback responses, SOAP failures preventing persistence, and CRUD independence.

## Persistence design

- `country_info` stores country fields. Its ISO code is unique and immutable.
- `language` is a shared catalog with a unique, immutable ISO code. Different
  codes are treated as different languages.
- `country_language` links countries to languages, with a unique constraint on
  `(country_id, language_id)`. Canada and France can share the same French row,
  while Canada can also link to English.
- `CountryInfo.addLanguage` prevents duplicate associations by language code.
  Removing a language or deleting a country deletes associations only. Shared
  language rows remain, including languages no longer linked to any country.
- The relationship is a unidirectional JPA `@ManyToMany`: countries expose their
  languages, and `Language` has no country field. There is no cascading persistence
  or deletion of languages - they are saved explicitly through `LanguageRepository`.

`CountryPersistenceService.create` accepts a new country containing already-fetched
details. In one short transaction it looks up each language by normalized code,
inserts missing languages, and saves the country and associations.

## Deployment and troubleshooting

See the [Kubernetes guide](k8s/README.md) for deployment and troubleshooting.
