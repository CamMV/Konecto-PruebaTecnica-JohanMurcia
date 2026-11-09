"""API contract (request/response) for the Series 76 backend.

Human-readable documentation with examples: docs/api_contract.md. FastAPI publishes it in /docs (OpenAPI).
The fields fixed by the assessment are `session_id`, `query` (request) and `session_id`, `answer` (response).
"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_serializer, field_validator

from app.schemas.actuator import ElectricalPoint, ServiceRating

AnswerStatus = Literal[
    "answered",  # answer with data
    "clarification_needed",  # ambiguous question / several candidates / missing requirements
    "not_found",  # the part number does not exist (it may come with suggestions)
    "no_match",  # no model meets the requirements (it may come with alternatives)
    "data_unavailable",  # the model exists but the datasheet says N/A
    "out_of_scope",  # unrelated to Series 76
]


class _Out(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# ---------------------------------------------------------------- errors

class ErrorBody(BaseModel):
    code: str = Field(examples=["SESSION_NOT_FOUND"])
    message: str


class ErrorResponse(BaseModel):
    error: ErrorBody


# ---------------------------------------------------------------- auth

class LoginRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254, examples=["ana@konecto.test"])
    password: str = Field(min_length=1, max_length=200)

    @field_validator("email")
    @classmethod
    def _normalize(cls, v: str) -> str:
        return v.strip().lower()


class UserOut(_Out):
    id: UUID
    email: str
    name: str
    is_admin: bool = Field(description="Can open the debug screens (GET /api/messages/{id}/debug)")


class LoginResponse(BaseModel):
    access_token: str
    token_type: Literal["bearer"] = "bearer"
    expires_in: int = Field(description="Seconds until the token expires")
    user: UserOut


# ---------------------------------------------------------------- products

class PowerSupplyOut(BaseModel):
    voltage_v: int
    phase: Literal[1, 3] | None
    current_type: Literal["ac", "dc", "ac_dc"]
    label: str = Field(examples=["110V single phase AC"])


class ElectricalOut(ElectricalPoint):
    """Same as ElectricalPoint, but amps serialized as JSON numbers."""

    @field_serializer("fla_a", "lra_a")
    def _as_float(self, v: Decimal) -> float:
        return float(v)


class SourceOut(BaseModel):
    table: str
    page: int


class ProductCard(BaseModel):
    """Actuator data, built from the DB (never from the LLM). Same shape for every product
    so the app can compare them side by side; the unit is in each field's name."""

    part_number: str
    role: Literal["match", "alternative"] = "match"
    enclosure: Literal["weatherproof", "explosionproof"]
    certifications: list[str]
    csa_certified: bool
    power_supply: PowerSupplyOut
    motor_power_w: int | None
    data_available: bool
    ratings: list[ServiceRating]
    electrical: list[ElectricalOut]
    source: SourceOut


# ---------------------------------------------------------------- conversation

class ConversationRequest(BaseModel):
    session_id: UUID | None = Field(
        None, description="Omitted → new conversation. Unknown id → it is created with that id."
    )
    query: str = Field(min_length=1, max_length=2000)
    client_message_id: UUID | None = Field(
        None, description="Idempotency: if it was already processed, the stored answer is returned."
    )


class ConversationResponse(BaseModel):
    session_id: UUID
    message_id: UUID
    created_at: datetime
    answer: str = Field(description="Markdown text for the user")
    status: AnswerStatus
    products: list[ProductCard] = []
    suggestions: list[str] = Field([], description="Suggested part numbers (did you mean…? / options)")


# ---------------------------------------------------------------- sessions & history

class SessionSummary(_Out):
    id: UUID
    title: str
    created_at: datetime
    updated_at: datetime
    message_count: int
    last_message_preview: str | None


class SessionList(BaseModel):
    items: list[SessionSummary]
    total: int


class MessageOut(BaseModel):
    id: UUID
    role: Literal["user", "assistant"]
    content: str
    status: AnswerStatus | None = None  # only on assistant messages
    products: list[ProductCard] = []
    suggestions: list[str] = []
    created_at: datetime


class SessionMessages(BaseModel):
    session_id: UUID
    title: str
    messages: list[MessageOut]


# ---------------------------------------------------------------- debug

class DebugSessionSummary(SessionSummary):
    user: UserOut = Field(description="Owner of the conversation (the debug list groups chats by user)")


class DebugSessionList(BaseModel):
    items: list[DebugSessionSummary]
    total: int


class DebugStep(BaseModel):
    type: Literal["tool_call"] = "tool_call"
    name: str
    args: dict
    result: dict | list | str | None
    duration_ms: int | None = None
    error: str | None = None


class TokenUsage(BaseModel):
    input_tokens: int = 0
    output_tokens: int = 0


class Grounding(BaseModel):
    ok: bool
    unverified_numbers: list[str] = []
    retried: bool = False


class DebugTrace(BaseModel):
    message_id: UUID
    session_id: UUID
    model: str
    latency_ms: int
    usage: TokenUsage
    steps: list[DebugStep]
    grounding: Grounding
    structured_output: dict | None
    error: str | None = None


# ---------------------------------------------------------------- health

class Health(BaseModel):
    status: Literal["ok", "degraded"]
    db: bool
    actuators: int
