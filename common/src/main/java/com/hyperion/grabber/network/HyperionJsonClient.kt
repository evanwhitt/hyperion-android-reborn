package com.hyperion.grabber.common.network

import android.util.Base64
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class HyperionJsonClient(address: String?, port: Int, priority: Int) : HyperionClient {
    private val TIMEOUT = 1000
    private val mSocket: Socket = Socket()
    private val mPriority: Int
    private val mJson = StringBuilder(512)

    init {
        mSocket.tcpNoDelay = true
        mSocket.sendBufferSize = 8192
        mSocket.receiveBufferSize = 4096
        mSocket.connect(InetSocketAddress(address, port), TIMEOUT)
        mSocket.soTimeout = 10
        mPriority = priority
        register()
    }

    private fun register() {
        send("{\"command\":\"register\",\"clientname\":\"HyperionAndroidGrabber\",\"priority\":$mPriority}")
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
        send("{\"command\":\"clear\",\"priority\":$priority}")
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
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        send("{\"command\":\"color\",\"color\":[$r,$g,$b],\"priority\":$priority,\"duration\":$duration_ms}")
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        mJson.setLength(0)
        mJson.append("{\"command\":\"image\",\"imagedata\":\"")
        mJson.append(encoded)
        mJson.append("\",\"imagewidth\":")
        mJson.append(width)
        mJson.append(",\"imageheight\":")
        mJson.append(height)
        mJson.append(",\"priority\":")
        mJson.append(priority)
        mJson.append(",\"duration\":")
        mJson.append(duration_ms)
        mJson.append("}")
        write(mJson.toString())
    }

    private fun send(json: String) {
        write(json)
    }

    @Throws(IOException::class)
    private fun write(json: String) {
        if (isConnected()) {
            val bytes = json.toByteArray(StandardCharsets.US_ASCII)
            val output = mSocket.getOutputStream()
            output.write(bytes)
            output.write('\n'.code)
            output.flush()
        }
    }
}
