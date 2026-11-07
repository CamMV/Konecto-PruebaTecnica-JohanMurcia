"""API tests with a fake agent (no LLM, no cost). They require Postgres with the data loaded.

The fake agent runs the REAL tools (lookup_actuator) against the DB and builds the answer from their data,
so the whole flow is exercised: tracing, cards from the DB, grounding, persistence and the endpoints.
"""

from __future__ import annotations

import re
import uuid

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, HumanMessage
from sqlalchemy import delete, text

from app.agent.schemas import AgentAnswer
from app.agent.tools import lookup_actuator
from app.api.deps import get_agent
from app.auth.security import hash_password
from app.config import get_settings
from app.db.models import AppUser
from app.db.session import SessionLocal
from app.main import app

PN_RE = re.compile(r"76\d[A-Z]\d{2}-[0-9A-Z]{8}/A", re.I)
PASSWORD = "test-pass"


class FakeAgent:
    """Mimics create_agent: one lookup per part number in the query (or in the history), then AgentAnswer."""

    def __init__(self, lie: bool = False):
        self.lie = lie  # first answer with an invented number (to test the grounding correction)
        self.calls = 0

    def invoke(self, inp: dict, config=None) -> dict:  # noqa: ANN001
        self.calls += 1
        msgs = list(inp["messages"])
        last_human = next(m for m in reversed(msgs) if isinstance(m, HumanMessage)).content
        if last_human.startswith("Automatic check"):
            pn = None
            for m in reversed(msgs):
                if found := PN_RE.findall(str(m.content)):
                    pn = found[0]
                    break
            return self._answer(msgs, pn, lie=False)
        user_text = re.search(r"<user_message>\n(.*)\n</user_message>", last_human, re.S)
        context = re.search(r"<conversation_context>(.*)</conversation_context>", last_human, re.S)
        found = PN_RE.findall(user_text.group(1) if user_text else last_human) or (
            PN_RE.findall(context.group(1))[-1:] if context else []
        )
        return self._answer(msgs, found[0] if found else None, lie=self.lie)

    def _answer(self, msgs, pn, lie):  # noqa: ANN001
        if pn is None:
            ans = AgentAnswer(answer="Which part number or requirements?", status="clarification_needed")
            return {"messages": msgs + [AIMessage("")], "structured_response": ans}
        call = {"name": "lookup_actuator", "args": {"part_number": pn}, "id": f"call_{uuid.uuid4().hex[:8]}", "type": "tool_call"}
        ai = AIMessage("", tool_calls=[call], usage_metadata={"input_tokens": 100, "output_tokens": 10, "total_tokens": 110})
        tool_msg = lookup_actuator.invoke(call)
        art = tool_msg.artifact
        if art["match_type"] in ("fuzzy", "none"):
            ans = AgentAnswer(
                answer=f"{pn} does not exist.",
                status="not_found",
                suggestions=[r["base_part_number"] for r in art["results"]],
            )
        else:
            r = art["results"][0]
            power = 99999 if lie else r.get("motor_power_w")
            ans = AgentAnswer(
                answer=f"{r['base_part_number']}: motor power {power} W.",
                status="answered" if r["data_available"] else "data_unavailable",
                part_numbers=[r["base_part_number"], "999Z99-00000000/A"],  # the second one doesn't exist → discarded
            )
        return {"messages": msgs + [ai, tool_msg], "structured_response": ans}


# ---------------------------------------------------------------- fixtures

@pytest.fixture(scope="module")
def users():
    try:
        with SessionLocal() as db:
            if not db.scalar(text("SELECT count(*) FROM actuator")):
                pytest.skip("empty DB: run pipeline.ingest")
    except Exception as exc:  # noqa: BLE001
        pytest.skip(f"DB not available: {exc}")
    tag = uuid.uuid4().hex[:8]
    emails = [f"test-a-{tag}@konecto.test", f"test-b-{tag}@konecto.test"]  # a = admin, b = regular user
    with SessionLocal.begin() as db:
        db.add_all(
            AppUser(email=e, name=e, password_hash=hash_password(PASSWORD), is_admin=(i == 0)) for i, e in enumerate(emails)
        )
    yield emails
    with SessionLocal.begin() as db:
        db.execute(delete(AppUser).where(AppUser.email.in_(emails)))  # cascade: sessions, messages, traces


@pytest.fixture
def agent():
    fake = FakeAgent()
    app.dependency_overrides[get_agent] = lambda: fake
    yield fake
    app.dependency_overrides.clear()


@pytest.fixture(scope="module")
def client():
    return TestClient(app)


def login(client, email):  # noqa: ANN001
    r = client.post("/api/auth/login", json={"email": email, "password": PASSWORD})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


# ---------------------------------------------------------------- tests

def test_health(client):
    body = client.get("/api/health").json()
    assert body["db"] is True and body["actuators"] == 68


def test_login_errors(client, users):
    r = client.post("/api/auth/login", json={"email": users[0], "password": "wrong"})
    assert r.status_code == 401 and r.json()["error"]["code"] == "INVALID_CREDENTIALS"
    assert client.get("/api/sessions").status_code == 401
    assert client.get("/api/sessions", headers={"Authorization": "Bearer nope"}).json()["error"]["code"] == "UNAUTHORIZED"
    assert client.get("/api/auth/me", headers=login(client, users[0])).json()["email"] == users[0]


def test_validation_error_format(client, users, agent):
    r = client.post("/api/conversation", json={"query": ""}, headers=login(client, users[0]))
    assert r.status_code == 422 and r.json()["error"]["code"] == "VALIDATION_ERROR"


def test_conversation_flow(client, users, agent):
    h = login(client, users[0])
    r = client.post("/api/conversation", json={"query": "torque of 761A00-11300000/A?"}, headers=h)
    assert r.status_code == 200, r.text
    first = r.json()
    sid = first["session_id"]
    assert first["status"] == "answered"
    # Card from the DB; the nonexistent part number cited by the "LLM" is discarded.
    assert [p["part_number"] for p in first["products"]] == ["761A00-11300000/A"]
    card = first["products"][0]
    assert card["motor_power_w"] == 15 and card["electrical"][0] == {
        "frequency_hz": 60, "operating_speed_s": 17, "fla_a": 1.2, "lra_a": 1.4
    }

    # Follow-up without repeating the part number: resolved from the history.
    r2 = client.post("/api/conversation", json={"session_id": sid, "query": "and how much power does it draw?"}, headers=h)
    assert r2.json()["session_id"] == sid and "15 W" in r2.json()["answer"]

    # History and listing
    hist = client.get(f"/api/sessions/{sid}/messages", headers=h).json()
    assert [m["role"] for m in hist["messages"]] == ["user", "assistant", "user", "assistant"]
    assert hist["messages"][1]["products"][0]["part_number"] == "761A00-11300000/A"
    listing = client.get("/api/sessions", headers=h).json()
    assert listing["total"] == 1 and listing["items"][0]["message_count"] == 4
    assert listing["items"][0]["title"] == "torque of 761A00-11300000/A?"

    # Debug
    dbg = client.get(f"/api/messages/{first['message_id']}/debug", headers=h).json()
    assert dbg["steps"][0]["name"] == "lookup_actuator"
    assert dbg["steps"][0]["args"] == {"part_number": "761A00-11300000/A"}
    assert dbg["steps"][0]["result"]["match_type"] == "exact"
    assert dbg["usage"]["input_tokens"] == 100 and dbg["grounding"]["ok"] is True


def test_not_found_with_suggestions(client, users, agent):
    r = client.post("/api/conversation", json={"query": "761A00-11380000/A"}, headers=login(client, users[0])).json()
    assert r["status"] == "not_found" and r["products"] == []
    assert r["suggestions"][0] == "761A00-11300000/A"


def test_data_unavailable_card(client, users, agent):
    r = client.post("/api/conversation", json={"query": "761B00-11330C00/A"}, headers=login(client, users[0])).json()
    assert r["status"] == "data_unavailable"
    assert r["products"][0]["data_available"] is False and r["products"][0]["ratings"] == []


def test_grounding_correction(client, users):
    liar = FakeAgent(lie=True)
    app.dependency_overrides[get_agent] = lambda: liar
    try:
        h = login(client, users[0])
        r = client.post("/api/conversation", json={"query": "power of 762A00-11300000/A"}, headers=h).json()
        assert "99999" not in r["answer"] and "40 W" in r["answer"]
        assert liar.calls == 2  # original + correction
        dbg = client.get(f"/api/messages/{r['message_id']}/debug", headers=h).json()
        assert dbg["grounding"] == {"ok": True, "unverified_numbers": [], "retried": True}
    finally:
        app.dependency_overrides.clear()


def test_idempotency(client, users, agent):
    h = login(client, users[0])
    cid = str(uuid.uuid4())
    body = {"query": "763A00-11300000/A", "client_message_id": cid}
    r1 = client.post("/api/conversation", json=body, headers=h).json()
    calls = agent.calls
    r2 = client.post("/api/conversation", json=body, headers=h).json()
    assert r1 == r2 and agent.calls == calls  # the agent was not called again


def test_client_created_session_and_isolation(client, users, agent):
    ha, hb = login(client, users[0]), login(client, users[1])
    sid = str(uuid.uuid4())
    r = client.post("/api/conversation", json={"session_id": sid, "query": "761A"}, headers=ha)
    assert r.status_code == 200 and r.json()["session_id"] == sid  # created with the client's id
    # Another user can neither see nor continue it.
    assert client.get(f"/api/sessions/{sid}/messages", headers=hb).status_code == 404
    r = client.post("/api/conversation", json={"session_id": sid, "query": "hi"}, headers=hb)
    assert r.status_code == 404 and r.json()["error"]["code"] == "SESSION_NOT_FOUND"
    msg_id = client.get(f"/api/sessions/{sid}/messages", headers=ha).json()["messages"][1]["id"]
    assert client.get(f"/api/messages/{msg_id}/debug", headers=hb).status_code == 403  # b is not an admin
    # The admin, on b's message → 404: admins only see the traces of their own chats.
    sid_b = client.post("/api/conversation", json={"query": "761A"}, headers=hb).json()["session_id"]
    msg_b = client.get(f"/api/sessions/{sid_b}/messages", headers=hb).json()["messages"][1]["id"]
    assert client.get(f"/api/messages/{msg_b}/debug", headers=ha).status_code == 404


def test_debug_admin_only(client, users, agent):
    ha, hb = login(client, users[0]), login(client, users[1])
    assert client.get("/api/auth/me", headers=ha).json()["is_admin"] is True
    login_b = client.post("/api/auth/login", json={"email": users[1], "password": PASSWORD}).json()
    assert login_b["user"]["is_admin"] is False
    # The regular user can chat and list their history...
    r = client.post("/api/conversation", json={"query": "761A00-11300000/A"}, headers=hb).json()
    assert client.get(f"/api/sessions/{r['session_id']}/messages", headers=hb).status_code == 200
    # ...but not read the debug trace, not even of their own answer.
    d = client.get(f"/api/messages/{r['message_id']}/debug", headers=hb)
    assert d.status_code == 403 and d.json()["error"]["code"] == "ADMIN_REQUIRED"
    # Unknown message: still 403 for a regular user (no hint about which ids exist).
    assert client.get(f"/api/messages/{uuid.uuid4()}/debug", headers=hb).status_code == 403


def test_debug_disabled(client, users, agent, monkeypatch):
    monkeypatch.setattr(get_settings(), "debug_api_enabled", False)
    r = client.get(f"/api/messages/{uuid.uuid4()}/debug", headers=login(client, users[0]))
    assert r.status_code == 503 and r.json()["error"]["code"] == "DEBUG_DISABLED"


def test_delete_session(client, users, agent):
    ha, hb = login(client, users[0]), login(client, users[1])
    sid = client.post("/api/conversation", json={"query": "761A00-11300000/A"}, headers=ha).json()["session_id"]
    # Another user's session → 404 and it is not deleted.
    r = client.delete(f"/api/sessions/{sid}", headers=hb)
    assert r.status_code == 404 and r.json()["error"]["code"] == "SESSION_NOT_FOUND"
    assert client.delete(f"/api/sessions/{sid}", headers=ha).status_code == 204
    assert client.get(f"/api/sessions/{sid}/messages", headers=ha).status_code == 404
    assert sid not in [s["id"] for s in client.get("/api/sessions", headers=ha).json()["items"]]
    assert client.delete(f"/api/sessions/{sid}", headers=ha).status_code == 404  # already gone
