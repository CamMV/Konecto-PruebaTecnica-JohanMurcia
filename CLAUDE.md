# CLAUDE.md: Series 76 Technical Assistant (Konecto assessment)

Technical assistant that answers questions about **Series 76** electric actuators (lookup by
Base Part Number) and recommends models based on requirements. Full spec in
`docs/assesment/assessment-en.pdf`; the author's initial decisions are in `docs/assesment/primerasDesiciones.md`.

## Working rules
- **Do not make git commits.** Leave the changes in the working tree; the user reviews and commits them.
- **The numbers the user sees always come from the DB, never from the LLM.** Retrieval returns
  `base_part_number` values; the technical data is then read from `actuator_flat`.
- **Retrieval decision: approach B (structured + fuzzy ID) is the primary path.** The agent uses the
  tools in `backend/app/agent/tools.py` (`lookup_actuator`, `search_actuators`). Approach A (hybrid)
  is kept only as an optional fallback; don't add new features to A without a reason.
- The only source of truth for the numbers is `docs/data/series_76_electric_data.pdf`
  (4 pages, 7 tables **as images**, 68 part numbers).
- Don't spend the OpenRouter key (hard limit of USD 10) on experiments: use Gemini (Google AI Studio, free) for extraction.

## Current phase
1. ✅ Data schema (Pydantic + PostgreSQL + Alembic).
2. ✅ PDF → Markdown → JSON → Postgres pipeline. Extraction with `gemini-3.6-flash`, cross-checked against an
   independent transcription (`data/interim/claude_transcription.md`); 1 error fixed by hand
   (motor power of `764Y30-11300000/A`, merged cell). See the header of `data/processed/series_76_electric_data.md`.
2b. ✅ DB created and populated (see "Database state").
3. ✅ Retrieval: approaches **A (hybrid BM25 + dense)** and **B (structured + fuzzy ID)** implemented and compared
   (`docs/retrieval_eval.md`). **Decision (2026-11-03): B is the primary path**; A is an optional fallback.
4. ✅ Backend: API contract (`docs/api_contract.md`), LangChain agent (`backend/app/agent/`), login (JWT),
   separate debug endpoint, persisted multi-turn, Docker. See "Backend".
5. ⏳ Mobile app (Kotlin + Compose): next phase.

## Layout
```
docker-compose.yml        # db (pgvector/pgvector:pg17) + backend (port ${BACKEND_PORT}, default 8000)
.dockerignore
.env.example              # copy to .env
data/interim/             # intermediate artifacts (PNGs, Gemini cache). gitignored
data/processed/           # reviewed .md + actuators.json (committed, ingestion source)
backend/                  # Python project (uv)
  app/config.py           # settings from env
  app/schemas/actuator.py # Pydantic domain models
  app/db/                 # SQLAlchemy models + session
  app/retrieval/          # A: lexical.py, dense.py, hybrid.py (RRF) · B: structured.py
  app/llm.py              # ChatOpenAI via OpenRouter (AGENT_MODEL)
  app/main.py             # FastAPI app
  app/api/                # schemas.py (CONTRACT), deps.py, errors.py, routes/{auth,conversation,sessions,debug,health}.py
  app/agent/              # AGENT: graph.py, prompts.py, tools.py, schemas.py, service.py, history.py,
                          #        products.py, grounding.py, tracing.py
  app/auth/               # security.py (argon2 + JWT), seed.py (test users)
  alembic/                # migrations: 0001 catalog + actuator_flat view · 0002 users/sessions/messages/traces
  Dockerfile, docker/entrypoint.sh   # migrations → seed → ingest if empty → uvicorn
  scripts/smoke_conversation.py      # multi-turn smoke test against the real LLM (< USD 0.02)
  pipeline/               # extraction, parsing, validation, ingestion, index, eval
    prompts/extract_tables.md   # extraction prompt (fixed column vocabulary)
    eval/retrieval_cases.yaml   # retrieval eval set (shared by approaches A and B)
  tests/
```

## Commands
```bash
cp .env.example .env                       # fill in keys
docker compose up -d db
cd backend && uv sync
uv run alembic upgrade head                # schema
uv run python -m pipeline.summary_to_md    # summary PDF -> md (text layer)
uv run python -m pipeline.extract_pdf_to_md   # data PDF -> md (Gemini, needs GOOGLE_API_KEY)
uv run python -m pipeline.compare_extractions A.md B.md  # cell-by-cell diff between extractions
uv run python -m pipeline.ingest --from-md # md -> actuators.json -> validate -> Postgres
uv run python -m pipeline.build_index      # search documents + embeddings (OPENROUTER_API_KEY)
uv run python -m pipeline.build_index --no-embeddings   # lexical only, no key
uv run python -m pipeline.eval_retrieval   # A (lexical/dense/hybrid) vs B (structured) metrics
uv run python -m app.auth.seed             # test users (idempotent)
uv run uvicorn app.main:app --port 8001 --reload   # local API (8000 is taken by Coolify on this machine)
uv run pytest                              # 36 tests (API with a fake agent: no LLM cost)
uv run python scripts/smoke_conversation.py --base-url http://localhost:8001   # real agent (spends OpenRouter)

# Full Docker (from the repo root)
docker compose up -d --build               # API at http://localhost:${BACKEND_PORT}/docs
```
Note: `pkill -f "uvicorn …"` kills the shell itself if the pattern appears in the same command; use `ss -ltnp` + `kill <pid>`.
Ingestion (`ingest --from-md`) **does not need API keys**: it starts from the reviewed `.md`.

## Data model
- `actuator`: one row per part number (enclosure, voltage, phase, current type, motor W,
  certifications, `csa_certified`, `data_available`, source table/row).
- `actuator_rating`: one row per service type (`on_off`, `modulating`, `general`).
  `general` = 3-phase table, which gives a single rating without separating On/Off and Modulating
  (we don't duplicate it, so we don't invent anything).
- `actuator_electrical`: one row per frequency (`60`, `50`, or `NULL` = 24V without a split).
- `actuator_flat` (view): everything pivoted into one row per part. This is what the tools query.
- `search_document`: retrieval documents (tsvector + pgvector embedding).
- Edge cases in the data: 4 rows **N/A** in 220V 3-phase (`data_available=false`, i.e. "missing data",
  not an "invalid part number"); sizes 6 and 7 (`766*`, `767*`) **not CSA certified**.

## Database state (verified 2026-11-03)
- Container `konecto-db` (`pgvector/pgvector:pg17`), persistent volume `konecto_pgdata`, migrations `0001` + `0002`
  applied, extensions `vector` + `pg_trgm`. Container `konecto-backend` on port 8001 (`BACKEND_PORT` in `.env`).
- Conversations: `app_user` (2 test users), `chat_session`, `chat_message`, `message_trace`.
- Data loaded from the reviewed `data/processed/series_76_electric_data.md` (identical to `actuators.json`):
  `source_table` 7 · `actuator` 68 (4 N/A, 5 not CSA) · `actuator_rating` 111 · `actuator_electrical` 118 ·
  `search_document` 78 (68 actuators + 10 context, all with embeddings `openai/text-embedding-3-small`).
- The volume survives `docker compose down`; it is lost with `docker compose down -v`. To recreate it:
  `docker compose up -d db && cd backend && uv run python -m pipeline.ingest --from-md --with-index`
  (`--with-index` is only needed for approach A; B only uses the tables + `actuator_flat`).

## Backend

### API (contract: `docs/api_contract.md`, code: `backend/app/api/schemas.py`, OpenAPI at `/docs`)
| | Method | Path | Auth |
|---|---|---|---|
| Required | POST | `/api/conversation` (`session_id?`, `query`, `client_message_id?`) | Bearer |
| Suggested | GET | `/api/sessions?limit&offset` | Bearer |
| Suggested | GET | `/api/sessions/{id}/messages` | Bearer |
| Login | POST | `/api/auth/login` · GET `/api/auth/me` | — / Bearer |
| Debug | GET | `/api/messages/{message_id}/debug` (only if `DEBUG_API_ENABLED=true`) | Bearer |
| Health | GET | `/api/health` | — |
- `status` of the answer: `answered | clarification_needed | not_found | no_match | data_unavailable | out_of_scope`.
- `products[]` = `ProductCard` built **from the DB** (`agent/products.py`), never from the LLM; `role` match/alternative.
- Unknown `session_id` → created with that id (offline-first app). Another user's → 404. `client_message_id` = idempotency.
- Errors: `{"error": {"code", "message"}}` (`api/errors.py`).
- **Test users** (`app/auth/seed.py`): `ana@konecto.test` and `luis@konecto.test`, password `Series76!demo`.

### Agent chain flow (`app/agent/service.py::run_turn`)
1. JWT → user · 2. idempotency (`client_message_id`) · 3. session get/create + user message (persisted before the LLM)
4. history: last `HISTORY_TURNS` (Human, AI) pairs **without** tool results + a `<conversation_context>` block in the
   current message with the referenced/suggested parts (follow-ups query the DB again)
5. `create_agent` (LangChain 1.4): `AGENT_MODEL` + tools `lookup_actuator`/`search_actuators` (approach B, `content_and_artifact`)
   + `ToolStrategy(AgentAnswer)` + `ModelCallLimitMiddleware`/`ToolCallLimitMiddleware` (cost guard)
6. deterministic post-processing: cards = parts cited ∩ parts that came out of the DB in this turn; numeric
   **grounding** (`agent/grounding.py`): unverified numbers → 1 correction; context-leak stripping; `no_match` → role alternative
7. persist answer + `message_trace` (steps with args/result/duration, tokens, latency, grounding) → response.
- No LangGraph checkpointer: the history lives in our own tables (`chat_message`), which the API also needs.
- Smoke test against the real LLM (2026-11-03): 8/8 expected statuses; ~9.5k input tokens per simple turn (~USD 0.001).
  Persistence verified with `docker restart konecto-backend` + follow-up. Startup from an empty DB verified
  (migrations + seed + ingest with no keys).
- Known limitations: grounding validates that the number exists in the data, not which field it belongs to (the cards are the
  truth); the `search_document` index (approach A) is not rebuilt automatically in Docker (it needs embeddings, and B doesn't use it).

## Retrieval

### Approach A: Hybrid BM25 + dense  ← implemented and measured · **secondary role (optional fallback)**
- One document per part number (semantic description + key/value pairs + **ID variants**:
  `761A00-11300000/A`, `761A0011300000A`, `761A00`, `761A`) + context documents (summary, notes).
- **Lexical**: **Okapi BM25 computed in SQL** over `tsvector` (`app/retrieval/lexical.py`, k1=1.2, b=0.75),
  with no extra extensions. Our own tokenizer (`app/retrieval/text.py`) for docs and queries, because the
  `simple` parser splits IDs badly (`-11300000`, `/a`). df/avgdl are recomputed on every query (fine with
  fewer than 100 docs); to scale, swap in ParadeDB `pg_search` behind the same interface.
- **Dense**: embeddings (`EMBEDDING_MODEL`, via OpenRouter) in pgvector, cosine distance.
- **Fusion**: Reciprocal Rank Fusion (k=60) over the top-20 from each retriever. Returns IDs + per-retriever scores
  (useful for the debug screen).
- Evaluation: `backend/pipeline/eval/retrieval_cases.yaml` (27 cases; the valid set is computed with
  `expected_where` over `actuator_flat`) → hit@1, hit@5, MRR, recall@5 per mode. Results in `docs/retrieval_eval.md`.
- Results (2026-11-03, `openai/text-embedding-3-small`, 24 cases with an answer):

  | mode | hit@1 | hit@5 | MRR | recall@5 |
  |---|---|---|---|---|
  | lexical | 0.79 | 0.96 | 0.85 | 0.87 |
  | dense | 0.62 | 0.79 | 0.72 | 0.71 |
  | hybrid | 0.75 | 1.00 | 0.85 | 0.92 |

- Findings: BM25 gets 100% on exact IDs/variants; dense confuses IDs that differ by one digit
  (`…11320000` vs `…11300000`) and **pulls hybrid's top-1 down on exact IDs** (0.60).
  Hybrid is the best on requirements (0.80) and full coverage (hit@5 = 1.00). Nobody solves
  superlatives ("highest torque") or numeric thresholds, and retrieval always returns something
  for queries with no answer (480V, 100,000 in-lbs): the "nothing meets this" check must be done against the numbers.
  Short documents (N/A rows) get favored by BM25 length normalization.

### Approach B: Structured + fuzzy ID  ← ✅ **CHOSEN: primary path** (`app/retrieval/structured.py`, `tools.py`)
- **`lookup_actuator(part_number)`** (LangChain tool): normalization (uppercase, alphanumeric only) and a cascade
  `exact` → `prefix` (partial IDs: `761A`, missing `/A`) → `fuzzy` (`pg_trgm`, similarity ≥ 0.3, only
  *suggestions* "did you mean…?") → `none`.
- **`search_actuators(**SearchFilters)`** (LangChain tool, Pydantic schema): the LLM fills in the filters
  (enclosure, voltage, phase, AC/DC, service, min/max torque in in-lbs or Nm, max speed + frequency,
  max FLA, max W, CSA, model prefix, sort_by) → parameterized SQL over `actuator_flat`. Torque depends on
  the service type (`COALESCE(onoff|mod, general)`); with no service it uses `GREATEST`. Default ranking =
  right-sizing (smallest torque that meets the requirement). Empty result = "no model meets it".
  N/A rows are returned last (`data_available=false`) when they match the categorical filters
  → the agent can tell "it exists but there's no data" apart from "it does not exist".
- Agent/router: `AGENT_MODEL=anthropic/claude-haiku-5.5` (OpenRouter, tool calling, `tool_choice="any"`).
  The eval caches the tool calls in `data/interim/eval_cache/tool_calls.json`.
- DB tests: `backend/tests/test_structured.py` (skipped if Postgres is not up).

### A vs B comparison (2026-11-03, `docs/retrieval_eval.md`)

| mode | hit@1 | hit@5 | MRR | recall@5 | abstention (3 no-answer cases) |
|---|---|---|---|---|---|
| A · lexical (BM25) | 0.79 | 0.96 | 0.85 | 0.87 | 0.00 |
| A · dense | 0.62 | 0.79 | 0.72 | 0.71 | 0.00 |
| A · hybrid (RRF) | 0.75 | 1.00 | 0.85 | 0.92 | 0.00 |
| **B · structured** | **1.00** | **1.00** | **1.00** | **1.00** | **1.00** |

- B wins in every category and is the only one that **abstains** (480V, 100,000 in-lbs, the summary's
  nonexistent part number) and that resolves superlatives/thresholds.
### Decision (2026-11-03): **B is the primary path**
- **Why**: exact numeric comparisons (≥, ≤, superlatives) resolved by SQL instead of embeddings; it abstains
  ("no model meets it" / "that part number does not exist, did you mean…?") instead of always returning
  something; it tells N/A apart from nonexistent; tool arguments are auditable on the debug screen.
- **Trade-offs we accept**: it depends on the LLM filling `SearchFilters` correctly (a schema with clear
  descriptions + Pydantic validation); requirements outside the schema (e.g. "for a small valve") don't map to
  filters → there the agent should ask for clarification, or use the A fallback.
- **Role of A**: optional fallback for vague queries that don't map to filters, or questions about the context
  (summary/notes). The code and the index (`search_document`) stay as they are; it is not part of the main flow.
- **Pending (does not block the decision)**: the eval set (27 cases) is small and was written while designing B;
  one B bug (N/A rows filtered out) was fixed after seeing it in the eval → add new held-out cases
  (vague queries, multiple requirements, Spanish, comparisons between 2 parts) before the delivery.

## Conventions
- Python 3.12, uv, SQLAlchemy 2.0, Pydantic v2, Alembic.
- Amps as `Decimal`/`numeric(4,1)`; torque, speed and watts as integers.
- Models configurable through env vars:
  - `EXTRACTION_MODEL=gemini-3.6-flash` (PDF → md; 2.5-flash is no longer available for new keys, 3.8/3.7 were returning 503).
  - `AGENT_MODEL=anthropic/claude-haiku-5.5` (agent + tool calling, OpenRouter).
  - `EMBEDDING_MODEL=openai/text-embedding-3-small` (approach A only).
