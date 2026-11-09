"""Admin-only debug endpoints (403 otherwise; 503 if DEBUG_API_ENABLED=false).

Admins see every user's conversations, so the debug screen can inspect any answer, grouped by user.
"""

import uuid

from fastapi import APIRouter, Query
from sqlalchemy import select

from app.api.deps import AdminDep, DbDep
from app.api.errors import ApiError
from app.api.routes.sessions import session_messages_out, session_summaries
from app.api.schemas import (
    DebugSessionList,
    DebugSessionSummary,
    DebugStep,
    DebugTrace,
    ErrorResponse,
    Grounding,
    SessionMessages,
    TokenUsage,
    UserOut,
)
from app.config import get_settings
from app.db.models import AppUser, ChatMessage, ChatSession, MessageTrace

router = APIRouter(tags=["debug"])
_ERRORS = {c: {"model": ErrorResponse} for c in (403, 404, 503)}


def _ensure_enabled() -> None:
    if not get_settings().debug_api_enabled:
        raise ApiError(503, "DEBUG_DISABLED", "The debug endpoint is disabled")


@router.get("/debug/sessions", response_model=DebugSessionList, responses=_ERRORS)
def debug_sessions(
    db: DbDep, user: AdminDep, limit: int = Query(100, ge=1, le=500), offset: int = Query(0, ge=0)
) -> DebugSessionList:
    """Every user's conversations, newest first, each with its owner."""
    _ensure_enabled()
    items, total = session_summaries(db, None, limit, offset)
    owner_ids = {s.user_id for s, _ in items}
    owners = {u.id: u for u in db.scalars(select(AppUser).where(AppUser.id.in_(owner_ids)))} if owner_ids else {}
    return DebugSessionList(
        items=[
            DebugSessionSummary(**summary.model_dump(), user=UserOut.model_validate(owners[s.user_id]))
            for s, summary in items
        ],
        total=total,
    )


@router.get("/debug/sessions/{session_id}/messages", response_model=SessionMessages, responses=_ERRORS)
def debug_session_messages(session_id: uuid.UUID, db: DbDep, user: AdminDep) -> SessionMessages:
    """History of any user's conversation (same shape as GET /api/sessions/{id}/messages)."""
    _ensure_enabled()
    session = db.get(ChatSession, session_id)
    if session is None:
        raise ApiError(404, "SESSION_NOT_FOUND", "Session not found")
    return session_messages_out(session)


@router.get("/messages/{message_id}/debug", response_model=DebugTrace, responses=_ERRORS)
def message_debug(message_id: uuid.UUID, db: DbDep, user: AdminDep) -> DebugTrace:
    """Tools called, their parameters and the data they returned, for an answer of any user."""
    _ensure_enabled()
    msg = db.get(ChatMessage, message_id)
    trace = db.get(MessageTrace, message_id) if msg else None
    if msg is None or trace is None:
        raise ApiError(404, "MESSAGE_NOT_FOUND", "Message not found or it has no trace")
    return DebugTrace(
        message_id=msg.id,
        session_id=msg.session_id,
        model=trace.model,
        latency_ms=trace.latency_ms,
        usage=TokenUsage(input_tokens=trace.input_tokens, output_tokens=trace.output_tokens),
        steps=[DebugStep(**s) for s in trace.steps],
        grounding=Grounding(**trace.grounding),
        structured_output=trace.structured_output,
        error=trace.error,
    )
