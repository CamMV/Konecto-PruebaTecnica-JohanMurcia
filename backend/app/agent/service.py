"""A conversation turn: idempotency → session → history → agent → post-processing → persistence.

Post-processing (deterministic, no LLM):
  - product cards: only part numbers that the AGENT cited AND that came out of the DB in this turn;
    the data is read from the DB (products.build_cards).
  - numeric grounding: if the answer has numbers that aren't in the data, it is corrected once.
  - suggestions: only part numbers that the tools returned.
"""

from __future__ import annotations

import logging
import re
import time
import uuid
from datetime import UTC, datetime
from typing import Any

import openai
from langchain_core.messages import BaseMessage, HumanMessage
from langgraph.errors import GraphRecursionError
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.agent import grounding
from app.agent.history import current_message, load_history, strip_context_leak
from app.agent.products import build_cards
from app.agent.schemas import AgentAnswer
from app.agent.tracing import RunTrace, extract
from app.api.errors import ApiError
from app.api.schemas import ConversationRequest, ConversationResponse, ProductCard
from app.config import get_settings
from app.db.models import AppUser, ChatMessage, ChatSession, MessageTrace

log = logging.getLogger(__name__)

TITLE_MAX = 60
CORRECTION = (
    "Automatic check: these numbers in your answer do not appear in the data returned in this turn: {nums}. "
    "Rewrite the answer using only values exactly as they appear in the results (call the lookup again if you "
    "need data). Do not compute or convert values. Then give the final answer again."
)
FALLBACK = AgentAnswer(
    answer="I couldn't complete this request. Could you rephrase it or add more details "
    "(part number, supply voltage, enclosure, required torque)?",
    status="clarification_needed",
)


def _now() -> datetime:
    return datetime.now(UTC)


def _title(query: str) -> str:
    q = re.sub(r"\s+", " ", query).strip()
    return q if len(q) <= TITLE_MAX else q[: TITLE_MAX - 1].rstrip() + "…"


def to_response(reply: ChatMessage) -> ConversationResponse:
    return ConversationResponse(
        session_id=reply.session_id,
        message_id=reply.id,
        created_at=reply.created_at,
        answer=reply.content,
        status=reply.status,
        products=[ProductCard.model_validate(p) for p in reply.products or []],
        suggestions=reply.suggestions or [],
    )


def _get_or_create_session(db: Session, user: AppUser, req: ConversationRequest) -> ChatSession:
    if req.session_id is not None:
        session = db.get(ChatSession, req.session_id)
        if session is not None:
            if session.user_id != user.id:
                raise ApiError(404, "SESSION_NOT_FOUND", "Session not found")
            return session
    session = ChatSession(
        id=req.session_id or uuid.uuid4(), user_id=user.id, title=_title(req.query), created_at=_now(), updated_at=_now()
    )
    db.add(session)
    return session


def _invoke(agent: Any, messages: list[BaseMessage]) -> dict:
    try:
        return agent.invoke({"messages": messages}, config={"recursion_limit": 30})
    except GraphRecursionError:
        log.warning("agent recursion limit reached")
        return {"messages": messages, "structured_response": None}
    except openai.APITimeoutError as exc:
        raise ApiError(504, "LLM_TIMEOUT", "The language model did not respond in time") from exc
    except openai.APIError as exc:
        log.exception("LLM error")
        raise ApiError(502, "LLM_UNAVAILABLE", "The language model is not available") from exc


def run_turn(db: Session, user: AppUser, req: ConversationRequest, agent: Any) -> ConversationResponse:
    s = get_settings()

    # 2. Idempotency
    user_msg: ChatMessage | None = None
    if req.client_message_id is not None:
        user_msg = db.scalar(select(ChatMessage).where(ChatMessage.client_message_id == req.client_message_id))
        if user_msg is not None:
            session = db.get(ChatSession, user_msg.session_id)
            if session.user_id != user.id:
                raise ApiError(404, "SESSION_NOT_FOUND", "Session not found")
            reply = db.scalar(select(ChatMessage).where(ChatMessage.reply_to == user_msg.id))
            if reply is not None:
                return to_response(reply)  # already answered: the LLM is not called again

    # 3. Session + user message (persisted even if the agent fails afterwards)
    if user_msg is None:
        session = _get_or_create_session(db, user, req)
        user_msg = ChatMessage(
            session_id=session.id,
            role="user",
            content=req.query,
            client_message_id=req.client_message_id,
            created_at=_now(),
        )
        db.add(user_msg)
        session.updated_at = _now()
        db.commit()

    # 4. History (previous turns that have an answer)
    history = load_history(db, session.id, s.history_turns)
    messages = history.messages + [current_message(user_msg.content, history)]

    # 5. Agent
    t0 = time.perf_counter()
    result = _invoke(agent, messages)
    trace: RunTrace = extract(result["messages"][len(messages):])
    answer: AgentAnswer | None = result.get("structured_response")
    error = None

    # 6b. Numeric grounding (one correction)
    def unverified_numbers(a: AgentAnswer) -> list[str]:
        return grounding.check(a.answer, [user_msg.content, [m.content for m in history.messages], trace.artifacts])

    unverified = unverified_numbers(answer) if answer else []
    retried = False
    if answer and unverified:
        retried = True
        retry_msgs = result["messages"] + [HumanMessage(CORRECTION.format(nums=", ".join(unverified)))]
        result2 = _invoke(agent, retry_msgs)
        trace.extend(extract(result2["messages"][len(retry_msgs):]))
        if result2.get("structured_response") is not None:
            answer = result2["structured_response"]
        unverified = unverified_numbers(answer)
        if unverified:
            log.warning("answer with unverified numbers: %s", unverified)

    if answer is None:
        error = "the agent did not produce a final answer (call limit reached)"
        answer = FALLBACK
    answer = answer.model_copy(update={"answer": strip_context_leak(answer.answer)})

    # 6a/c. Cards and suggestions: only what came out of the DB in this turn
    seen = trace.parts_seen
    main = [p for p in dict.fromkeys(answer.part_numbers) if p in seen]
    alts = [p for p in dict.fromkeys(answer.alternatives) if p in seen and p not in main]
    if answer.status == "no_match":  # nothing meets the requirements: whatever is shown is an alternative
        main, alts = [], list(dict.fromkeys(main + alts))
    products = build_cards(db, main) + build_cards(db, alts, role="alternative")
    suggestions = [p for p in dict.fromkeys(answer.suggestions) if p in seen and p not in main]

    # 7. Persist the answer + trace
    reply = ChatMessage(
        session_id=session.id,
        role="assistant",
        content=answer.answer,
        status=answer.status,
        part_numbers=main + alts,
        products=[p.model_dump(mode="json") for p in products],
        suggestions=suggestions,
        reply_to=user_msg.id,
        created_at=_now(),
    )
    db.add(reply)
    db.flush()
    db.add(
        MessageTrace(
            message_id=reply.id,
            model=s.agent_model,
            steps=trace.steps,
            structured_output=answer.model_dump(mode="json"),
            grounding={"ok": not unverified, "unverified_numbers": unverified, "retried": retried},
            input_tokens=trace.input_tokens,
            output_tokens=trace.output_tokens,
            latency_ms=round((time.perf_counter() - t0) * 1000),
            error=error,
        )
    )
    session.updated_at = _now()
    db.commit()
    return to_response(reply)
