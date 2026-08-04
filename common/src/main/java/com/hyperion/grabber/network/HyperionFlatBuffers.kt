package com.hyperion.grabber.common.network

import com.google.flatbuffers.FlatBufferBuilder
import hyperionnet.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class HyperionFlatBuffers(address: String?, port: Int, priority: Int) : HyperionClient {
    private val TIMEOUT = 1000
    private val mSocket: Socket = Socket()
    private val mPriority: Int
    private val mBuilder: FlatBufferBuilder

    init {
        mSocket.tcpNoDelay = true // Disable Nagle's algorithm for low latency
        mSocket.sendBufferSize = 8192 // Smaller buffer for faster sends
        mSocket.receiveBufferSize = 4096
        mSocket.connect(InetSocketAddress(address, port), TIMEOUT)
        mSocket.soTimeout = 10 // Very short timeout for non-blocking behavior
        mPriority = priority
        mBuilder = FlatBufferBuilder(1024)
        register()
    }

    @Throws(IOException::class)
    private fun register() {
        mBuilder.clear()
        val originOffset = mBuilder.createString("HyperionAndroidGrabber")
        val registerOffset = Register.createRegister(mBuilder, originOffset, mPriority)
        val requestOffset = Request.createRequest(mBuilder, Command.Register, registerOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    override fun isConnected(): Boolean {
        return mSocket.isConnected
    }

    @Throws(IOException::class)
    override fun disconnect() {
        if (isConnected()) {
            mSocket.close()
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        mBuilder.clear()
        val clearOffset = Clear.createClear(mBuilder, priority)
        val requestOffset = Request.createRequest(mBuilder, Command.Clear, clearOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(-1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        // Android's Color ints are 0xAARRGGBB; Hyperion expects a 0xRRGGBB value.
        // The alpha byte must be stripped, otherwise the alpha channel is
        // misinterpreted as the red channel (e.g. Color.BLACK would turn the
        // LEDs red instead of clearing them).
        val rgb = toHyperionRgb(color)
        mBuilder.clear()
        val colorOffset = Color.createColor(mBuilder, rgb, duration_ms)
        val requestOffset = Request.createRequest(mBuilder, Command.Color, colorOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        mBuilder.clear()
        val dataOffset = RawImage.createDataVector(mBuilder, data)
        val rawImageOffset = RawImage.createRawImage(mBuilder, dataOffset, width, height)
        val imageOffset = Image.createImage(mBuilder, ImageType.RawImage, rawImageOffset, duration_ms)
        val requestOffset = Request.createRequest(mBuilder, Command.Image, imageOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    @Throws(IOException::class)
    private fun sendRequest(bb: ByteBuffer) {
        if (isConnected()) {
            val size = bb.remaining()
            val header = ByteArray(4)
            header[0] = ((size shr 24) and 0xFF).toByte()
            header[1] = ((size shr 16) and 0xFF).toByte()
            header[2] = ((size shr 8) and 0xFF).toByte()
            header[3] = (size and 0xFF).toByte()

            val output = mSocket.getOutputStream()
            output.write(header)

            val data = ByteArray(bb.remaining())
            bb[data]
            output.write(data)
            output.flush()
            
            // Don't wait for reply - fire and forget for minimal latency
            // Replies will be handled asynchronously if needed
        }
    }

    fun cleanReplies() {
        receiveReply()
    }

    private fun receiveReply() {
        // Non-blocking reply consumption to keep socket clean
        // This is called separately and doesn't block frame sending
        try {
            val input = mSocket.getInputStream()
            while (input.available() >= HEADER_SIZE) {
                val header = ByteArray(HEADER_SIZE)
                var read = 0
                while (read < HEADER_SIZE) {
                    val n = input.read(header, read, HEADER_SIZE - read)
                    if (n <= 0) return
                    read += n
                }
                val size = (header[0].toInt() and 0xFF shl 24) or
                        (header[1].toInt() and 0xFF shl 16) or
                        (header[2].toInt() and 0xFF shl 8) or
                        (header[3].toInt() and 0xFF)
                if (size <= 0 || size > MAX_REPLY_SIZE) return // corrupt frame, stop consuming
                if (input.available() < size) return // Not enough data yet, will consume later
                val data = ByteArray(size)
                var done = 0
                while (done < size) {
                    val n = input.read(data, done, size - done)
                    if (n <= 0) return
                    done += n
                }
            }
        } catch (e: IOException) {
            // Ignore - non-blocking read
        }
    }

    companion object {
        private const val HEADER_SIZE = 4
        private const val MAX_REPLY_SIZE = 1 shl 16

        /**
         * Converts an Android ARGB color int (0xAARRGGBB) to the 0xRRGGBB value
         * that Hyperion's color command expects, stripping the alpha byte.
         */
        @JvmStatic
        fun toHyperionRgb(color: Int): Int = color and 0xFFFFFF
    }
}