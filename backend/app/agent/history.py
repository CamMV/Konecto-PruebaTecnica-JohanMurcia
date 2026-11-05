"""Conversation history (chat_message) → LangChain messages for the agent.

- Previous turns are sent as plain (Human, AI) pairs (only those that have an answer).
- Earlier tool results are NOT replayed. Instead, the current turn's message carries a
  <conversation_context> block with the parts referenced in recent answers, so follow-ups
  ("and its power?") resolve the part and query the DB again (fresh numbers).
  It goes in the current message (not in the earlier answers) so the model doesn't copy it into its answer.
"""

from __future__ import annotations

import re
import uuid
from dataclasses import dataclass, field

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage
from sqlalchemy import select
from sqlalchemy.orm import Session, aliased

from app.db.models import ChatMessage

CONTEXT_LEAK_RE = re.compile(r"\n*\[(?:Referenced|Suggested) parts:[^\]]*\]\s*", re.I)


@dataclass
class History:
    messages: list[BaseMessage] = field(default_factory=list)
    referenced_parts: list[str] = field(default_factory=list)  # most recent last
    suggested_parts: list[str] = field(default_factory=list)  # from the last answer only


def load_history(db: Session, session_id: uuid.UUID, turns: int) -> History:
    answer = aliased(ChatMessage)
    question = aliased(ChatMessage)
    rows = db.execute(
        select(question.content, answer.content, answer.part_numbers, answer.suggestions)
        .join(question, answer.reply_to == question.id)
        .where(answer.session_id == session_id, answer.role == "assistant")
        .order_by(answer.created_at.desc())
        .limit(turns)
    ).all()
    h = History()
    for q, a, parts, _ in reversed(rows):
        h.messages += [HumanMessage(q), AIMessage(a)]
        for p in parts or []:
            if p in h.referenced_parts:
                h.referenced_parts.remove(p)
            h.referenced_parts.append(p)
    if rows:
        h.suggested_parts = list(rows[0].suggestions or [])
    return h


def current_message(query: str, history: History) -> HumanMessage:
    if not (history.referenced_parts or history.suggested_parts):
        return HumanMessage(query)
    ctx = []
    if history.referenced_parts:
        ctx.append(f"Parts referenced in previous answers (most recent last): {', '.join(history.referenced_parts[-5:])}")
    if history.suggested_parts:
        ctx.append(f"Parts suggested in the last answer: {', '.join(history.suggested_parts)}")
    return HumanMessage(
        "<conversation_context>\n" + "\n".join(ctx) + "\n</conversation_context>\n"
        f"<user_message>\n{query}\n</user_message>"
    )


def strip_context_leak(answer: str) -> str:
    return CONTEXT_LEAK_RE.sub("\n", answer).strip()
