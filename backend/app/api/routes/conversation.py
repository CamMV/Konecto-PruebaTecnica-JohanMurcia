from fastapi import APIRouter

from app.agent.service import run_turn
from app.api.deps import AgentDep, DbDep, UserDep
from app.api.schemas import ConversationRequest, ConversationResponse, ErrorResponse

router = APIRouter(tags=["conversation"])


@router.post(
    "/conversation",
    response_model=ConversationResponse,
    responses={c: {"model": ErrorResponse} for c in (401, 404, 422, 502, 503, 504)},
)
def conversation(body: ConversationRequest, db: DbDep, user: UserDep, agent: AgentDep) -> ConversationResponse:
    """Sends a query to the agent. Without `session_id` it starts a new conversation."""
    return run_turn(db, user, body, agent)
