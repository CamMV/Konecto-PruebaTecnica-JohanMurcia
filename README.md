# Series 76 Technical Assistant

Konecto AI Engineer assessment (Johan Murcia). A technical assistant for **Series 76 electric actuators**:

- **Backend** (Python, FastAPI, LangChain, PostgreSQL + pgvector): an agent that answers questions about a specific
  actuator by its Base Part Number and recommends models from technical requirements.
- **Mobile app** (Android, Kotlin + Jetpack Compose): login, conversation list, chat with product cards, and a debug
  screen for developers.

The numbers the user sees always come from the database, never from the LLM.

```
 Android app ──HTTP/JSON──▶ FastAPI ──▶ LangChain agent (AGENT_MODEL via OpenRouter)
 (Room cache,               (JWT auth,      │  tools: lookup_actuator · search_actuators
  outbox)                    sessions)      ▼
                                       PostgreSQL ── actuator / actuator_rating / actuator_electrical
                                         │           └─ actuator_flat (view the tools query)
                                         └── app_user / chat_session / chat_message / message_trace
```

---

## Contents
1. [Quick start](#1-quick-start)
2. [Setup: requirements and environment variables](#2-setup)
3. [Backend with Docker](#3-backend-with-docker)
4. [Ingestion](#4-ingestion)
5. [Models](#5-models)
6. [API](#6-api)
7. [Mobile app](#7-mobile-app) (emulator, physical phone over USB or over the network, debug screen)
8. [Design and architecture](#8-design-and-architecture)
9. [Optional features](#9-optional-features)
10. [Tests](#10-tests)
11. [Repository layout](#11-repository-layout)

---

## 1. Quick start

```bash
git clone <this-repo> konecto && cd konecto
cp .env.example .env                 # set OPENROUTER_API_KEY and JWT_SECRET
docker compose up -d --build         # Postgres + backend; migrations, test users and data load run automatically
curl http://localhost:8001/api/health   # {"status":"ok","db":true,"actuators":68}

cd app && ./gradlew :app:installDebug   # with an emulator running or a phone plugged in
```
Log in with `ana@konecto.test` / `Series76!demo`. Details for every step follow.

## 2. Setup

### Requirements
| For | Needs |
|---|---|
| Backend (Docker) | Docker with Compose v2 |
| Backend (local dev, tests, re-running the PDF extraction) | Python 3.12 + [uv](https://docs.astral.sh/uv/) |
| App | JDK 17+ (Android Studio's bundled JDK works), Android SDK (API 37 platform), `adb` (platform-tools) |
| App on a phone | Android 8.0+ (API 26), USB debugging enabled |

### Environment variables (`.env`, copied from `.env.example`)
| Variable | Required | Default | Purpose |
|---|---|---|---|
| `OPENROUTER_API_KEY` | **yes** | — | LLM for the agent (and embeddings for the optional approach A index) |
| `JWT_SECRET` | **yes** (change it) | `change-me` | Signs login tokens. `python -c "import secrets; print(secrets.token_urlsafe(32))"` |
| `AGENT_MODEL` | no | `anthropic/claude-haiku-5.5` | Agent model (OpenRouter id) |
| `BACKEND_PORT` | no | `8001` | Host port of the API |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` / `POSTGRES_PORT` | no | `konecto` / `konecto` / `konecto` / `5432` | Database container |
| `DATABASE_URL` | no | `postgresql+psycopg://konecto:konecto@localhost:5432/konecto` | Only for running Python outside Docker (Compose overrides it to reach the `db` service) |
| `AGENT_MAX_MODEL_CALLS` / `AGENT_MAX_TOOL_CALLS` / `AGENT_TIMEOUT_S` | no | `6` / `5` / `60` | Cost and latency guards per turn |
| `HISTORY_TURNS` | no | `8` | Previous turns sent to the agent |
| `DEBUG_API_ENABLED` | no | `true` | Enables the debug endpoint (admins only) |
| `JWT_EXPIRE_MINUTES`, `CORS_ORIGINS` | no | `1440`, `*` | |
| `GOOGLE_API_KEY`, `EXTRACTION_MODEL` | only to re-extract the PDF | —, `gemini-3.6-flash` | PDF → Markdown with Gemini (Google AI Studio, free tier) |
| `EMBEDDING_PROVIDER`, `EMBEDDING_MODEL`, `EMBEDDING_DIM` | only for approach A | `openrouter`, `openai/text-embedding-3-small`, `1536` | Embeddings for the optional hybrid index |

## 3. Backend with Docker

```bash
cp .env.example .env              # fill in OPENROUTER_API_KEY and JWT_SECRET
docker compose up -d --build
docker compose logs -f backend    # wait for "Uvicorn running on http://0.0.0.0:8000"
```

- **API**: `http://localhost:8001` · Swagger UI: `http://localhost:8001/docs` · health: `http://localhost:8001/api/health`.
- The container listens on 8000; Compose publishes it on the host as `${BACKEND_PORT}` (8001 by default).
- On every start the entrypoint (`backend/docker/entrypoint.sh`) runs: Alembic migrations → test users (idempotent)
  → **loads the catalog from `data/processed/actuators.json` if the DB is empty** (no API keys needed) → uvicorn.
- Data lives in the `konecto_pgdata` volume. It survives `docker compose down`; `docker compose down -v` wipes it
  (the next start reloads the catalog; conversations are lost).

Try it from the command line (one agent turn costs about USD 0.001):
```bash
TOKEN=$(curl -s http://localhost:8001/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ana@konecto.test","password":"Series76!demo"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

curl -s http://localhost:8001/api/conversation -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"What is the torque of 761A00-11300000/A?"}' | python3 -m json.tool
# Follow-up: pass the returned session_id and ask "and how much power does it draw?"
```

Running the backend without Docker (development):
```bash
docker compose up -d db
cd backend && uv sync
uv run alembic upgrade head && uv run python -m app.auth.seed && uv run python -m pipeline.ingest
uv run uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
```

## 4. Ingestion

The only source of truth is `docs/data/series_76_electric_data.pdf` (4 pages, 7 tables embedded **as images**, 68 part
numbers). The pipeline lives in `backend/pipeline/` and has two stages; the reviewed output of stage 1 is committed,
so stage 2 is all a reviewer needs (Docker runs it automatically).

| Stage | Command (from `backend/`) | Output |
|---|---|---|
| 1. PDF → Markdown (vision LLM, needs `GOOGLE_API_KEY`) | `uv run python -m pipeline.extract_pdf_to_md` | `data/processed/series_76_electric_data.md` (page images cached in `data/interim/`) |
| 1b. (optional) cell-by-cell diff of two extractions | `uv run python -m pipeline.compare_extractions A.md B.md` | report on stdout |
| 2. Markdown → JSON → validation → Postgres | `uv run python -m pipeline.ingest --from-md` | `data/processed/actuators.json` + tables `actuator`, `actuator_rating`, `actuator_electrical`, view `actuator_flat` |
| 2b. (optional, approach A) search index + embeddings | `uv run python -m pipeline.ingest --from-md --with-index` (or `pipeline.build_index [--no-embeddings]`) | table `search_document` |

- Stage 1 uses a fixed column vocabulary (`backend/pipeline/prompts/extract_tables.md`). The result was cross-checked
  against an independent transcription and the PDF: one merged-cell error was fixed by hand (motor power of
  `764Y30-11300000/A`, 90 → 180 W). The header of the `.md` documents the review.
- Stage 2 is deterministic: it parses the Markdown, validates it (Pydantic types plus checks: row count per table,
  unique part numbers, in-lbs ↔ Nm consistency, speed/power sanity warnings) and replaces the catalog in one
  transaction (migrations are applied first). `uv run python -m pipeline.ingest` (without `--from-md`)
  loads the committed `actuators.json` directly.
- Data model: one row per part number (`actuator`), one per service type (`actuator_rating`: `on_off`, `modulating`, or
  `general` for the 3-phase tables, which don't split them), one per frequency (`actuator_electrical`: 60 Hz, 50 Hz, or
  none for 24 V). `actuator_flat` pivots everything into one row per part, which is what the tools query.
- Edge cases kept as data: 4 rows are **N/A** in the 220V 3-phase table (`data_available=false`: the part exists, the
  data doesn't); sizes 6 and 7 (`766*`, `767*`) are **not CSA certified**.

## 5. Models

All models are set through environment variables.

| Task | Variable | Model | Why |
|---|---|---|---|
| Agent (tool calling, answer writing) | `AGENT_MODEL` | `anthropic/claude-haiku-5.5` via OpenRouter | Reliable structured tool calls (it must fill a typed filter schema), fast, ~9.5k input tokens ≈ USD 0.001 per turn. The answer only rephrases data the tools returned, so a bigger model adds cost without adding accuracy. |
| PDF table extraction | `EXTRACTION_MODEL` | `gemini-3.6-flash` via Google AI Studio | The tables are images; it is multimodal and free (the assessment's hint), so the OpenRouter budget was not spent on experiments. Run once offline; output reviewed by hand. |
| Embeddings (approach A only) | `EMBEDDING_MODEL` | `openai/text-embedding-3-small` via OpenRouter | Cheap; only used by the optional hybrid index, not by the main path. |

Budget guards: `AGENT_MAX_MODEL_CALLS` and `AGENT_MAX_TOOL_CALLS` cap each turn; the API tests use a fake agent
(no LLM cost); retrieval evals cache tool calls in `data/interim/eval_cache/`.

## 6. API

Full contract with examples: [`docs/api_contract.md`](docs/api_contract.md). Code: `backend/app/api/schemas.py`.
Interactive docs: `http://localhost:8001/docs`.

| | Method | Path | Auth |
|---|---|---|---|
| Required | POST | `/api/conversation` (`session_id?`, `query`, `client_message_id?`) | Bearer |
| Suggested | GET | `/api/sessions?limit&offset` | Bearer |
| Suggested | GET | `/api/sessions/{id}/messages` | Bearer |
| Extra | DELETE | `/api/sessions/{id}` | Bearer |
| Login | POST | `/api/auth/login` · GET `/api/auth/me` | — / Bearer |
| Debug | GET | `/api/debug/sessions` · `/api/debug/sessions/{id}/messages` · `/api/messages/{message_id}/debug` | Bearer, **admin** |
| Health | GET | `/api/health` | — |

The `/api/conversation` response adds to the fixed fields (`session_id`, `answer`):
- `status`: `answered | clarification_needed | not_found | no_match | data_unavailable | out_of_scope`, so the app
  can render each edge case differently.
- `products[]`: product cards built **from the database** (enclosure, power supply, certifications, ratings per service
  type, electrical data per frequency, source table and page), with `role` `match` or `alternative`. This is what the
  app shows as comparable cards.
- `suggestions[]`: "did you mean…?" part numbers or clarification options.
- `message_id`: used to open the debug trace of that answer.

Changes compared with the suggested endpoints, and why:
- `/api/sessions` returns `{items, total}` with `limit`/`offset`: pagination, and each item carries
  `updated_at`, `message_count` and `last_message_preview` for the list screen.
- `/api/sessions/{id}/messages` returns `products` and `suggestions` too, so a resumed conversation looks the same.
- Debug uses **separate endpoints**, restricted to admins: the chat response never carries tool names or parameters.
  Admins see every user's conversations there (the normal session endpoints stay scoped to the caller).
- `client_message_id` makes sending idempotent (the app retries after connectivity drops).
- A `session_id` that doesn't exist yet is created with that id, so the app can start conversations offline.
- `DELETE /api/sessions/{id}`, `/api/auth/me` (validate a saved token, refresh the role) and `/api/health` (the app's
  "Test connection" button) were added.
- Errors share one shape: `{"error": {"code", "message"}}`.

## 7. Mobile app

Android, Kotlin + Jetpack Compose, minSdk 26, target 37. The Gradle project is in `app/` (module `:app`, package
`com.konecto.series76`).

### Build
```bash
cd app
# The Android SDK location: either ANDROID_HOME is set, or create local.properties:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties      # macOS: $HOME/Library/Android/sdk
./gradlew :app:assembleDebug
# APK: app/app/build/outputs/apk/debug/app-debug.apk
```
Or open the `app/` folder in Android Studio and press Run. (`local.properties` may also hold `release.*` signing
keys; they're only needed for `assembleRelease`.)

### Run on an emulator
Start any emulator (API 26+), then `./gradlew :app:installDebug`. The default backend URL is
`http://10.0.2.2:8001/` (10.0.2.2 is the host machine as seen from the emulator), so it works without changes.

### Install on a physical phone
1. On the phone: **Settings → About phone → tap "Build number" 7 times** to enable Developer options, then
   **Settings → System → Developer options → USB debugging: on**.
2. Plug it in by USB and accept the "Allow USB debugging?" prompt on the phone.
3. On the computer:
   ```bash
   adb devices                         # the phone must show as "device" (not "unauthorized")
   cd app
   ./gradlew :app:installDebug         # build + install
   # or, with an APK that is already built:
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell monkey -p com.konecto.series76 1   # launch it (or tap the "Konecto" icon)
   ```
   If `adb` isn't on your PATH it's in `<sdk>/platform-tools/adb` (e.g. `~/Android/Sdk/platform-tools/adb`).
   With several devices connected, add `-s <serial>` to the `adb` commands.

   If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE ... signatures do not match`, the phone already
   has a copy signed with another key (e.g. a release build). Remove it first; this only clears the local cache and
   login, since conversations are stored on the backend:
   ```bash
   adb uninstall com.konecto.series76
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. Point the app to the backend with one of the two options below.

### Point the app to the backend
The URL is changed at runtime: on the Login screen tap **⚙** (top right) → type the URL → **Test** (calls
`/api/health`) → **Save**. It is also editable from the Debug screen. Saving a different URL logs you out and clears
the local cache. `http://` and `https://` are accepted; `host:port` alone also works (`http://` is added).

#### Option 1: USB (`adb reverse`)
The phone's `127.0.0.1:8001` is tunneled over the USB cable to the computer's port 8001. No Wi-Fi, firewall or IP
needed.
```bash
docker compose up -d                    # backend on the computer, port 8001
adb reverse tcp:8001 tcp:8001           # run again after replugging the cable or restarting adb
adb reverse --list                      # check:  UsbFfs tcp:8001 tcp:8001
```
In the app: **⚙ → `http://127.0.0.1:8001` → Test → Save**.

#### Option 2: over the network (backend in Docker on any PC)
Run the backend on any PC with Docker; Compose publishes the API on **all interfaces of that PC** (`0.0.0.0:8001`),
so the phone connects to the **PC's IP on the local network**. Don't use the container's internal IP (`172.x.x.x` /
`10.x` from `docker inspect`): it only exists inside that PC.

1. On the PC: `docker compose up -d` and check `curl http://localhost:8001/api/health`.
2. Get the PC's LAN IP:
   ```bash
   hostname -I | awk '{print $1}'                          # Linux
   ip route get 1.1.1.1 | awk '{for(i=1;i<NF;i++) if($i=="src") print $(i+1)}'   # Linux, interface used for outbound traffic
   ipconfig getifaddr en0                                  # macOS (Wi-Fi)
   ipconfig                                                # Windows: "IPv4 Address" of the Wi-Fi/Ethernet adapter
   ```
3. Connect the phone to the **same Wi-Fi/LAN** as the PC. Allow the port on the PC's firewall if it has one:
   ```bash
   sudo ufw allow 8001/tcp                                 # Linux with ufw
   # Windows: allow inbound TCP 8001 in "Windows Defender Firewall" (or accept the prompt Docker Desktop shows)
   ```
4. From the phone's browser open `http://<PC-IP>:8001/api/health`; it should return `{"status":"ok",…}`.
5. In the app: **⚙ → `http://<PC-IP>:8001` → Test → Save**.

Notes:
- **Android 17 and up**: the app asks for the **"Nearby devices"** permission at startup; it is required to reach
  local-network addresses (`192.168.x.x`, `10.x`, `10.0.2.2`). If it was denied, enable it in the app's settings.
  Option 1 (`127.0.0.1`) doesn't need it.
- Guest or corporate Wi-Fi networks often isolate clients; use Option 1 or a phone hotspot instead.
- To reach the backend from outside the LAN, put it behind any HTTPS reverse proxy or tunnel and use that `https://`
  URL in the app (a path prefix such as `https://host/konecto/` is kept).

### Using the app
- **Login** with a test user (see [Optional features](#9-optional-features)).
- **Chats**: your conversations (newest first). Tap one to resume it with the same context; **New chat** starts one;
  swipe a card all the way to the left to delete it (with confirmation).
- **Conversation**: multi-turn chat. Product data is shown as cards (power supply, torque per service type,
  speed/current per frequency, motor power, certifications), never tool names or internals. Each status has its own
  rendering: "did you mean…?" chips, clarification options, "no model meets it" with alternatives, "no data" for N/A.
- **Offline**: messages written without connection stay as "waiting for connection" and are sent automatically when
  the network comes back (also after the app is killed, through WorkManager). Server errors show "tap to retry".
- **Loading and error states**: every screen shows progress and an error banner with retry when the backend doesn't
  respond.

### Debug screen (developers only)
- Log in as **`ana@konecto.test`** (admin). A **Debug** button appears on the Chats screen. Regular users
  (`luis@konecto.test`) don't see it, and the backend answers `403 ADMIN_REQUIRED` if they call the endpoints anyway.
- The Debug screen lists **every user's conversations, grouped by user** (header with name, email, role and chat count;
  the logged-in admin first). It loads them from the server, so it needs a connection; pull down to refresh.
- Debug → pick a conversation → its answers, each shown as "<user name>: question" + a preview of the answer +
  date · status → pick one → the question, the full answer and its trace: every tool call with its parameters, the
  data it returned and its duration, plus tokens, latency and the numeric grounding result.
- Server-side kill switch: `DEBUG_API_ENABLED=false` in `.env`.

## 8. Design and architecture

### Retrieval: structured queries instead of semantic search
Two approaches were built and measured on the same 27-case set (`backend/pipeline/eval/retrieval_cases.yaml`,
details in [`docs/retrieval_eval.md`](docs/retrieval_eval.md)):

| Approach | hit@1 | hit@5 | MRR | recall@5 | abstains when nothing matches |
|---|---|---|---|---|---|
| A · BM25 (in SQL) | 0.79 | 0.96 | 0.85 | 0.87 | 0 / 3 |
| A · dense (pgvector) | 0.62 | 0.79 | 0.72 | 0.71 | 0 / 3 |
| A · hybrid (RRF) | 0.75 | 1.00 | 0.85 | 0.92 | 0 / 3 |
| **B · structured + fuzzy ID** | **1.00** | **1.00** | **1.00** | **1.00** | **3 / 3** |

**B is the main path.** The agent has two LangChain tools (`backend/app/agent/tools.py`):
- `lookup_actuator(part_number)`: normalizes the ID and cascades exact → prefix (partial IDs such as `761A`, missing
  `/A`) → fuzzy (`pg_trgm`, only as "did you mean…?" suggestions) → none.
- `search_actuators(filters)`: the LLM fills a typed Pydantic schema (enclosure, voltage, phase, AC/DC, service type,
  min/max torque in in-lbs or Nm, max speed, max current, max power, CSA, model prefix, sort) and the tool runs
  parameterized SQL over `actuator_flat`. Default ranking is right-sizing (smallest torque that meets the requirement).

Why: thresholds and superlatives ("at least 2,000 in-lbs", "the fastest") are exact comparisons that SQL solves and
embeddings don't; an empty result means "no model meets it" instead of always returning the nearest neighbor; N/A rows
are returned flagged, so "exists without data" is told apart from "doesn't exist"; and tool arguments are auditable
on the debug screen. Trade-off: it depends on the LLM filling the schema correctly (clear field descriptions +
validation), and requirements outside the schema ("for a small valve") need a clarification question. Approach A
(BM25 + pgvector + RRF) stays in the code as an optional fallback; it is not part of the main flow.

### Numbers never come from the LLM
- Product cards are built from `actuator_flat` (`backend/app/agent/products.py`): only parts that the tools returned
  in this turn and that the answer cites.
- Numeric grounding (`backend/app/agent/grounding.py`): every number in the answer text is checked against the data
  returned in the turn; unverified numbers trigger one correction pass. Limitation: it checks the number exists in the
  data, not which field it belongs to; the cards are the reference.
- The agent returns a structured `AgentAnswer` (status, cited parts, suggestions), so the edge cases map to explicit
  statuses instead of free text.

### Conversations and multi-turn
- History lives in our own tables (`chat_session`, `chat_message`, `message_trace`) instead of a LangGraph
  checkpointer, because the API needs them anyway (list, resume, debug) and they survive restarts.
- Each turn sends the last `HISTORY_TURNS` question/answer pairs **without** raw tool results, plus a short context
  block with the parts referenced so far. Follow-ups ("and how much power does it draw?") query the DB again instead of
  trusting numbers from earlier turns. This keeps prompts small (~9.5k tokens) at the cost of one extra tool call.
- Cost guards per turn (model calls, tool calls, timeout) via LangChain middleware.

### Mobile app
- MVVM: `domain/` (models, repository interfaces) · `data/` (Retrofit, Room, DataStore, mappers) · `sync/` (outbox) ·
  `ui/feature/*` (stateless screens + ViewModels exposing `StateFlow`). Hilt for DI.
- Offline-first: Room is the single source of truth; screens observe it and refreshes update it. Sending writes a
  pending message first; an outbox (triggered immediately, on reconnection and by WorkManager) delivers it with
  `client_message_id`, so retries never duplicate answers.
- The backend URL is runtime state (DataStore + an OkHttp interceptor), so the same APK works with an emulator, USB
  or a LAN server.
- UI from a Figma mockup; the animated background is a Figma shader ported to AGSL (API 33+) with a CPU fallback for
  older devices.

### Known limitations
- The retrieval eval set is small (27 cases) and was written while designing B.
- The approach A index is not rebuilt automatically in Docker (it needs embeddings; the main path doesn't use it).
- No instrumented UI tests; the CPU fallback of the background shader hasn't run on a real API < 33 device.

## 9. Optional features

### Login (implemented)
Passwords are stored hashed (argon2); the token is a JWT sent as `Authorization: Bearer <token>`; each user only
sees their own conversations. Test users are created on every backend start (`backend/app/auth/seed.py`):

| Email | Password | Role |
|---|---|---|
| `ana@konecto.test` | `Series76!demo` | admin (sees the Debug screen) |
| `luis@konecto.test` | `Series76!demo` | regular user |

### Voice messages
Not implemented.

## 10. Tests

```bash
cd backend && uv run pytest              # 39 tests; API tests use a fake agent (no LLM cost); DB tests skip if Postgres is down
uv run python -m pipeline.eval_retrieval # retrieval metrics A vs B
uv run python scripts/smoke_conversation.py --base-url http://localhost:8001   # real agent, 8 scenarios (< USD 0.02)

cd app && ./gradlew :app:testDebugUnitTest   # 21 JVM tests (Robolectric, Room in memory, MockWebServer)

scripts/check_deployment.sh [http://localhost:8001]   # checks a running backend (no LLM cost), see below
```

### Deployment check
`scripts/check_deployment.sh` checks a running backend without calling the LLM: health, DB and catalog (68
actuators), Swagger UI, login of both test users and a wrong password, token required, session endpoints, error
format, and debug endpoints allowed for the admin and refused (403) for the regular user. Point it at any URL, e.g. the
PC's LAN IP before connecting the phone: `scripts/check_deployment.sh http://192.168.1.20:8001`.

### Continuous integration (GitHub Actions, `.github/workflows/`)
| Workflow | Runs on | What it checks |
|---|---|---|
| `backend.yml` · Backend | changes in `backend/`, `data/processed/` | Lint (syntax errors, undefined names); `actuators.json` regenerated from the reviewed `.md` is identical and valid; migrations + ingestion into Postgres/pgvector; table counts (68 actuators, 111 ratings, 118 electrical rows, 7 tables); `pytest` with no skipped tests |
| `android.yml` · Android | changes in `app/` | JVM unit tests and the debug APK, uploaded as the `app-debug-apk` artifact (download it from the run page and install it with `adb install`) |
| `deploy.yml` · Deployment | changes in `backend/`, `docker-compose.yml`, `.env.example`, data | `cp .env.example .env` + `docker compose up --build` exactly as in this README, `scripts/check_deployment.sh`, then a backend restart: the checks pass again and the catalog is not reloaded (data persists) |

None of them spends OpenRouter credit. The Deployment workflow has an optional job that runs real multi-turn
conversations (`backend/scripts/smoke_conversation.py`, < USD 0.02): add the repository secret `OPENROUTER_API_KEY`
(Settings → Secrets and variables → Actions), then Actions → Deployment → Run workflow → tick `real_agent`.
All three can also be started by hand from the Actions tab.

## 11. Repository layout
```
docker-compose.yml        db (pgvector/pgvector:pg17) + backend
.env.example              environment template (no real keys)
backend/                  FastAPI + LangChain (uv project)
  app/agent/              agent, tools, prompts, grounding, tracing
  app/api/                routes and response schemas
  app/retrieval/          structured.py (B, main) · lexical/dense/hybrid (A, optional)
  alembic/                migrations
  pipeline/               PDF extraction, parsing, validation, ingestion, retrieval eval
  Dockerfile, docker/entrypoint.sh
app/                      Android app (Gradle project, module :app)
data/processed/           reviewed Markdown + actuators.json (ingestion source)
docs/                     assessment, source PDFs, API contract, retrieval evaluation
scripts/check_deployment.sh   checks a running backend (no LLM cost)
.github/workflows/        CI: backend tests, Android build, deployment check
CLAUDE.md, .claude/       AI assistant configuration (Claude Code)
```

## AI assistant setup
Built with Claude Code. `CLAUDE.md` holds the project instructions and decisions log the assistant worked from;
`.claude/settings.json` holds the project settings (Figma plugin used to implement the mockup).
