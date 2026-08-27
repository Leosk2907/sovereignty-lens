# Backend architecture

The service is layered so that the dependency arrow only ever points inward:
**adapter → application → domain**. The domain knows nothing about HTTP, JDBC,
Jackson or Spring; the application layer knows the domain and its ports but not
their implementations; only the adapters know a framework.

```
                    inward dependencies only
    ┌──────────────────────────────────────────────────────────┐
    │  adapter.web            adapter.persistence              │  frameworks
    │  controllers, filters   JDBC repositories, row mappers   │  live here
    └───────────┬──────────────────────────┬───────────────────┘
                │ implements ports          │ implements ports
    ┌───────────▼──────────────────────────▼───────────────────┐
    │  application                                             │  use cases
    │  one service per use case, owns the transaction boundary │
    └───────────────────────┬──────────────────────────────────┘
                            │ depends on
    ┌───────────────────────▼──────────────────────────────────┐
    │  domain                                                  │  pure Java
    │  model · port · service · DomainException                │  no framework
    └──────────────────────────────────────────────────────────┘

    contract  — the frozen public wire format (version 1). Referenced by
                adapter.web and by mapper only. The domain never imports it.
```

## Packages

| Package | Contains | May depend on |
| --- | --- | --- |
| `eu.sovereigntylens.domain.model` | Pure records: `Session`, `Organization`, `Dependency`, `DependencySubmission`, `GraphView`, `DomainErrorCode`, `Jurisdiction`/`OrganizationType`/… value enums | nothing but the JDK |
| `eu.sovereigntylens.domain.port` | Outbound interfaces the application needs: `SessionRepository`, `GraphRepository`, `ContributionRepository`, `AdminRepository`, `GraphEventRepository`, `GraphEventNotifier` | `domain.model` |
| `eu.sovereigntylens.domain.service` | Framework-free domain logic: `Normalizer` | `domain.model` |
| `eu.sovereigntylens.domain` | `DomainException` | `domain.model` |
| `eu.sovereigntylens.application` | One service per use case. Owns `@Transactional`. Speaks domain models in and out | `domain.*` |
| `eu.sovereigntylens.adapter.persistence` | `Jdbc*Repository` implementations of the ports, plus `mapper/*RowMapper` turning `ResultSet` rows into domain models | `domain.*`, Spring JDBC |
| `eu.sovereigntylens.adapter.web` | `@RestController`s, `ApiExceptionHandler`, admin auth filter, `GraphEventBroadcaster` (the Server-Sent Events fan-out, which is transport). Converts contract DTOs ↔ domain models via `mapper` | `application`, `mapper`, `contract`, Spring Web |
| `eu.sovereigntylens.mapper` | `SessionMapper`, `GraphMapper`, `AdminMapper`, `ErrorMapper` — the only place a domain model becomes a contract DTO | `domain.model`, `contract` |
| `eu.sovereigntylens.contract` | The frozen version-1 wire format. Records + enums only, no logic | the JDK, Jackson, and Bean Validation annotations |
| `eu.sovereigntylens.config` | Spring wiring: `AppProperties`, `WebConfig` | anything |

## Why the domain has its own enums and error codes

`contract` is a published, versioned artefact shared with three frontend
workstreams; it may not change without a coordinated contract bump. If the
domain imported it, a wire-format concern would reach into business rules and
the two would be impossible to version independently.

So the domain carries its own `DomainErrorCode` with no knowledge of HTTP, and
`mapper.ErrorMapper` is the single place that decides a domain failure becomes,
say, `409 DUPLICATE_DEPENDENCY`. The fixed status mapping in the data contract
is therefore enforced in exactly one file.

Two dependencies are allowed into `contract` deliberately. Jackson annotations
carry the snake_case wire tokens, and Bean Validation annotations carry the
field constraints the contract itself states (a company name is 2-60
characters). Both are annotation-only APIs that describe the format rather than
transport it, and duplicating every request record into an adapter-layer twin
purely to relocate them would cost more than it buys.

A web framework is a different matter. `ApiErrorCode` originally exposed its
status as a Spring `HttpStatus`, which meant every frontend consumer of the
published contract would have needed `spring-web` on its classpath. It now
carries a plain `int`.

The value enums (`Jurisdiction`, `OrganizationType`, `SessionStatus`,
`DependencyStatus`) exist in both layers for the same reason. They are mapped
one-to-one by name in `mapper`, and a contract test asserts the two sets stay in
step, so a divergence fails the build rather than the demo.

## Transactions and atomicity

The application layer owns the transaction boundary. The contribution use case
runs one transaction that inserts the dependency and its `dependency.created`
event row together, so a rollback persists neither. `pg_notify` is transactional
too, which is what lets the SSE bridge fan out only committed events.

The bridge spans two adapters — a `LISTEN`/`NOTIFY` loop in `adapter.persistence`
and the Server-Sent Events fan-out in `adapter.web` — and neither imports the
other. `PostgresNotificationListener` calls `domain.port.GraphEventNotifier`,
which `GraphEventBroadcaster` implements, so that arrow points inward like every
other one. It is a port for direction, not for substitution.

## Testing seams

Because the application layer depends on ports rather than JDBC, use-case tests
run against in-memory fakes with no database. The adapters are covered
separately by Testcontainers integration tests that exercise real SQL, and the
end-to-end suite drives the assembled service over HTTP.
