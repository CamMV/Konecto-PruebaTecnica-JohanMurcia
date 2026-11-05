"""PostgreSQL tables (SQLAlchemy 2.0). Normalized schema + `actuator_flat` view (see migration)."""

from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    ARRAY,
    Boolean,
    CheckConstraint,
    Computed,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    SmallInteger,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB, TSVECTOR, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

from app.config import get_settings

EMBEDDING_DIM = get_settings().embedding_dim


class Base(DeclarativeBase):
    pass


class SourceTableRow(Base):
    __tablename__ = "source_table"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    page: Mapped[int] = mapped_column(SmallInteger)
    title: Mapped[str] = mapped_column(Text)
    enclosure: Mapped[str] = mapped_column(Text)
    certifications: Mapped[list[str]] = mapped_column(ARRAY(Text))
    power_desc: Mapped[str] = mapped_column(Text)
    notes: Mapped[list[str]] = mapped_column(JSONB, default=list)


class ActuatorRow(Base):
    __tablename__ = "actuator"
    __table_args__ = (
        CheckConstraint("enclosure IN ('weatherproof', 'explosionproof')", name="ck_actuator_enclosure"),
        CheckConstraint("current_type IN ('ac', 'dc', 'ac_dc')", name="ck_actuator_current_type"),
        CheckConstraint("phase IS NULL OR phase IN (1, 3)", name="ck_actuator_phase"),
        # Fuzzy part-number search (approach B). Created in migration 0001.
        Index(
            "ix_actuator_pn_trgm",
            "part_number_normalized",
            postgresql_using="gin",
            postgresql_ops={"part_number_normalized": "gin_trgm_ops"},
        ),
    )

    base_part_number: Mapped[str] = mapped_column(Text, primary_key=True)
    part_number_normalized: Mapped[str] = mapped_column(Text, unique=True)
    model_code: Mapped[str] = mapped_column(Text, index=True)
    size: Mapped[int] = mapped_column(SmallInteger)
    enclosure: Mapped[str] = mapped_column(Text)
    certifications: Mapped[list[str]] = mapped_column(ARRAY(Text))
    csa_certified: Mapped[bool] = mapped_column(Boolean)
    voltage_v: Mapped[int] = mapped_column(SmallInteger)
    phase: Mapped[int | None] = mapped_column(SmallInteger)
    current_type: Mapped[str] = mapped_column(Text)
    motor_power_w: Mapped[int | None] = mapped_column(SmallInteger)
    data_available: Mapped[bool] = mapped_column(Boolean)
    source_table_id: Mapped[str] = mapped_column(ForeignKey("source_table.id"))
    source_row: Mapped[int] = mapped_column(SmallInteger)

    ratings: Mapped[list[RatingRow]] = relationship(cascade="all, delete-orphan")
    electrical: Mapped[list[ElectricalRow]] = relationship(cascade="all, delete-orphan")


class RatingRow(Base):
    __tablename__ = "actuator_rating"
    __table_args__ = (
        UniqueConstraint("base_part_number", "service_type"),
        CheckConstraint("service_type IN ('on_off', 'modulating', 'general')", name="ck_rating_service"),
        CheckConstraint("duty_cycle_pct > 0 AND duty_cycle_pct <= 100", name="ck_rating_duty"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    base_part_number: Mapped[str] = mapped_column(
        ForeignKey("actuator.base_part_number", ondelete="CASCADE"), index=True
    )
    service_type: Mapped[str] = mapped_column(Text)
    torque_inlb: Mapped[int] = mapped_column(Integer)
    torque_nm: Mapped[int] = mapped_column(Integer)
    duty_cycle_pct: Mapped[int] = mapped_column(SmallInteger)
    cycles_per_hour: Mapped[int | None] = mapped_column(SmallInteger)
    starts_per_hour: Mapped[int | None] = mapped_column(SmallInteger)


class ElectricalRow(Base):
    __tablename__ = "actuator_electrical"
    __table_args__ = (
        UniqueConstraint(
            "base_part_number", "frequency_hz", postgresql_nulls_not_distinct=True
        ),
        CheckConstraint("frequency_hz IS NULL OR frequency_hz IN (50, 60)", name="ck_elec_freq"),
        CheckConstraint("lra_a >= fla_a", name="ck_elec_lra_ge_fla"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    base_part_number: Mapped[str] = mapped_column(
        ForeignKey("actuator.base_part_number", ondelete="CASCADE"), index=True
    )
    frequency_hz: Mapped[int | None] = mapped_column(SmallInteger)
    operating_speed_s: Mapped[int] = mapped_column(SmallInteger)
    fla_a: Mapped[Decimal] = mapped_column(Numeric(4, 1))
    lra_a: Mapped[Decimal] = mapped_column(Numeric(4, 1))


class SearchDocument(Base):
    """Retrieval documents (approach A: hybrid lexical + dense)."""

    __tablename__ = "search_document"
    __table_args__ = (
        CheckConstraint("doc_type IN ('actuator', 'context')", name="ck_doc_type"),
        Index("ix_search_document_tsv", "tsv", postgresql_using="gin"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    base_part_number: Mapped[str | None] = mapped_column(
        ForeignKey("actuator.base_part_number", ondelete="CASCADE"), index=True
    )
    doc_type: Mapped[str] = mapped_column(Text)
    title: Mapped[str] = mapped_column(Text)
    content: Mapped[str] = mapped_column(Text)
    # Lexical text: content + ID variants, already normalized by the pipeline.
    lexical_text: Mapped[str] = mapped_column(Text)
    tsv: Mapped[str] = mapped_column(
        TSVECTOR, Computed("to_tsvector('simple', lexical_text)", persisted=True)
    )
    embedding: Mapped[list[float] | None] = mapped_column(Vector(EMBEDDING_DIM))
    embedding_model: Mapped[str | None] = mapped_column(Text)


# ---------------------------------------------------------------- users & conversations

class AppUser(Base):
    __tablename__ = "app_user"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(Text, unique=True)
    name: Mapped[str] = mapped_column(Text)
    password_hash: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class ChatSession(Base):
    __tablename__ = "chat_session"
    __table_args__ = (Index("ix_chat_session_user_updated", "user_id", "updated_at"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("app_user.id", ondelete="CASCADE"))
    title: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    messages: Mapped[list[ChatMessage]] = relationship(
        back_populates="session", cascade="all, delete-orphan", order_by="ChatMessage.created_at"
    )


class ChatMessage(Base):
    __tablename__ = "chat_message"
    __table_args__ = (
        CheckConstraint("role IN ('user', 'assistant')", name="ck_message_role"),
        Index("ix_chat_message_session_created", "session_id", "created_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    session_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("chat_session.id", ondelete="CASCADE"))
    role: Mapped[str] = mapped_column(Text)
    content: Mapped[str] = mapped_column(Text)
    status: Mapped[str | None] = mapped_column(Text)
    part_numbers: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list)
    products: Mapped[list[dict]] = mapped_column(JSONB, default=list)
    suggestions: Mapped[list[str]] = mapped_column(ARRAY(Text), default=list)
    # Idempotency: set on the user message; the answer is looked up by reply_to.
    client_message_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), unique=True)
    reply_to: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("chat_message.id", ondelete="SET NULL"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    session: Mapped[ChatSession] = relationship(back_populates="messages")


class MessageTrace(Base):
    """Agent trace per assistant answer (debug screen)."""

    __tablename__ = "message_trace"

    message_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("chat_message.id", ondelete="CASCADE"), primary_key=True
    )
    model: Mapped[str] = mapped_column(Text)
    steps: Mapped[list[dict]] = mapped_column(JSONB, default=list)
    structured_output: Mapped[dict | None] = mapped_column(JSONB)
    grounding: Mapped[dict] = mapped_column(JSONB, default=dict)
    input_tokens: Mapped[int] = mapped_column(Integer, default=0)
    output_tokens: Mapped[int] = mapped_column(Integer, default=0)
    latency_ms: Mapped[int] = mapped_column(Integer, default=0)
    error: Mapped[str | None] = mapped_column(Text)
