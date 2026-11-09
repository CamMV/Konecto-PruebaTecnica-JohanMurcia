import uuid

from fastapi import APIRouter, Query, Response
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from app.api.deps import DbDep, UserDep
from app.api.errors import ApiError
from app.api.schemas import ErrorResponse, MessageOut, ProductCard, SessionList, SessionMessages, SessionSummary
from app.db.models import ChatMessage, ChatSession

router = APIRouter(prefix="/sessions", tags=["sessions"])
PREVIEW_LEN = 120


def session_summaries(
    db: Session, user_id: uuid.UUID | None, limit: int, offset: int
) -> tuple[list[tuple[ChatSession, SessionSummary]], int]:
    """Sessions newest first, with message count and last-message preview. `user_id=None` = every user (admin debug)."""
    count = (
        select(func.count(ChatMessage.id)).where(ChatMessage.session_id == ChatSession.id).scalar_subquery()
    )
    last = (
        select(ChatMessage.content)
        .where(ChatMessage.session_id == ChatSession.id)
        .order_by(ChatMessage.created_at.desc())
        .limit(1)
        .scalar_subquery()
    )
    query = select(ChatSession, count, last)
    total_q = select(func.count()).select_from(ChatSession)
    if user_id is not None:
        query = query.where(ChatSession.user_id == user_id)
        total_q = total_q.where(ChatSession.user_id == user_id)
    rows = db.execute(query.order_by(ChatSession.updated_at.desc()).limit(limit).offset(offset)).all()
    items = [
        (
            s,
            SessionSummary(
                id=s.id,
                title=s.title,
                created_at=s.created_at,
                updated_at=s.updated_at,
                message_count=n,
                last_message_preview=(p[:PREVIEW_LEN] + "…" if p and len(p) > PREVIEW_LEN else p),
            ),
        )
        for s, n, p in rows
    ]
    return items, db.scalar(total_q)


def session_messages_out(session: ChatSession) -> SessionMessages:
    return SessionMessages(
        session_id=session.id,
        title=session.title,
        messages=[
            MessageOut(
                id=m.id,
                role=m.role,
                content=m.content,
                status=m.status,
                products=[ProductCard.model_validate(p) for p in m.products or []],
                suggestions=m.suggestions or [],
                created_at=m.created_at,
            )
            for m in session.messages
        ],
    )


@router.get("", response_model=SessionList)
def list_sessions(
    db: DbDep, user: UserDep, limit: int = Query(20, ge=1, le=100), offset: int = Query(0, ge=0)
) -> SessionList:
    items, total = session_summaries(db, user.id, limit, offset)
    return SessionList(items=[summary for _, summary in items], total=total)


@router.get("/{session_id}/messages", response_model=SessionMessages, responses={404: {"model": ErrorResponse}})
def session_messages(session_id: uuid.UUID, db: DbDep, user: UserDep) -> SessionMessages:
    session = db.get(ChatSession, session_id)
    if session is None or session.user_id != user.id:
        raise ApiError(404, "SESSION_NOT_FOUND", "Session not found")
    return session_messages_out(session)


@router.delete("/{session_id}", status_code=204, responses={404: {"model": ErrorResponse}})
def delete_session(session_id: uuid.UUID, db: DbDep, user: UserDep) -> Response:
    """Deletes the conversation with its messages and traces (DB cascades)."""
    session = db.get(ChatSession, session_id)
    if session is None or session.user_id != user.id:
        raise ApiError(404, "SESSION_NOT_FOUND", "Session not found")
    db.execute(delete(ChatSession).where(ChatSession.id == session_id))
    db.commit()
    return Response(status_code=204)
