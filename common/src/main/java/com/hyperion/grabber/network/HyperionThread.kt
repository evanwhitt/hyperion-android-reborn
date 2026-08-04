package com.hyperion.grabber.common.network

import com.hyperion.grabber.common.HyperionScreenService.HyperionThreadBroadcaster
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HyperionThread(
    private val callback: HyperionThreadBroadcaster,
    private val host: String?,
    private val port: Int,
    private val priority: Int,
    reconnect: Boolean,
    delaySeconds: Int
) : Thread(TAG) {

    private val reconnectDelayMs = (delaySeconds * 1000).toLong()
    private val reconnectEnabled = AtomicBoolean(reconnect)
    private val connected = AtomicBoolean(false)
    private val clientRef = AtomicReference<HyperionClient>()
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private val latestFrame = AtomicReference<FrameData>()
    private var lastFrameNumber = 0L
    private var sendTaskScheduled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    val receiver: HyperionThreadListener = object : HyperionThreadListener {
        override fun sendFrame(data: ByteArray, width: Int, height: Int) {
            val client = clientRef.get()
            if (paused.get() || client == null || !client.isConnected()) return

            ++lastFrameNumber
            latestFrame.set(FrameData(data, width, height, lastFrameNumber))
            
            if (sendTaskScheduled.compareAndSet(false, true)) {
                networkExecutor.submit { sendLatestFrame() }
            }
        }

        private fun sendLatestFrame() {
            try {
                val frame = latestFrame.getAndSet(null) ?: return
                val client = clientRef.get()

                if (client != null && client.isConnected()) {
                    client.setImage(frame.data, frame.width, frame.height, priority, FRAME_DURATION)

                    if (client is HyperionFlatBuffers) {
                        client.cleanReplies()
                    }
                }
            } catch (e: IOException) {
                handleError(e)
            } finally {
                sendTaskScheduled.set(false)
            }
        }

        override fun clear() {
            val client = clientRef.get()
            if (client != null && client.isConnected()) {
                try {
                    client.clear(priority)
                } catch (e: IOException) {
                    callback.onConnectionError(e.hashCode(), e.message ?: "Unknown error")
                }
            }
        }

        override fun disconnect() {
            latestFrame.set(null)
            lastFrameNumber = 0
            sendTaskScheduled.set(false)

            if (!networkExecutor.isShutdown) {
                networkExecutor.shutdownNow()
                try {
                    networkExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                }
            }

            val client = clientRef.getAndSet(null)
            if (client != null) {
                try {
                    client.disconnect()
                } catch (ignored: IOException) {
                }
            }

            connected.set(false)
        }

        override fun sendStatus(isGrabbing: Boolean) {
            callback.onReceiveStatus(isGrabbing)
        }
    }

    override fun run() {
        connect()
    }

    fun pauseConnection() {
        paused.set(true)
        latestFrame.set(null)
        lastFrameNumber = 0
        sendTaskScheduled.set(false)

        // Stop the connect() retry loop so it can't create a duplicate client while paused.
        if (isAlive && !connected.get()) {
            interrupt()
        }
    }

    fun resumeConnection() {
        if (networkExecutor.isShutdown) return
        paused.set(false)
        networkExecutor.submit {
            try {
                val existing = clientRef.get()
                if (existing != null && existing.isConnected()) {
                    connected.set(true)
                    callback.onConnected()
                    return@submit
                }

                val client = createClient()
                if (client.isConnected()) {
                    clientRef.set(client)
                    connected.set(true)
                    callback.onConnected()
                } else {
                    callback.onConnectionError(0, "Failed to reconnect")
                }
            } catch (e: IOException) {
                callback.onConnectionError(e.hashCode(), e.message ?: "Unknown error")
            }
        }
    }

    private fun createClient(): HyperionClient {
        return HyperionFlatBuffers(host, port, priority)
    }

    private fun connect() {
        // Always attempt the initial connection; only retry when reconnect is enabled.
        do {
            try {
                val client = createClient()
                if (client.isConnected()) {
                    clientRef.set(client)
                    connected.set(true)
                    callback.onConnected()
                    return
                }
            } catch (e: IOException) {
                callback.onConnectionError(e.hashCode(), e.message ?: "Unknown error")
            }
            if (!reconnectEnabled.get() || isInterrupted || paused.get()) return
            sleepSafe(reconnectDelayMs)
        } while (true)
    }

    private fun handleError(e: IOException) {
        callback.onConnectionError(e.hashCode(), e.message ?: "Unknown error")

        if (!reconnectEnabled.get() || paused.get()) return

        // Drop the dead client immediately so frames stop being written to a
        // broken socket (which would otherwise loop "Broken pipe" forever).
        val deadClient = clientRef.getAndSet(null)
        if (deadClient != null) {
            try {
                deadClient.disconnect()
            } catch (ignored: IOException) {
            }
        }
        connected.set(false)

        sleepSafe(reconnectDelayMs)
        if (!connected.get() && !isInterrupted && !paused.get()) {
            try {
                val newClient = createClient()
                if (newClient.isConnected()) {
                    clientRef.set(newClient)
                    connected.set(true)
                    callback.onConnected()
                } else {
                    callback.onConnectionError(0, "Failed to reconnect")
                }
            } catch (ignored: IOException) {
            }
        }
    }

    private fun sleepSafe(ms: Long) {
        try {
            sleep(ms)
        } catch (e: InterruptedException) {
            currentThread().interrupt()
        }
    }

    private class FrameData(val data: ByteArray, val width: Int, val height: Int, val frameNumber: Long)

    interface HyperionThreadListener {
        fun sendFrame(data: ByteArray, width: Int, height: Int)
        fun clear()
        fun disconnect()
        fun sendStatus(isGrabbing: Boolean)
    }

    companion object {
        private const val TAG = "HyperionThread"
        private const val FRAME_DURATION = -1
        private const val SHUTDOWN_TIMEOUT_MS = 100
    }
}
