package com.example.remoteacess.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Viewer-side TCP client. Connects to a host's [FrameServer], reads the
 * length-prefixed JPEG stream, decodes each frame to a Bitmap and hands it
 * back via [onFrame]. All callbacks fire on a background thread — the UI layer
 * is responsible for marshalling to the main thread.
 */
class FrameClient {

    @Volatile private var running = false
    private var socket: Socket? = null
    private var worker: Thread? = null

    fun connect(
        host: String,
        port: Int,
        onFrame: (Bitmap) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        if (running) return
        running = true
        worker = thread(name = "FrameClient") {
            try {
                onStatus("Connecting to $host:$port…")
                val s = Socket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                socket = s
                onStatus("Connected")

                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                while (running) {
                    val len = input.readInt()
                    if (len <= 0 || len > MAX_FRAME_BYTES) {
                        onStatus("Bad frame length: $len")
                        break
                    }
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, len)
                    if (bmp != null) onFrame(bmp)
                }
            } catch (e: Exception) {
                if (running) onStatus("Disconnected: ${e.message}")
            } finally {
                closeSocket()
                if (running) onStatus("Disconnected")
            }
        }
    }

    fun disconnect() {
        running = false
        closeSocket()
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.d(TAG, "close: ${e.message}")
        }
        socket = null
    }

    companion object {
        private const val TAG = "FrameClient"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val MAX_FRAME_BYTES = 20_000_000
    }
}
