"""The dashboard wait kind tells a question apart from an approval.

The session status merges both into ``waiting_approval``, so these tests pin the
only evidence a client can use: the open interaction's ``interactionType``.
"""

from __future__ import annotations

import asyncio
from typing import Any

from agent_server.core.models import NoticeIn
from agent_server.services.session_wait_kind import (
    WAIT_KIND_FIELD,
    pending_wait_kind,
    wait_kind_from_interaction_type,
)


def notice(
    *,
    kind: str = "interaction",
    interaction_type: str | None = "input_request",
    status: str = "open",
    notice_id: str = "n1",
) -> NoticeIn:
    return NoticeIn.model_validate(
        {
            "noticeId": notice_id,
            "sessionId": "session",
            "type": kind,
            "title": "title",
            "status": status,
            "interactionType": interaction_type,
        }
    )


def test_interaction_types_map_to_wait_kinds() -> None:
    assert wait_kind_from_interaction_type("input_request") == "input"
    assert wait_kind_from_interaction_type("confirmation") == "input"
    assert wait_kind_from_interaction_type("approval") == "approval"
    assert wait_kind_from_interaction_type("execution_error") is None
    assert wait_kind_from_interaction_type("unknown") is None
    assert wait_kind_from_interaction_type(None) is None


def test_open_input_request_is_an_input_wait() -> None:
    assert pending_wait_kind([notice()]) == "input"


def test_open_approval_is_an_approval_wait() -> None:
    assert pending_wait_kind([notice(interaction_type="approval")]) == "approval"


def test_resolved_notice_no_longer_waits() -> None:
    assert pending_wait_kind([notice(status="resolved")]) is None
    assert pending_wait_kind([notice(interaction_type="approval", status="cancelled")]) is None


def test_input_request_outranks_a_parallel_approval() -> None:
    notices = [
        notice(interaction_type="approval", notice_id="a"),
        notice(interaction_type="input_request", notice_id="q"),
    ]
    assert pending_wait_kind(notices) == "input"
    assert pending_wait_kind(list(reversed(notices))) == "input"


def test_plain_notifications_do_not_count_as_a_wait() -> None:
    assert pending_wait_kind([notice(kind="notification")]) is None


def test_dashboard_snapshot_labels_a_blocked_session(monkeypatch) -> None:
    from agent_server.api import dashboard_stream

    class Session:
        id = "session"

        def __init__(self, status: str) -> None:
            self.status = status

        def model_dump(self, mode: str = "python") -> dict[str, Any]:
            assert mode == "json"
            return {"id": self.id, "status": self.status}

    async def fake_notices(manager: Any, session: Any) -> list[NoticeIn]:
        return [notice()]

    monkeypatch.setattr(dashboard_stream, "read_session_notices_for_snapshot", fake_notices)

    rows = asyncio.run(
        dashboard_stream._dashboard_sessions(
            None,  # type: ignore[arg-type]
            [Session("waiting_approval"), Session("running")],
        )
    )

    assert rows[0][WAIT_KIND_FIELD] == "input"
    assert WAIT_KIND_FIELD not in rows[1]


def test_dashboard_snapshot_survives_a_runtime_without_notices(monkeypatch) -> None:
    from agent_server.api import dashboard_stream

    class Session:
        id = "session"
        status = "waiting_approval"

        def model_dump(self, mode: str = "python") -> dict[str, Any]:
            return {"id": self.id, "status": self.status}

    async def failing_notices(manager: Any, session: Any) -> list[NoticeIn]:
        raise RuntimeError("connector rpc unavailable")

    monkeypatch.setattr(dashboard_stream, "read_session_notices_for_snapshot", failing_notices)

    rows = asyncio.run(
        dashboard_stream._dashboard_sessions(None, [Session()])  # type: ignore[arg-type]
    )

    assert rows == [{"id": "session", "status": "waiting_approval"}]
