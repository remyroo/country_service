# AGENTS.md

## Project Context

This project is a small technical assessment built with Java and Spring Boot.

The application receives a country name through a REST API, calls an external SOAP service to retrieve the country's ISO code and full country information, stores the result in MySQL, and exposes REST CRUD operations for the stored country data.

The application must also be containerized and include simple Kubernetes deployment and troubleshooting documentation.

The developer working on this project is experienced with software development but is new to Java and Spring Boot.

A major goal of this project is therefore **clarity and learning**, not just producing working code.

---

## Core Principles

### Keep the solution simple

Prefer the smallest implementation that correctly satisfies the requirements.

Do not introduce additional architectural layers, frameworks, services, infrastructure, or design patterns unless they solve a concrete requirement.

Do not implement speculative features.

Do not split the application into multiple microservices.

Do not introduce Kafka, RabbitMQ, Redis, an API gateway, service mesh, event sourcing, CQRS, or similar infrastructure unless explicitly requested.

### Optimize for explainability

All implementation choices should be understandable by a developer who is new to Spring Boot.

Prefer explicit and conventional Spring code over clever abstractions.

Use descriptive names.

Avoid excessive inheritance, generic abstractions, reflection, metaprogramming, or custom frameworks.

When introducing an unfamiliar Spring concept or dependency, briefly explain:

1. what it does;
2. why it is needed here;
3. what simpler alternatives were considered.

### Work incrementally

Do not implement the entire assessment in one pass.

Complete one small milestone at a time.

Before making a substantial change:

1. explain what will be changed;
2. identify the relevant files;
3. explain any new Spring/Java concept being introduced.

After each milestone:

1. run the relevant tests;
2. run or compile the application where practical;
3. summarize what changed;
4. explain how the developer can manually verify it.

Do not continue into the next major milestone unless requested.

---

## Target Architecture

Use one Spring Boot application.

Use conventional separation of concerns:

- `controller` for REST API handling
- `service` for application/business logic
- `client` for communication with the external SOAP service
- `repository` for database access
- `model` for JPA entities
- `dto` for API request/response objects where appropriate
- `exception` for centralized error handling
- `config` only where configuration is actually needed

Do not create interfaces and implementations such as `CountryService` + `CountryServiceImpl` unless there is a genuine need for multiple implementations.

Prefer constructor injection.

Do not use field injection.

---

## Application Flow

The main creation flow should remain easy to follow:

1. Receive a POST request containing a country name (e.g., { "name": "Tanzania" }).
2. Validate the request.
3. Normalize the country name to sentence case (e.g., "kenya" to
   "Kenya").
4. Call the SOAP `CountryISOCode` operation using the country name from Step 3 as a request body parameter.
5. Extract the ISO code.
6. Call the SOAP `FullCountryInfo` operation using that ISO code as a request body parameter.
7. Map the useful response fields to `CountryInfo` and `Language`.
8. Persist the result to MySQL.
9. Return an appropriate REST response.

CRUD operations should read and modify the stored data without unnecessarily calling the SOAP service again.

---

## Data Model

Keep the data model small.

The assignment requires:

- `CountryInfo`
- `Language`

Model only fields that are useful and actually returned by the required SOAP operation.

Use a straightforward JPA relationship between a country and its languages.

Avoid complex domain modelling.

---

## SOAP Integration

Use a dedicated SOAP client class.

Prefer Spring-supported SOAP functionality such as `WebServiceTemplate` where it keeps the implementation understandable.

Keep SOAP-specific XML and mapping concerns inside the client/integration layer rather than leaking them into controllers.

Configure reasonable connection and read timeouts.

Do not hand-roll unnecessary networking infrastructure.

---

## REST API

Use conventional REST semantics and HTTP status codes.

At minimum support:

- create/fetch country information from the external service
- fetch all stored countries
- fetch a stored country by ID
- update a stored country
- delete a stored country

Use request validation where appropriate.

Return concise, user-friendly error responses.

Do not expose stack traces through API responses.

---

## Error Handling and Resilience

Handle likely failures explicitly:

- invalid or empty country name
- unknown country
- country record not found
- SOAP service unavailable
- SOAP timeout
- invalid SOAP response
- database failure

Use a global exception handler for REST error responses.

The assessment requests retries, timeouts, circuit breakers, and fallbacks.

Implement these in the simplest reasonable way around the external SOAP integration.

Keep retry counts low.

Do not retry validation failures or other non-transient errors.

If a resilience library is introduced, explain why it is necessary before adding it.

---

## Logging and Observability

Use the standard Spring logging infrastructure.

Log meaningful events such as:

- incoming country lookup
- outbound SOAP operation
- successful lookup
- persistence result
- external-service error

Never log passwords or secrets.

Avoid noisy logs.

Use Spring Boot Actuator for basic health and application visibility.

Do not add a full observability stack such as Prometheus, Grafana, or distributed tracing unless explicitly requested.

Document how such tooling could be added in a production environment instead.

---

## Database and Local Development

Use MySQL as required by the assessment.

For local development, run MySQL through Docker Compose rather than requiring a machine-level MySQL installation.

Keep credentials configurable through environment variables or application configuration.

Do not commit real secrets.

Use schema generation appropriate for this assessment; do not introduce a database migration framework unless it becomes clearly necessary.

---

## Testing

Prioritize a small number of meaningful tests over high test-count coverage.

At minimum test:

- country-name normalization
- service orchestration
- controller validation/error behavior where practical
- important mapping logic

Mock the external SOAP client in service tests.

Do not call the real public SOAP service from normal unit tests.

Avoid elaborate test infrastructure.

---

## Docker and Kubernetes

Create one Docker image for the Spring Boot application if it has not already been created.

Keep the Dockerfile simple.

Create understandable Kubernetes manifests for the application and required configuration.

The application must remain stateless so multiple replicas can run safely.

Use at least two application replicas to demonstrate horizontal scaling.

Include readiness and liveness probes using Spring Boot Actuator.

Use a Kubernetes Service to expose the application within the cluster.

Keep configuration separate from the application image.

Do not introduce Helm, an ingress controller, operators, or service mesh unless explicitly requested.

---

## Scalability Decisions

Do not add infrastructure solely to appear production-ready.

When discussing scalability:

- explain that the Spring application is stateless
- explain horizontal scaling through Kubernetes replicas
- explain database considerations
- discuss caching or queues as future options only where they solve a clear bottleneck

Document trade-offs rather than implementing unnecessary infrastructure.

---

## Documentation

Maintain a concise README containing:

- what the application does
- architecture overview
- prerequisites
- how to run MySQL
- how to run the application
- example API requests
- how to run tests
- how to build the Docker image
- how to deploy to Kubernetes
- how to verify the deployment
- basic troubleshooting steps
- key architectural decisions and trade-offs

Keep Kubernetes deployment and troubleshooting instructions practical and reproducible.

---

## Code Quality

Follow conventional Java and Spring Boot practices.

Prefer:

- constructor injection
- small focused classes
- descriptive method and variable names
- immutable DTOs where practical
- clear exception handling
- explicit configuration

Avoid:

- unnecessary Lombok usage if plain Java is clearer
- premature abstraction
- deeply nested logic
- giant service classes
- magic strings
- unexplained annotations
- generated code that the developer cannot explain

If generated SOAP classes are required, keep generated code clearly separated from handwritten application code.

---

## Working With the Developer

This project is also a learning exercise.

Whenever I ask you to implement a feature:

1. first explain the approach briefly;
2. tell me which files will change;
3. identify any Java or Spring concept I should understand;
4. then make the implementation;
5. run the relevant validation/tests;
6. explain the resulting request flow in plain English.

If there are multiple reasonable approaches, recommend the simplest one and briefly explain the trade-off.

Do not optimize for impressiveness.

Optimize for correctness, clarity, maintainability, and my ability to explain the solution in an interview.
