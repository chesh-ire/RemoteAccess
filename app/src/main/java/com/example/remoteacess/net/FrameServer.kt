package com.example.remoteacess.net

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Host-side TCP server. Accepts a single viewer and streams length-prefixed
 * JPEG frames to it:  [4-byte big-endian length N][N bytes of JPEG] ...
 *
 * Intentionally minimal — one viewer at a time is enough for the MVP.
 */
class FrameServer(private val port: Int) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    @Volatile private var client: Socket? = null
    @Volatile private var output: DataOutputStream? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "FrameServer-accept") {
            try {
                val server = ServerSocket(port)
                serverSocket = server
                Log.i(TAG, "Listening on port $port")
                while (running.get()) {
                    val socket = server.accept() // blocks until a viewer connects
                    Log.i(TAG, "Viewer connected: ${socket.inetAddress?.hostAddress}")
                    // Only one viewer at a time: drop any previous connection.
                    closeClient()
                    socket.tcpNoDelay = true
                    client = socket
                    output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                }
            } catch (e: IOException) {
                if (running.get()) Log.e(TAG, "Accept loop ended", e)
            }
        }
    }

    /** Called from the capture thread for every frame. Silently drops if no viewer. */
    fun sendFrame(jpeg: ByteArray) {
        val out = output ?: return
        try {
            synchronized(out) {
                out.writeInt(jpeg.size)
                out.write(jpeg)
                out.flush()
            }
        } catch (e: IOException) {
            Log.i(TAG, "Viewer disconnected while sending")
            closeClient()
        }
    }

    private fun closeClient() {
        try {
            client?.close()
        } catch (_: IOException) {
        }
        client = null
        output = null
    }

    fun stop() {
        running.set(false)
        closeClient()
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
    }

    companion object {
        private const val TAG = "FrameServer"
    }
}
