import uuid

from fastapi import APIRouter

from app.api.deps import DbDep, UserDep
from app.api.errors import ApiError
from app.api.schemas import DebugStep, DebugTrace, ErrorResponse, Grounding, TokenUsage
from app.config import get_settings
from app.db.models import ChatMessage, ChatSession, MessageTrace

router = APIRouter(prefix="/messages", tags=["debug"])


@router.get(
    "/{message_id}/debug", response_model=DebugTrace, responses={c: {"model": ErrorResponse} for c in (404, 503)}
)
def message_debug(message_id: uuid.UUID, db: DbDep, user: UserDep) -> DebugTrace:
    """Developer-only: tools called, their parameters and the data they returned."""
    if not get_settings().debug_api_enabled:
        raise ApiError(503, "DEBUG_DISABLED", "The debug endpoint is disabled")
    msg = db.get(ChatMessage, message_id)
    session = db.get(ChatSession, msg.session_id) if msg else None
    trace = db.get(MessageTrace, message_id) if msg else None
    if msg is None or session.user_id != user.id or trace is None:
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
