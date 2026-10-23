# Country service

A Java 21 / Spring Boot application that will look up country information through
SOAP, store it in MySQL, and expose REST CRUD operations.

Currently implemented: the `CountryInfo` and `Language` entities, their repositories,
`CountryPersistenceService` for saving a country with shared languages, and a tested
SOAP client. REST endpoints and orchestration between SOAP and persistence are
upcoming milestones.

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
docker compose up -d
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

## SOAP client

`CountrySoapClient` provides two independent methods:

1. `getCountryIsoCode(countryName)` sends `CountryISOCode` with `sCountryName` and
   returns a validated two-letter country code.
2. `getFullCountryInfo(isoCode)` sends `FullCountryInfo` with `sCountryISOCode` and
   returns country fields and a list of languages.

`CountryDetails` and `LanguageDetails` are Java records: small immutable data
carriers separate from the JPA entities. The country record also copies its list
to prevent callers from modifying its languages. The client never accesses MySQL.
Country-name normalization and calling these operations in sequence will be the
application service's responsibility in the next milestone.

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

No retry or circuit breaker is added in this milestone. SOAP faults remain distinct
from transport failures so later retry logic can avoid retrying every failure.

Run just the SOAP tests without MySQL or public-service access:

```sh
./mvnw -Dtest=CountrySoapClientTests test
```

Spring-WS `MockWebServiceServer` checks outgoing envelopes, parameters, namespaces,
the endpoint and SOAP action, then supplies local XML fixtures. Tests cover mapping,
multiple/empty languages, alternate prefixes, XML escaping, unknown countries,
malformed responses, faults, and simulated transport/timeouts. Simulating a timeout
tests exception handling; it does not measure real network timeout timing.

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
not as part of a larger database transaction. It currently handles creation only.

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
in `country_language` linking the entities. API examples, image builds, Kubernetes deployment,
and troubleshooting instructions will be added with those milestones.
