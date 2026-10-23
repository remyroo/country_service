# Country service

A Java 21 / Spring Boot application that will look up country information through
SOAP, store it in MySQL, and expose REST CRUD operations.

Currently implemented: the `CountryInfo` and `Language` entities, their repositories,
`CountryPersistenceService` for saving a country with shared languages, a tested
SOAP client, and REST creation, read, update, and delete endpoints. POST fetches
SOAP data; GET, PUT, and DELETE operate only on stored MySQL data.

## Run locally

Prerequisites: JDK 21 and Docker with Docker Compose. Maven is supplied by `mvnw`.

For a new checkout, copy `.env.example` to `.env` and replace the password
placeholders. Skip the copy if you already have a configured `.env`.

```sh
cp .env.example .env
chmod 600 .env
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

The application defaults to MySQL at `localhost:3306/country_db`; `DB_URL` can
override that URL. `DB_USERNAME` and `DB_PASSWORD` have no committed defaults.
Compose uses those same credentials and requires `MYSQL_ROOT_PASSWORD` for MySQL
initialization. The health check reads credentials inside the container (`$$`
defers variable expansion until then) and verifies a query as the application user.

Keep `application.properties`, `compose.yml`, and `.env.example` in Git. `.env`
and other `.env.*` files are ignored, apart from `.env.example`. Do not put real
secrets in the example file. For IDE launches/tests, set `DB_USERNAME` and
`DB_PASSWORD` (and optionally `DB_URL`) in the run configuration's environment.
Source only your own trusted `.env` file: the shell executes its contents.

For an existing MySQL volume, use its existing credentials in `.env`. Changing
initialization variables does not change passwords already stored in MySQL.
The local cleanup preserved the existing credentials in the ignored `.env`;
it did not reset the database or change its users' passwords.

Hibernate (the JPA implementation) creates/updates the tables on startup because
`spring.jpa.hibernate.ddl-auto=update`. This is convenient for the assessment;
production schema changes would need controlled migrations.

## Run with Docker Compose

Only Docker with Compose is required for this path. Configure `.env` as described
above; Compose reads it automatically, so no shell export is needed.

```sh
docker compose build app
docker compose up -d --wait
docker compose ps
curl http://localhost:8080/actuator/health
```

Alternatively, build the image directly with `docker build -t country-service:local .`.
The multi-stage Dockerfile compiles with a Java 21 JDK and the Maven wrapper, then
copies only the executable JAR into a Java 21 JRE image. The runtime runs as
non-root UID/GID 10001. Curl is installed for the Compose health check. No database
credentials are baked into the image; `.dockerignore` excludes `.env` files, Git,
local build outputs, and IDE files from the build context.

Run `./mvnw test` with local environment variables loaded before building (see
verification instructions below). Image builds skip test execution because the
integration tests require a running database. The builder still compiles the tests.
Base images use Java 21 Ubuntu Jammy tags; use `docker compose build --pull app` to
refresh them. Pin image digests in a production release for reproducible builds.

The app connects to `mysql:3306`, the Compose service hostname. `localhost` inside
the app container would refer to the app itself. MySQL retains the existing
`mysql_data` volume. Compose waits for MySQL's SQL health check before starting the
app; the app becomes healthy when Actuator reports health (including database
connectivity). This is startup coordination, not automatic recovery orchestration.

The app's host port binds to `127.0.0.1:8080` for local use, including the currently
unauthenticated Actuator metrics. Stop any host-run Spring application using 8080
before starting the container. MySQL keeps its existing host port for local Java
development. To return to that workflow:

```sh
docker compose stop app
docker compose up -d mysql
# Load .env into your shell, then run ./mvnw spring-boot:run as above.
```

Useful checks and lifecycle commands:

```sh
docker compose logs --tail=50 app
docker compose logs --tail=50 mysql
curl http://localhost:8080/actuator/metrics/http.server.requests
docker compose exec app id
docker compose restart app
docker compose up -d --wait
# Stop/remove containers and network, preserving the database volume:
docker compose down
```

`docker compose down -v` also deletes the database volume and its data; do not use
it to fix routine startup errors. Rebuilding or restarting the app does not delete
MySQL records. After code changes, use `docker compose up -d --build --wait`.

For API verification, use the creation and CRUD examples below with a country not
already stored. Confirm POST returns 201, repeat POST returns 409, GET returns the
saved data, PUT changes fields/languages, and DELETE returns 204 followed by 404 on
GET. Restart the app before deleting that record and verify it still exists. A blank
name should return 400. Leave pre-existing records untouched.

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

For two application replicas, persistent MySQL, probes, and deployment/troubleshooting commands, see [the Kubernetes guide](k8s/README.md).

## Create a country

With MySQL healthy and the application running:

```sh
curl -i http://localhost:8080/countries \
  -H 'Content-Type: application/json' \
  -d '{"name":"  kENYA  "}'
```

The flow is: validate the name, trim and convert to sentence case (`Kenya`), call
`CountryISOCode`, call `FullCountryInfo`, map the data, save the country with shared
languages, then return `201 Created`. The response includes the generated country
and language IDs and a `Location: /countries/{id}` header. GET at that location retrieves the stored record. Repeating a successful creation
returns `409 Conflict`; it does not update the stored country.

Example response (IDs and language data depend on the database and SOAP response):

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
  "languages": [{"id": 1, "isoCode": "swa", "name": "Swahili"}]
}
```

`CountryController` handles HTTP and `@Valid` request validation. `CountryService`
coordinates the SOAP calls and mapping; `CountryPersistenceService` owns the short
database transaction. SOAP failures therefore cannot create partial database rows.
Sentence case is literal and locale-independent: `UNITED STATES` becomes
`United states`. Names must be nonblank and at most 255 characters.

Response DTOs are Java records rather than database entities. Open EntityManager
in View is disabled, so serializing a response cannot cause hidden database reads.
The creation path maps the already-loaded saved country and languages into DTOs.

Errors use Spring's `ProblemDetail` JSON format with `status`, `title`, and `detail`:

| Failure | HTTP status |
| --- | --- |
| Missing/blank/overlong name or malformed JSON | 400 |
| Unknown country | 404 |
| Country already stored | 409 |
| Invalid upstream response or SOAP fault | 502 |
| SOAP unavailable or circuit open | 503 |
| SOAP timeout | 504 |
| Other database or unexpected failure | 500 |

Only the existing country ISO unique index is translated to a duplicate-country
error; other integrity violations remain server errors. The persistence code
matches Hibernate's current generated index name, verified by a MySQL integration
test. If that index is renamed in a future schema change, update the matching
constant as well. Database/stack-trace details are logged, not returned to callers.

To verify validation without making a SOAP call:

```sh
curl -i http://localhost:8080/countries \
  -H 'Content-Type: application/json' -d '{"name":" "}'
```

Run just the service and controller tests without MySQL or public-service access:

```sh
./mvnw -Dtest=CountryServiceTests,CountryControllerTests test
```

Service tests mock SOAP and persistence to check normalization, ordering, mapping,
and early termination on failure. MockMvc tests send simulated HTTP requests and
verify validation, status codes, the Location header, and safe response bodies.

## Stored-data CRUD

These endpoints never call SOAP, so stored data remains accessible during a SOAP outage:

| Endpoint | Success | Missing record |
| --- | --- | --- |
| `GET /countries` | 200; JSON array, or `[]` when empty | Not applicable |
| `GET /countries/{id}` | 200; country and languages | 404 |
| `PUT /countries/{id}` | 200; updated country | 404 |
| `DELETE /countries/{id}` | 204; empty body | 404 |

```sh
curl -i http://localhost:8080/countries
curl -i http://localhost:8080/countries/52
curl -i -X PUT http://localhost:8080/countries/52 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Kenya","capital":"Nairobi","phoneCode":"254","continentCode":"AF","currencyCode":"KES","flagUrl":null,"languages":[{"isoCode":"swa","name":"Swahili"}]}'
# Replace 52 with a disposable record ID before deleting:
curl -i -X DELETE http://localhost:8080/countries/52
```

PUT replaces editable fields, not the country's ID or ISO code. A nonblank name
and a non-null language list are required. Omitted/null descriptive fields are
cleared; `"languages":[]` removes all associations. Country names are trimmed on
update, preserving the caller's capitalization. Descriptive fields and language
codes/names have a maximum of 255 characters; flag URLs allow 2048 characters.
Nested language entries must have nonblank codes and names. Invalid requests and
non-numeric/out-of-range ID formats return 400; nonexistent numeric IDs return 404.

Language codes are normalized and reused. Repeated codes produce one association;
existing catalog names win over supplied names. Replacing a country's languages
or deleting the country preserves language rows and other countries' associations.
Unused languages remain in the catalog. Response languages are sorted by code.

`CountryPersistenceService` loads and maps read results inside read-only
transactions. A fetch join loads the country list and languages together; results
are ordered by country ID. Update and delete run in short write transactions.
A failed update rolls back fields, associations, and newly created languages.
The shared response mapper keeps POST/GET/PUT representations consistent.

The list endpoint is deliberately unpaginated for this small assessment. There is
no version-based concurrent-edit detection; simultaneous updates are not reported
as version conflicts. Pagination and optimistic locking can be added if needed.

Tests cover missing IDs, validation, replacement and clearing of languages, safe
deletion, rollback, and explicitly verify no SOAP calls from stored-data CRUD.
Database integration tests create dedicated records and clean up their own data.

## SOAP client

`CountrySoapClient` provides two independent methods:

1. `getCountryIsoCode(countryName)` sends `CountryISOCode` with `sCountryName` and
   returns a validated two-letter country code.
2. `getFullCountryInfo(isoCode)` sends `FullCountryInfo` with `sCountryISOCode` and
   returns country fields and a list of languages.

`CountryDetails` and `LanguageDetails` are Java records: small immutable data
carriers separate from the JPA entities. The country record also copies its list
to prevent callers from modifying its languages. The client never accesses MySQL.
`CountryService` normalizes the country name, calls these operations in sequence,
and passes the mapped details into persistence.

`SoapConfig` registers a `WebServiceTemplate` bean (an object managed by Spring)
using Boot's builder and configurable connection/read timeouts. The client receives
it through its constructor. This uses existing dependencies; no generated WSDL
classes or additional XML mapping library are needed for these two operations.
See [Spring's SOAP client configuration](https://docs.spring.io/spring-boot/reference/io/webservices.html).

| Environment variable | Default |
| --- | --- |
| `COUNTRY_SOAP_ENDPOINT` | `http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso` |
| `COUNTRY_SOAP_CONNECT_TIMEOUT` | `2s` |
| `COUNTRY_SOAP_READ_TIMEOUT` | `5s` |

The endpoint is the service URL, not the `?WSDL` URL. The provider's SOAP 1.1 binding
declares an empty SOAP action for both operations, so the client sends `SOAPAction:
""`. XML request values are built as text nodes, not concatenated into markup.
Response matching uses namespace URI and local element name, not a particular
prefix. The contract was verified against the provider's WSDL; response fixtures
come from `docs/example-responses.md`.

The client validates required elements, country codes, country/language names,
and that the returned country code matches the request. Optional descriptive
values may be empty. Empty language lists and WSDL-permitted null language items
are supported. Language codes are trimmed and lowercased, without translating
between two- and three-letter aliases.

Failures are represented by exceptions, ready for future REST error mapping:

- `UnknownCountryException`: either documented country-not-found response.
- `InvalidSoapResponseException`: malformed XML, wrong namespaces, missing or
  duplicate required fields, or inconsistent data.
- `SoapServiceException`: distinguishes `UNAVAILABLE`, `TIMEOUT`, and `FAULT`.
  Raw upstream fault text is retained only in the cause, not the public message.

SOAP operations now use bounded retries and a shared circuit breaker, described below.
SOAP faults remain distinct from transient transport failures and are not retried.

Run just the SOAP tests without MySQL or public-service access:

```sh
./mvnw -Dtest=CountrySoapClientTests test
```

Spring-WS `MockWebServiceServer` checks outgoing envelopes, parameters, namespaces,
the endpoint and SOAP action, then supplies local XML fixtures. Tests cover mapping,
multiple/empty languages, alternate prefixes, XML escaping, unknown countries,
malformed responses, faults, and simulated transport/timeouts. Simulating a timeout
tests exception handling; it does not measure real network timeout timing.

## Resilience and observability

Resilience4j's retry and circuit-breaker core modules provide the state management.
`SoapResilienceConfig` defines the policies; `SoapResilience.execute` applies them
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

| Property | Default |
| --- | --- |
| `country.soap.retry.max-attempts` | 2, including initial attempt; allowed range 1–3 |
| `country.soap.retry.delay` | 200ms |
| `country.soap.circuit.window-size` | 10 final operations |
| `country.soap.circuit.minimum-calls` | 4 recorded operations |
| `country.soap.circuit.failure-rate` | 50 percent |
| `country.soap.circuit.open-duration` | 10s |

These properties can be overridden externally (for example,
`COUNTRY_SOAP_CIRCUIT_OPEN_DURATION=30s`). Existing transport timeouts stay at 2s
for connection and 5s for reading. If both operations each take two attempts, the
configured connect/read waits and retry delays can total roughly 28.4 seconds.
This is not a strict end-to-end deadline; transport/DNS behavior can affect elapsed
time. Retries block the request thread, intentionally keeping this small service
synchronous. Circuit state and metrics are per application instance, reset at
restart, and do not require shared infrastructure across replicas.

Console logs use Spring Boot's built-in Logstash JSON format. Every HTTP request
gets a generated `X-Request-ID` response header; the same `requestId` is attached to
its logs through MDC (a thread-local logging context), then cleared/restored when
the request finishes. The ID is for correlation only. SOAP records include
`operation`, `outcome`, retry number, or circuit transition. Request completion
records include method, status, and duration. No request bodies, passwords, or
country names are added as metric labels.

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

The operation timer reports count and duration, tagged by operation and outcome.
Counters track actual attempts, scheduled retries, and circuit-rejected operations.
The circuit gauge is 1 for the active state and 0 for other states. Counters/timers
appear after their first event. Labels have bounded values; request IDs never
become metric tags. Health remains independent of SOAP availability so an upstream
outage does not mark stored CRUD unhealthy. Database health is still checked.

For a local failure drill, export `.env` as above and start with
`COUNTRY_SOAP_ENDPOINT=http://127.0.0.1:1 ./mvnw spring-boot:run`. Send five POST
requests within ten seconds. With the defaults, expect eight outbound attempts,
four retries, then one circuit rejection, with all responses 503. GET should still
work and no country should be created. Inspect metrics and correlate the response
header with the JSON logs. Restart with the normal endpoint afterward.

Actuator metrics are exposed without authentication in this local assessment.
Restrict their network exposure or add authentication for deployment; do not expose
all Actuator endpoints publicly. Production log collection and a Micrometer
Prometheus registry/exporter could be added later; neither is installed here.

Focused resilience tests use zero retry delays and explicit circuit transitions to
cover retry recovery/exhaustion, non-transient exclusions, open-circuit rejection,
recovery, and metric updates. Existing tests cover timeout classification, HTTP
fallback responses, SOAP failures preventing persistence, and CRUD independence.

## Persistence design

- `country_info` stores country fields; its ISO code is unique and immutable.
- `language` is a shared catalog with a unique, immutable ISO code. Codes are trimmed
  and lowercased; both two- and three-letter SOAP codes are supported. Different
  codes are treated as different languages; no alias conversion is attempted.
- `country_language` links countries to languages, with a unique constraint on
  `(country_id, language_id)`. Canada and France can share the same French row,
  while Canada can also link to English.
- `CountryInfo.addLanguage` prevents duplicate associations by language code.
  Removing a language or deleting a country deletes associations only. Shared
  language rows remain, including languages no longer linked to any country.
- The relationship is a unidirectional JPA `@ManyToMany`: countries expose their
  languages, and `Language` has no country field. There is no cascading persistence
  or deletion of languages; they are saved explicitly through `LanguageRepository`.
- `CountryRepository` extends `JpaRepository`, so Spring supplies standard save,
  lookup, and delete operations without a handwritten implementation.
- Language collections load lazily. Future service methods should access/map them
  inside a database transaction before returning response DTOs.

`CountryPersistenceService.create` accepts a new country containing already-fetched
details. In one short transaction it looks up each language by normalized code,
inserts missing languages, and saves the country and associations. Existing names
are retained even if a later response uses a different name for the same code.

The unique language constraint protects concurrent requests. `TransactionTemplate`
provides an explicit transaction boundary: if another request inserts a language
after lookup, the failed transaction rolls back and the service retries once in a
fresh transaction. Other constraint violations are not retried. Each attempt uses
new entity objects so rolled-back generated IDs are never reused. The operation
starts its own transaction even if the caller has one; call it after SOAP retrieval,
not as part of a larger database transaction. Creation and updates share the language resolution and bounded retry logic.

## Verify this milestone

With MySQL healthy, export the local variables and run:

```sh
set -a
. ./.env
set +a
./mvnw test
```

The repository integration tests use the configured MySQL database and exercise
save/reload, updates, sharing, association removal, safe country deletion, and
database uniqueness constraints. Their data changes roll back after each test.
A concurrent integration test forces two requests to discover the same missing
language, then verifies that both countries share one stored language. That test
commits transactions and cleans up its own rows in a `finally` block. Generated ID
sequences may advance. Tests use country codes `ZZ`/`ZX` and `test-` language codes;
keep those free in the development database and do not use a production database.
Persistence unit tests also verify language reuse, code normalization, retry limits,
and failure propagation. The full suite includes the mocked SOAP tests described above.
The existing context test also verifies that Spring starts successfully. No test
calls the public SOAP service.

After starting the application, inspect the generated schema interactively:

```sh
docker compose exec mysql sh -c 'mysql -u "$MYSQL_USER" -p "$MYSQL_DATABASE"'
```

Enter `DB_PASSWORD` from your local `.env`, then run:

```sql
SHOW TABLES;
SHOW CREATE TABLE country_info;
SHOW CREATE TABLE language;
SHOW CREATE TABLE country_language;
```

Expect three tables, unique country and language ISO codes, and two foreign keys
in `country_language` linking the entities. See the API and Docker instructions above
and the [Kubernetes guide](k8s/README.md) for deployment and troubleshooting.
