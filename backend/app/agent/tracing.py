"""Extracts the trace of an agent run (tool calls ↔ results, tokens) from its messages."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from langchain_core.messages import AIMessage, BaseMessage, ToolMessage

from app.agent.schemas import AgentAnswer

STRUCTURED_TOOL = AgentAnswer.__name__


@dataclass
class RunTrace:
    steps: list[dict[str, Any]] = field(default_factory=list)
    artifacts: list[dict[str, Any]] = field(default_factory=list)  # successful tool results
    input_tokens: int = 0
    output_tokens: int = 0

    @property
    def parts_seen(self) -> set[str]:
        """Part numbers that came out of the DB in this run."""
        return {
            r["base_part_number"]
            for a in self.artifacts
            for r in a.get("results", [])
            if isinstance(r, dict) and "base_part_number" in r
        }

    def extend(self, other: RunTrace) -> None:
        self.steps += other.steps
        self.artifacts += other.artifacts
        self.input_tokens += other.input_tokens
        self.output_tokens += other.output_tokens


def _result(msg: ToolMessage) -> Any:
    if isinstance(msg.artifact, dict):
        return {k: v for k, v in msg.artifact.items() if k != "duration_ms"}
    try:
        return json.loads(msg.content) if isinstance(msg.content, str) else msg.content
    except json.JSONDecodeError:
        return msg.content


def extract(messages: list[BaseMessage]) -> RunTrace:
    trace = RunTrace()
    tool_msgs = {m.tool_call_id: m for m in messages if isinstance(m, ToolMessage)}
    for m in messages:
        if not isinstance(m, AIMessage):
            continue
        usage = m.usage_metadata or {}
        trace.input_tokens += usage.get("input_tokens", 0)
        trace.output_tokens += usage.get("output_tokens", 0)
        for call in m.tool_calls:
            if call["name"] == STRUCTURED_TOOL:
                continue
            tm = tool_msgs.get(call["id"])
            error = None
            if tm is None:
                error = "no result (tool call limit reached?)"
            elif tm.status == "error":
                error = str(tm.content)
            artifact = tm.artifact if tm is not None and isinstance(tm.artifact, dict) else None
            trace.steps.append(
                {
                    "type": "tool_call",
                    "name": call["name"],
                    "args": call["args"],
                    "result": _result(tm) if tm is not None and error is None else None,
                    "duration_ms": artifact.get("duration_ms") if artifact else None,
                    "error": error,
                }
            )
            if artifact and error is None:
                trace.artifacts.append(artifact)
    return trace
