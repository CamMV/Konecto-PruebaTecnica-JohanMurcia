"""Building the agent with LangChain v1 (`create_agent`, a LangGraph tool-calling loop).

    LLM ──tool_call──▶ lookup_actuator / search_actuators ──DB data──▶ LLM ──▶ … ──▶ AgentAnswer

- response_format=ToolStrategy(AgentAnswer): the final answer is a structured tool call (validated with Pydantic;
  on a validation error, the LLM gets the error and tries again).
- Middleware: limits on LLM calls and tool calls per turn (cost guard for the USD 10 key).
- No checkpointer: the history lives in our own tables (chat_message) and is passed in on every turn.
"""

from __future__ import annotations

from functools import lru_cache

from langchain.agents import create_agent
from langchain.agents.middleware import ModelCallLimitMiddleware, ToolCallLimitMiddleware
from langchain.agents.structured_output import ToolStrategy
from langchain_core.language_models import BaseChatModel

from app.agent.prompts import system_prompt
from app.agent.schemas import AgentAnswer
from app.agent.tools import TOOLS
from app.config import get_settings


def build_agent(model: BaseChatModel | None = None):
    from app.llm import get_chat_model

    s = get_settings()
    return create_agent(
        model=model or get_chat_model(),
        tools=TOOLS,
        system_prompt=system_prompt(),
        response_format=ToolStrategy(AgentAnswer),
        middleware=[
            ModelCallLimitMiddleware(run_limit=s.agent_max_model_calls, exit_behavior="end"),
            ToolCallLimitMiddleware(run_limit=s.agent_max_tool_calls, exit_behavior="continue"),
        ],
        name="series76_agent",
    )


@lru_cache
def get_agent():
    return build_agent()
