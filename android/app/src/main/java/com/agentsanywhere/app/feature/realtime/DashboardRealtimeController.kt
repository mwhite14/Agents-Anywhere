package com.agentsanywhere.app.feature.realtime

import android.util.Log
import com.agentsanywhere.app.api.RealtimeTransport
import com.agentsanywhere.app.api.RemoteDashboardSnapshot
import com.agentsanywhere.app.feature.auth.AuthSessionReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicReference

class DashboardRealtimeController(
    private val transport: RealtimeTransport,
    private val sessionStore: AuthSessionReader,
    private val clientId: String,
    private val retryDelay: (Int) -> Long = ::reconnectDelayMillis,
    private val firstSnapshotTimeoutMillis: Long = 2_500L,
) {
    private val scopeGuard = CoroutineExceptionHandler { _, error ->
        // start() runs inside the caller's composition scope, so an exception that
        // escapes a callback it invokes inline is a FATAL EXCEPTION that kills the
        // process. Log it instead, the way SessionRealtimeController does.
        Log.w(REALTIME_LOG_TAG, "Dashboard realtime coroutine failed", error)
    }
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private val activeSocket = AtomicReference<WebSocket?>()
    private val activeJob = AtomicReference<Job?>()

    fun requestImmediateReconnect() {
        activeSocket.getAndSet(null)?.cancel()
        retryNow.trySend(Unit)
    }

    fun start(
        scope: CoroutineScope,
        onSnapshot: (RemoteDashboardSnapshot) -> Unit,
        onConnectionChanged: (connected: Boolean, attempt: Int) -> Unit = { _, _ -> },
        onInitialFailure: () -> Unit = {},
    ): Job {
        val job = scope.launch(Dispatchers.IO + scopeGuard) {
        var attempt = 0
        var initialFailureReported = false
        while (isActive) {
            val serverUrl = sessionStore.readServerUrl()
            val accessToken = sessionStore.readAccessToken()
            if (serverUrl.isBlank() || accessToken.isBlank()) return@launch

            val incoming = Channel<String>(Channel.UNLIMITED)
            var socket: WebSocket? = null
            var receivedSnapshot = false
            val openSignal = CompletableDeferred<Boolean>()
            try {
                val ticket = transport.createDashboardTicket(serverUrl, accessToken, clientId)
                socket = transport.openWebSocket(
                    transport.dashboardWebSocketUrl(serverUrl, ticket.ticket),
                    channelListener(incoming) {
                        openSignal.complete(true)
                        onConnectionChanged(true, 0)
                    }.also { listener -> listener.onTerminated = { openSignal.complete(false) } },
                )
                activeSocket.set(socket)
                if (!openSignal.await()) throw IllegalStateException("Dashboard WebSocket handshake failed.")
                val firstSnapshot: RemoteDashboardSnapshot? = withTimeoutOrNull(firstSnapshotTimeoutMillis) {
                    var parsed: RemoteDashboardSnapshot? = null
                    while (parsed == null) {
                        val message = incoming.receiveCatching().getOrNull() ?: break
                        parsed = transport.parseDashboardMessage(message)
                    }
                    parsed
                }
                if (firstSnapshot != null) {
                    receivedSnapshot = true
                    onSnapshot(firstSnapshot)
                }
                if (!receivedSnapshot && !initialFailureReported) {
                    initialFailureReported = true
                    onInitialFailure()
                }
                for (message in incoming) {
                    transport.parseDashboardMessage(message)?.let { snapshot ->
                        receivedSnapshot = true
                        onSnapshot(snapshot)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!receivedSnapshot && !initialFailureReported) {
                    initialFailureReported = true
                    onInitialFailure()
                }
            } finally {
                activeSocket.compareAndSet(socket, null)
                socket?.cancel()
                incoming.close()
                onConnectionChanged(false, attempt)
            }
            if (!isActive) break
            // A socket that opens and then closes without a parsed snapshot (the
            // server failing the dashboard snapshot, or an unparseable payload) has
            // not delivered anything usable, so escalate instead of re-ticketing
            // every second. Only a received snapshot proves the stream works.
            if (receivedSnapshot) attempt = 0 else attempt = (attempt + 1).coerceAtMost(30)
            val requested = withTimeoutOrNull(retryDelay(attempt)) { retryNow.receive() } != null
            if (requested) attempt = 0
        }
        }
        activeJob.getAndSet(job)?.cancel()
        job.invokeOnCompletion { activeJob.compareAndSet(job, null) }
        return job
    }

    private companion object {
        const val REALTIME_LOG_TAG = "DashboardRealtime"
    }
}

internal class ChannelWebSocketListener(
    private val incoming: Channel<String>,
    private val onOpenCallback: () -> Unit = {},
) : WebSocketListener() {
    var onTerminated: () -> Unit = {}

    override fun onOpen(webSocket: WebSocket, response: Response) {
        onOpenCallback()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        incoming.trySend(text)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        onTerminated()
        incoming.close()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        onTerminated()
        incoming.close(t)
    }
}

internal fun channelListener(
    incoming: Channel<String>,
    onOpen: () -> Unit = {},
): ChannelWebSocketListener = ChannelWebSocketListener(incoming, onOpen)
