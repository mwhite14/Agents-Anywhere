"""NUL bytes in timeline payloads must not break readers.

Tool output is stored verbatim, so a binary file read with ``cat`` can carry
U+0000. PostgreSQL cannot produce text from a ``json`` value that contains the
NUL escape (22P05), and extracting a single field re-serializes the document, so
one such row used to abort the whole session list, the dashboard snapshot and
the session detail routes.
"""

from __future__ import annotations

import asyncio
import json

import asyncpg

from conftest import TEST_DATABASE_URL, _asyncpg_url
from test_backend_mvp import create_connector_and_session, make_client

from agent_server.core.models import TimelineItemIn
from agent_server.infra.timeline_store import _json_dumps


def _item(session_id: str, item_id: str, item_time: str) -> TimelineItemIn:
    return TimelineItemIn.model_validate(
        {
            "id": item_id,
            "sessionId": session_id,
            "type": "message",
            "status": "done",
            "role": "assistant",
            "content": {"text": "hello", "format": "markdown"},
            "source": {
                "runtime": "codex",
                "sessionId": "thread_1",
                "itemId": item_id,
            },
            "orderSeq": 1,
            "revision": 1,
            "contentHash": f"sha256:{item_id}",
            "createdAt": item_time,
            "updatedAt": item_time,
        }
    )


def test_json_dumps_drops_nul_from_nested_payloads() -> None:
    encoded = _json_dumps(
        {
            "type": "tool",
            "content": {"stdout": "before\x00after", "lines": ["a\x00b", 3, None]},
        }
    )

    assert "\\u0000" not in encoded
    decoded = json.loads(encoded)
    assert decoded["content"]["stdout"] == "beforeafter"
    assert decoded["content"]["lines"] == ["ab", 3, None]


def test_session_list_survives_a_legacy_payload_with_a_nul_escape(tmp_path) -> None:
    with make_client(tmp_path) as client:
        _, _, session_id, headers = create_connector_and_session(client)
        store = client.app.state.store

        async def seed() -> None:
            await store.sync_timeline_items(
                session_id=session_id,
                items=[_item(session_id, "tl_nul_1", "2026-11-12T10:00:00Z")],
            )

        asyncio.run(seed())

        async def write_legacy_row() -> None:
            conn = await asyncpg.connect(_asyncpg_url(TEST_DATABASE_URL))
            try:
                updated = await conn.execute(
                    "UPDATE timeline_items "
                    "SET payload_json = replace(payload_json, '\"markdown\"', '\"markdown\\u0000\"') "
                    "WHERE session_id = $1",
                    session_id,
                )
            finally:
                await conn.close()
            assert updated.endswith("1"), updated

        asyncio.run(write_legacy_row())

        listed = client.get("/sessions/list", headers=headers)
        assert listed.status_code == 200, listed.text
        assert session_id in {row["id"] for row in listed.json()["sessions"]}

        detail = client.get(f"/sessions/{session_id}/snapshot?limit=100", headers=headers)
        assert detail.status_code == 200, detail.text
