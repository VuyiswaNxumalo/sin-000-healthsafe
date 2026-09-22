# HealthSafe

Hospital ward status and emergency staffing schedules.

Domain entities: wards, wings, specialist departments.

Every class in this repo lives in a single flat package, `co.wethinkcode.healthsafe`.
HealthSafe is built as a small set of independent services, following a growth path
from simple data cleanup through synchronous REST calls to asynchronous MQ
decoupling and alerting:

1. Clean a messy legacy CSV export (`wards-outdated.csv`) — handled by `IngestionServiceApp`
2. Serve it up and act on it, via three REST services calling each other directly over HTTP
3. Decouple the relevant services with an ActiveMQ topic (`staffing-events-topic`)
   instead of direct calls — shared broker setup lives in `common/`
4. Raise the alarm on failure — handled by `EquipmentAlertServiceApp`, with
   guaranteed delivery via an ActiveMQ queue

**Status: all 4 stages complete.** Every service builds, runs, and has been verified
working end to end, including the full async flow across all 5 services.

| Service | Folder | Port | Role |
|---|---|---|---|
| IngestionServiceApp | `ingestion-service/` | 7030 | Parses and cleans `wards-outdated.csv` |
| WardServiceApp | `ward-service/` | 7031 | Provides lists of wards and departments |
| AlertLevelServiceApp | `alert-level-service/` | 7032 | Tracks the hospital Emergency Status (0-8, 8 = full Code Blue) |
| StaffingServiceApp | `staffing-service/` | 7033 | Provides on-call schedules for doctors based on ward and status |
| EquipmentAlertServiceApp | `equipment-alert-service/` | 7034 | Uses a Queue to guarantee delivery of critical medical equipment failure alerts |

Plus `common/` (no port) — the shared ActiveMQ broker and MQ config notes.

## What was built

**Stage 1 — Ingestion.** `IngestionServiceApp` reads `wards-outdated.csv`, normalizes
casing and whitespace, merges duplicate ward records (documenting conflicts rather
than silently discarding them), and flags missing/invalid `bedsAvailable` values
with an explanatory note instead of guessing at a number. Exposes `GET /wards`.

**Stage 2 — REST integration.**
- `ward-service` fetches from `ingestion-service` on startup (with retry logic in
  case it isn't ready yet) and exposes `GET /wards` and `GET /wards/{id}`.
- `alert-level-service` tracks the Emergency Status in memory, exposing
  `GET /alert-level` and `PUT /alert-level` (with 0-8 validation).
- `staffing-service` computes an on-call schedule via `GET /schedule/{wardId}`:
  it validates the ward against `ward-service`, reads the current status from
  `alert-level-service`, and scales the number of on-call doctors with severity.
  Returns `503` (not a crash) if either dependency is unreachable.

**Stage 3 — MQ decoupling.** `staffing-service` publishes every computed schedule
to `staffing-events-topic`. `ward-service` subscribes on startup and stores the
latest staffing update per ward, exposed via `GET /wards/{id}/staffing` — with no
direct call or polling between the two services.

**Stage 4 — Guaranteed-delivery alerting.** `ward-service` exposes
`POST /wards/{id}/equipment-failure` to report a failure, publishing it to
`equipment-failure-queue`. `equipment-alert-service` consumes the queue using
`CLIENT_ACKNOWLEDGE` mode, only acknowledging a message after it has been fully
processed — an unacknowledged message is redelivered rather than lost. Processed
alerts are visible via `GET /alerts`.

## A real bug found along the way

`jakarta.jms-api:2.0.3` (the dependency version used here) still ships its classes
under the old `javax.jms` package, not `jakarta.jms`, despite the artifact's name.
All MQ-related imports across `ward-service`, `staffing-service`, and
`equipment-alert-service` use `javax.jms.*` accordingly. Diagnosed by inspecting
the actual contents of the jar (`jar tf ...`) rather than assuming the package name
matched the artifact name.

## Integration contracts (as implemented)

| From | To | Call | Purpose |
|---|---|---|---|
| ward-service | ingestion-service | `GET /wards` → cleaned ward records | Populate its own ward/department list |
| staffing-service | ward-service | `GET /wards/{id}` → 404 if unknown | Validate the ward before scheduling |
| staffing-service | alert-level-service | `GET /alert-level` → `{ "level": 0-8 }` | Read current Emergency Status to size the on-call schedule |
| staffing-service | staffing-events-topic | publish | Broadcast a computed schedule |
| ward-service | staffing-events-topic | subscribe | React to staffing updates without polling |
| ward-service | equipment-failure-queue | publish | Report an equipment failure |
| equipment-alert-service | equipment-failure-queue | consume (guaranteed delivery) | Process and record the alert |

## Project structure

```
healthsafe/
├── README.md
├── .gitignore
├── ingestion-service/          (port 7030)
│   ├── pom.xml
│   ├── README.md
│   └── src/main/
│       ├── java/co/wethinkcode/healthsafe/IngestionServiceApp.java
│       └── resources/wards-outdated.csv
├── ward-service/                (port 7031)
├── alert-level-service/         (port 7032)
├── staffing-service/            (port 7033)
├── common/
│   ├── docker-compose.yml
│   └── README.md
└── equipment-alert-service/     (port 7034)
```

## Build

Requirements: Java 17+, Maven 3.8+, Docker (for the broker in `common/`).

Every folder here is an independent Maven project — there is no parent/aggregator
pom. Build one at a time:

```
cd ward-service
mvn clean package
```

...or build every module in one pass from the project root:

```
find . -name pom.xml -execdir mvn -q clean package \;
```

## Run

Start the broker first (required for stages 3-4):

```
cd common && sudo docker compose up -d
```

Then start each service, in this order, each in its own terminal:

```
# terminal 1
cd ingestion-service && java -jar target/ingestion-service.jar

# terminal 2 (waits on ingestion-service, subscribes to staffing-events-topic)
cd ward-service && java -jar target/ward-service.jar

# terminal 3
cd alert-level-service && java -jar target/alert-level-service.jar

# terminal 4 (publishes to staffing-events-topic on every schedule computed)
cd staffing-service && java -jar target/staffing-service.jar

# terminal 5 (consumes equipment-failure-queue)
cd equipment-alert-service && java -jar target/equipment-alert-service.jar
```

| Service | Port |
|---|---|
| IngestionServiceApp | 7030 |
| WardServiceApp | 7031 |
| AlertLevelServiceApp | 7032 |
| StaffingServiceApp | 7033 |
| EquipmentAlertServiceApp | 7034 |

## Try it end to end

```bash
# Compute a schedule (also publishes to staffing-events-topic)
curl http://localhost:7033/schedule/W-01

# Confirm ward-service received it asynchronously
curl http://localhost:7031/wards/W-01/staffing

# Report an equipment failure (publishes to equipment-failure-queue)
curl -X POST -H "Content-Type: application/json" \
  -d '{"equipment": "MRI Machine", "description": "Coolant pressure critical"}' \
  http://localhost:7031/wards/W-01/equipment-failure

# Confirm equipment-alert-service processed it
curl http://localhost:7034/alerts
```

## Test

Each running service exposes `/health`:

```
curl http://localhost:7030/health   # -> OK
```

No automated (JUnit) tests were added — manual end-to-end verification was used
instead, per the steps above.

## My code

WTC-QTL4L4KJ