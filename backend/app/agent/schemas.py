"""The agent's structured final output (response_format of create_agent)."""

from __future__ import annotations

from pydantic import BaseModel, Field

from app.api.schemas import AnswerStatus


class AgentAnswer(BaseModel):
    """Final answer to the user. Call this ONCE, after you have the data from the tools."""

    answer: str = Field(
        description=(
            "Answer for the user in Markdown, in the user's language. Short and direct. Every number must be "
            "copied exactly from the tool results, with its unit. Never mention tools, functions, databases or "
            "internal field names."
        )
    )
    status: AnswerStatus = Field(
        description=(
            "answered | clarification_needed (ambiguous, several candidates, or missing requirements) | "
            "not_found (the part number does not exist) | no_match (no model meets the requirements) | "
            "data_unavailable (the model exists but has no data, data_available=false) | out_of_scope"
        )
    )
    part_numbers: list[str] = Field(
        default_factory=list,
        description="Base part numbers whose data you used in the answer (main results), in display order, exactly as returned by the tools.",
    )
    alternatives: list[str] = Field(
        default_factory=list,
        description="Part numbers offered as alternatives when nothing meets every requirement (status no_match).",
    )
    suggestions: list[str] = Field(
        default_factory=list,
        description="Part numbers offered as 'did you mean…?' (not_found) or as options to choose from (clarification_needed).",
    )
