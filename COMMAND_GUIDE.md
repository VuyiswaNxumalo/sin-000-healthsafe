# HealthSafe — Command Guide

This explains every command you'll run, what it does, what output to expect,
and what to say about it if you're demoing. Three scripts make this easier:

- **start-all.sh** — starts the broker + all 5 services in the right order automatically
- **demo-flow.sh** — walks through every demo command with pauses, so you don't have to type live
- **stop-all.sh** — shuts everything down cleanly

## One-time setup

Copy all three scripts into your repo root (`~/Downloads/sin-000-healthsafe/`),
then make them executable:

```bash
cd ~/Downloads/sin-000-healthsafe
chmod +x start-all.sh stop-all.sh demo-flow.sh
```

## Before every demo/recording

```bash
./start-all.sh
```

**What this does:** starts the ActiveMQ broker (Docker), then starts each of
the 5 services one at a time, waiting for each one's `/health` check to
return `OK` before starting the next. This matters because `ward-service`
needs `ingestion-service` running first, and `staffing-service` needs both
`ward-service` and `alert-level-service` up.

**What you'll see:** a line per service, ending in `-> <name> is up`. If
anything says `WARNING`, check the matching log file in `logs/` before
continuing — something didn't start correctly.

**If you get a permission/password prompt:** that's `sudo` asking for your
password to run Docker — type it and press enter, it's expected.

## Running the actual demo

```bash
./demo-flow.sh
```

This runs every command from your demo script, in order, printing what
command is about to run, then waiting for you to press ENTER before it
actually runs it and shows the result. This means you can talk first,
then press ENTER and let the audience see the result — much calmer than
typing curl commands live while also trying to explain them.

## After you're done

```bash
./stop-all.sh
```

Stops all 5 Java services. Leaves the Docker broker running (restarting it
every time is unnecessary — only stop it if you want to fully shut
everything down):

```bash
cd common && sudo docker compose down
```

---

## What each demo command actually proves

### `curl http://localhost:7030/wards`
**Proves:** Stage 1 — the CSV was read and cleaned correctly.
**Say:** "This is the cleaned ward data — notice the casing is consistent,
duplicates are merged, and anything uncertain is flagged with a note instead
of guessed at."

### `curl http://localhost:7033/schedule/W-01`
**Proves:** Stage 2 — staffing-service successfully called both ward-service
and alert-level-service to compute a real answer.
**Say:** "This one request triggered two other services being called behind
the scenes — one to validate the ward, one to read the current emergency
level."

### `curl -i http://localhost:7033/schedule/W-99`
**Proves:** error handling works correctly across services, not just within one.
**Say:** "Ward-service returned a 404 for this ID, and staffing-service
passed that same 404 back to me, rather than crashing or returning a
confusing 500 error."

### `curl http://localhost:7031/wards/W-01/staffing` (run right after the schedule call)
**Proves:** Stage 3 — the message queue topic actually delivered data asynchronously.
**Say:** "Ward-service never called staffing-service directly for this. It
got this data because staffing-service broadcast it to a topic, and
ward-service was listening."

### The `POST .../equipment-failure` command
**Proves:** Stage 4 — a message was published to a queue (not a topic).
**Say:** "This time I'm using a queue rather than a topic, because I need
exactly one consumer to process this alert reliably, not everyone getting a copy."

### `curl http://localhost:7034/alerts`
**Proves:** the queue consumer actually processed the message using
guaranteed-delivery acknowledgment.
**Say:** "This confirms equipment-alert-service received and fully processed
the alert. If it had crashed partway through, this message would have
stayed in the queue and been redelivered, instead of being lost."

---

