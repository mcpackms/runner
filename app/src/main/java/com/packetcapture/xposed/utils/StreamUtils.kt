// app/src/main/java/com/packetcapture/xposed/utils/StreamUtils.kt
package com.packetcapture.xposed.utils

import java.io.*
import kotlin.math.min

class CachedInputStream(
    private val original: InputStream,
    private val onContentRead: (String) -> String
) : InputStream() {
    
    private var cached: ByteArray? = null
    private var pos = 0
    private var closed = false
    
    private fun ensureCached() {
        if (cached != null || closed) return
        
        cached = try {
            val bytes = original.readBytes()
            val originalStr = String(bytes, Charsets.UTF_8)
            val modified = onContentRead(originalStr)
            modified.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            original.readBytes()
        }
        pos = 0
    }
    
    override fun read(): Int {
        ensureCached()
        return cached?.let {
            if (pos < it.size) it[pos++].toInt() and 0xFF else -1
        } ?: -1
    }
    
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        ensureCached()
        return cached?.let {
            val remaining = it.size - pos
            if (remaining <= 0) return -1
            val toRead = min(len, remaining)
            System.arraycopy(it, pos, b, off, toRead)
            pos += toRead
            toRead
        } ?: -1
    }
    
    override fun available(): Int {
        ensureCached()
        return cached?.let { it.size - pos } ?: 0
    }
    
    override fun close() {
        closed = true
        original.close()
    }
}

class CapturingOutputStream(
    private val original: OutputStream,
    private val onFlush: (String) -> Unit
) : OutputStream() {
    
    private val buffer = ByteArrayOutputStream()
    
    override fun write(b: Int) {
        buffer.write(b)
        original.write(b)
    }
    
    override fun write(b: ByteArray) {
        buffer.write(b)
        original.write(b)
    }
    
    override fun write(b: ByteArray, off: Int, len: Int) {
        buffer.write(b, off, len)
        original.write(b, off, len)
    }
    
    override fun flush() {
        original.flush()
        val content = buffer.toString(Charsets.UTF_8.name())
        if (content.isNotEmpty()) {
            onFlush(content)
        }
    }
    
    override fun close() {
        flush()
        original.close()
    }
}
