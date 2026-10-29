"""Why a session is blocked on the user.

The session status cannot answer this on its own. Runtimes publish a blocking
question and a blocking approval as the same ``waiting_approval`` status, so a
client that resends a notification whenever the status is ``waiting_approval``
cannot tell "answer me" from "approve this tool call". The runtime does know:
the open interaction notice carries ``interactionType``. This module turns the
session's open notices into that answer, so every client can label the wait the
same way without one extra connector round trip per client.

The value travels as ``session.waitReason`` on the dashboard snapshot. It is
``"input"`` or ``"approval"`` whenever a blocking interaction waits for the user,
and ``null`` otherwise. Clients that do not know the field ignore it.
"""

from __future__ import annotations

from typing import Literal

from agent_server.core.models import NoticeIn

SessionWaitKind = Literal["input", "approval"]

WAIT_KIND_FIELD = "waitReason"

# Notice statuses that still wait for the user.
OPEN_STATUSES = frozenset({"open", "responding", "response_accepted", "resolving", "failed"})

_INTERACTION_TYPES: dict[str, SessionWaitKind] = {
    "input_request": "input",
    "confirmation": "input",
    "approval": "approval",
}


def wait_kind_from_interaction_type(interaction_type: str | None) -> SessionWaitKind | None:
    if interaction_type is None:
        return None
    return _INTERACTION_TYPES.get(interaction_type)


def pending_wait_kind(notices: list[NoticeIn]) -> SessionWaitKind | None:
    """The wait kind of the open notices, or None when nothing waits.

    An input request outranks an approval when both are open, because the user
    has to answer the question before the turn can ask for an approval again.
    """
    found: SessionWaitKind | None = None
    for notice in notices:
        if notice.type != "interaction" or notice.status not in OPEN_STATUSES:
            continue
        kind = wait_kind_from_interaction_type(notice.interactionType)
        if kind == "input":
            return "input"
        if kind == "approval":
            found = "approval"
    return found
