from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import pytest
from conftest import ApiV2TestClient as TestClient
from sqlalchemy import select

from agent_server.app import create_app
from agent_server.infra.db import sessions


@dataclass
class ArchiveClient:
    client: TestClient
    headers: dict[str, str]
    connector_headers: dict[str, str]
    connector_id: str
    tick: int = 0

    def ingest(self, *notifications):
        response = self.client.post(
            "/connector/ingest",
            headers=self.connector_headers,
            json={"notifications": list(notifications)},
        )
        assert response.status_code == 200, response.text
        assert response.json()["accepted"] == len(notifications), response.text

    def sync(self, session_id, runtime, state, *, mode="meta", cwd="/repo"):
        self.tick += 1
        observed_at = (
            datetime(2026, 9, 6, tzinfo=timezone.utc) + timedelta(seconds=self.tick)
        ).isoformat()
        source = {
            "availability": state,
            "observedAt": observed_at,
            "observationOrigin": "event" if mode == "event" else "inventory",
        }
        identity = {
            "sessionId": session_id,
            "runtime": runtime,
            "externalSessionId": f"external_{session_id}",
        }
        if mode == "meta":
            self.ingest(
                {
                    "method": "session.meta.upsert",
                    "params": {
                        **identity,
                        "title": session_id,
                        "cwd": cwd,
                        "sourceState": source,
                    },
                }
            )
        elif mode == "event":
            self.ingest(
                {"method": "session.source.updated", "params": {**identity, **source}}
            )
        else:
            scan = f"archive-inventory-{self.tick}"
            self.ingest(
                {
                    "method": "session.inventory.begin",
                    "params": {"runtime": runtime, "scanToken": scan},
                },
                {
                    "method": "session.inventory.complete",
                    "params": {
                        "runtime": runtime,
                        "scanToken": scan,
                        "complete": False,
                        "sessions": [{**identity, "sourceState": source}],
                    },
                },
            )

    def session(self, session_id):
        response = self.client.get(f"/sessions/{session_id}/meta", headers=self.headers)
        assert response.status_code == 200, response.text
        return response.json()["session"]

    def listed_ids(self, *, archived):
        response = self.client.get(
            "/sessions",
            headers=self.headers,
            params={"archived": archived, "limit": 100},
        )
        assert response.status_code == 200, response.text
        return {session["id"] for session in response.json()["sessions"]}

    def unarchive(self, session_id):
        response = self.client.post(
            "/sessions/unarchive", headers=self.headers, json=[session_id]
        )
        assert response.status_code == 200, response.text
        assert response.json()["sessions"][0]["archived"] is False

    def stored_state(self, session_id):
        async def read():
            async with self.client.app.state.store.engine.connect() as connection:
                row = (
                    await connection.execute(
                        select(sessions.c.archived, sessions.c.source_state).where(
                            sessions.c.id == session_id
                        )
                    )
                ).one()
                return row.archived, row.source_state

        return asyncio.run(read())


@pytest.fixture
def archive_client(tmp_path):
    client = TestClient(create_app())
    client.get("/auth/config")
    registered = client.post(
        "/auth/register",
        json={
            "email": "archive-owner@example.com",
            "displayName": "Archive Owner",
            "password": "secret",
            "setupToken": client.app.state.setup_token.peek(),
        },
    )
    assert registered.status_code == 200, registered.text
    headers = {"Authorization": f"Bearer {registered.json()['accessToken']}"}
    connector_response = client.post(
        "/connectors", headers=headers, json={"name": "Device"}
    )
    assert connector_response.status_code == 200, connector_response.text
    connector = connector_response.json()
    connector_id = connector["connector"]["id"]
    authenticated = client.post(
        "/connector/auth",
        headers={
            "Authorization": f"Connector {connector_id}:{connector['connectorToken']}"
        },
    )
    assert authenticated.status_code == 200, authenticated.text
    try:
        yield ArchiveClient(
            client,
            headers,
            {"Authorization": f"Bearer {authenticated.json()['accessToken']}"},
            connector_id,
        )
    finally:
        client.close()
        asyncio.run(client.app.state.store.close())


@pytest.mark.parametrize("runtime", ["codex", "claude", "dsh"])
@pytest.mark.parametrize("mode", ["meta", "event", "inventory"])
def test_aa_unarchive_survives_sync_until_a_new_agent_archive(
    archive_client, runtime, mode
):
    api = archive_client
    session_id = f"session_{runtime}"
    api.sync(session_id, runtime, "archived")
    assert api.stored_state(session_id) == (1, "archived")
    assert session_id in api.listed_ids(archived=True)

    api.unarchive(session_id)
    for _ in range(2):
        api.sync(session_id, runtime, "archived", mode=mode)
    assert api.stored_state(session_id) == (0, "archived")
    assert api.session(session_id)["archivedAt"] is None
    assert api.session(session_id)["sourceAvailability"] == "archived"
    assert session_id not in api.listed_ids(archived=True)
    assert session_id in api.listed_ids(archived=False)

    api.sync(session_id, runtime, "available", mode=mode)
    assert api.stored_state(session_id) == (0, "available")
    api.sync(session_id, runtime, "archived", mode=mode)
    assert api.stored_state(session_id) == (1, "archived")
    archived_at = api.session(session_id)["archivedAt"]
    assert archived_at is not None
    api.sync(session_id, runtime, "available", mode=mode)
    assert api.stored_state(session_id) == (1, "available")
    assert api.session(session_id)["archivedAt"] == archived_at


@pytest.mark.parametrize("runtime", ["codex", "claude", "dsh"])
@pytest.mark.parametrize("state", ["unavailable", "deleted", "missing", "hidden"])
def test_unavailable_agent_sessions_are_not_aa_archives(archive_client, runtime, state):
    api = archive_client
    api.sync("session", runtime, state)
    assert api.stored_state("session") == (0, state)
    assert api.session("session")["archived"] is False
    assert "session" not in api.listed_ids(archived=True)
    assert "session" in api.listed_ids(archived=False)


def test_project_unarchive_persists_for_synced_sessions_and_refreshes_counts(
    archive_client,
):
    api = archive_client
    api.sync("codex_session", "codex", "archived")
    api.sync("claude_session", "claude", "archived")
    api.sync("other_project", "codex", "archived", cwd="/other")
    project_id = api.session("codex_session")["projectId"]
    response = api.client.post(
        f"/projects/{project_id}/sessions/archive-all",
        headers=api.headers,
        json={"archived": False, "scope": "archived"},
    )
    assert response.status_code == 200, response.text
    assert response.json()["affected"] == 2
    assert all(not session["archived"] for session in response.json()["sessions"])
    api.sync("codex_session", "codex", "archived", mode="inventory")
    api.sync("claude_session", "claude", "archived", mode="meta")
    assert api.listed_ids(archived=True) == {"other_project"}
    assert api.listed_ids(archived=False) == {"codex_session", "claude_session"}
    projects = api.client.get("/projects", headers=api.headers).json()["projects"]
    project = next(project for project in projects if project["id"] == project_id)
    assert project["activeSessionCount"] == 2
    assert project["sidebarSessionCounts"] == {"active": 2, "archived": 0}


@pytest.mark.parametrize("runtime", ["codex", "claude", "dsh"])
def test_source_disappearance_does_not_undo_aa_unarchive(archive_client, runtime):
    api = archive_client
    api.sync("session", runtime, "archived")
    api.unarchive("session")
    for state in ["missing", "unavailable", "deleted", "unknown"]:
        api.sync("session", runtime, state, mode="inventory")
        api.sync("session", runtime, "archived", mode="inventory")
        assert api.stored_state("session") == (0, "archived")


def test_stale_source_event_does_not_rearchive_an_aa_session(archive_client):
    api = archive_client
    api.sync("session", "codex", "archived")
    api.unarchive("session")
    api.sync("session", "codex", "available")
    api.ingest(
        {
            "method": "session.source.updated",
            "params": {
                "sessionId": "session",
                "runtime": "codex",
                "availability": "archived",
                "observedAt": "2026-09-05T00:00:00Z",
                "observationOrigin": "event",
            },
        }
    )
    assert api.stored_state("session") == (0, "available")


@pytest.mark.parametrize("method", ["upsert", "snapshot"])
def test_repository_sync_paths_preserve_aa_unarchive(archive_client, method):
    api = archive_client
    store = api.client.app.state.store
    if method == "snapshot":
        api.sync("session", "codex", "available")

    async def sync():
        if method == "upsert":
            return await store.upsert_connector_session(
                connector_id=api.connector_id,
                session_id="session",
                runtime="codex",
                external_session_id="external_session",
                cwd="/repo",
                title="Session",
                source_state="archived",
            )
        return await store.update_session_snapshot(
            session_id="session", source_state="archived"
        )

    assert asyncio.run(sync()).archived is True
    assert api.stored_state("session") == (1, "archived")
    api.unarchive("session")
    assert asyncio.run(sync()).archived is False
    assert api.stored_state("session") == (0, "archived")


def _advertise_native_archive(api: ArchiveClient, *, runtime: str = "dsh") -> None:
    revision = 1_785_489_256_422_611
    response = api.client.post(
        "/connector/ingest",
        headers=api.connector_headers,
        json={
            "notifications": [
                {
                    "method": "protocol.capabilitiesUpdated",
                    "params": {
                        "revision": revision,
                        "capabilities": [
                            {
                                "capabilityId": "sessionArchive",
                                "scope": "runtime",
                                "runtime": runtime,
                                "version": "1",
                                "supported": True,
                                "available": True,
                                "allowed": True,
                            }
                        ],
                    },
                }
            ]
        },
    )
    assert response.status_code == 200, response.text


def _capture_runtime_rpc(api, monkeypatch, calls, *, error: Exception | None = None):
    async def request(connector_id, method, params=None, *, timeout=None):
        calls.append((method, dict(params or {})))
        if error is not None:
            raise error
        return {"ok": True}

    manager = api.client.app.state.rpc

    async def online(connector_id):
        return True

    monkeypatch.setattr(manager, "request", request)
    monkeypatch.setattr(manager, "is_online", online)
    return calls


def test_runtime_archive_capability_archives_through_the_device(archive_client, monkeypatch):
    api = archive_client
    api.sync("session_dsh", "dsh", "available")
    _advertise_native_archive(api)
    calls = _capture_runtime_rpc(api, monkeypatch, [])

    response = api.client.post(
        "/sessions/archive", headers=api.headers, json=["session_dsh"]
    )

    assert response.status_code == 200, response.text
    assert response.json()["sessions"][0]["archived"] is True
    assert [method for method, _ in calls] == ["session.archive"]
    _, params = calls[0]
    assert params["sessionId"] == "session_dsh"
    assert params["externalSessionId"] == "external_session_dsh"
    assert params["archived"] is True
    assert params["runtime"] == "dsh"
    assert api.stored_state("session_dsh")[0] == 1


def test_runtime_archive_capability_restores_through_the_device(archive_client, monkeypatch):
    api = archive_client
    api.sync("session_dsh", "dsh", "available")
    _advertise_native_archive(api)
    calls = _capture_runtime_rpc(api, monkeypatch, [])

    response = api.client.post(
        "/sessions/unarchive", headers=api.headers, json=["session_dsh"]
    )

    assert response.status_code == 200, response.text
    assert response.json()["sessions"][0]["archived"] is False
    assert calls[0][0] == "session.archive"
    assert calls[0][1]["archived"] is False


def test_session_meta_archive_is_mirrored_into_the_device(archive_client, monkeypatch):
    api = archive_client
    api.sync("session_dsh", "dsh", "available")
    _advertise_native_archive(api)
    calls = _capture_runtime_rpc(api, monkeypatch, [])

    response = api.client.patch(
        "/sessions/session_dsh/meta", headers=api.headers, json={"archived": True}
    )

    assert response.status_code == 200, response.text
    assert response.json()["session"]["archived"] is True
    assert [method for method, _ in calls] == ["session.archive"]
    assert calls[0][1]["archived"] is True


def test_connector_archive_all_is_mirrored_into_the_device(archive_client, monkeypatch):
    api = archive_client
    api.sync("session_dsh", "dsh", "available")
    _advertise_native_archive(api)
    calls = _capture_runtime_rpc(api, monkeypatch, [])

    response = api.client.post(
        f"/connectors/{api.connector_id}/sessions/archive-all",
        headers=api.headers,
        json={"archived": True, "scope": "active"},
    )

    assert response.status_code == 200, response.text
    assert [method for method, _ in calls] == ["session.archive"]


def test_runtimes_without_the_capability_are_not_called(archive_client, monkeypatch):
    api = archive_client
    api.sync("session_codex", "codex", "available")
    calls = _capture_runtime_rpc(api, monkeypatch, [])

    response = api.client.post(
        "/sessions/archive", headers=api.headers, json=["session_codex"]
    )

    assert response.status_code == 200, response.text
    assert response.json()["sessions"][0]["archived"] is True
    assert calls == []


def test_a_refused_device_archive_still_archives_in_agents_anywhere(
    archive_client, monkeypatch
):
    api = archive_client
    api.sync("session_dsh", "dsh", "available")
    _advertise_native_archive(api)
    from agent_server.infra.connector_rpc import ConnectorRpcError

    calls = _capture_runtime_rpc(
        api, monkeypatch, [], error=ConnectorRpcError("INVALID_PARAMS", "refused")
    )

    response = api.client.post(
        "/sessions/archive", headers=api.headers, json=["session_dsh"]
    )

    assert response.status_code == 200, response.text
    assert response.json()["sessions"][0]["archived"] is True
    assert [method for method, _ in calls] == ["session.archive"]
    assert api.stored_state("session_dsh")[0] == 1


def test_a_connector_without_the_archive_method_keeps_the_platform_archive(
    archive_client, monkeypatch
):
    api = archive_client
    api.sync("session_dsh", "dsh", "available")
    _advertise_native_archive(api)
    from agent_server.infra.connector_rpc import ConnectorRpcError

    _capture_runtime_rpc(
        api, monkeypatch, [], error=ConnectorRpcError("runtime_unsupported", "no method")
    )

    response = api.client.post(
        "/sessions/archive", headers=api.headers, json=["session_dsh"]
    )

    assert response.status_code == 200, response.text
    assert api.stored_state("session_dsh")[0] == 1
