package com.example.mobilelabs.media

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocketFactory

/**
 * Pure Kotlin RTMP incoming chunk reader & AMF0 control/response parser:
 * - Reads server SetChunkSize, WindowAckSize, PingRequest (responds with PingResponse!)
 * - Extracts AMF0 command name, transactionId, and onStatus/error code for real handshake verification
 */
object RtmpProtocolParser {

    sealed class IncomingMessage {
        data class SetChunkSize(val chunkSize: Int) : IncomingMessage()
        data class WindowAckSize(val size: Int) : IncomingMessage()
        data class PingRequest(val timestamp: Int) : IncomingMessage()
        data class AmfCommand(
            val commandName: String,
            val transactionId: Double,
            val statusCode: String,
            val description: String
        ) : IncomingMessage()
        data class Other(val msgTypeId: Int) : IncomingMessage()
    }

    data class ChunkHeaderState(
        var timestamp: Int = 0,
        var length: Int = 0,
        var msgTypeId: Int = 0,
        var streamId: Int = 0,
        var tsDelta: Int = 0
    )

    class StreamReader(private val input: DataInputStream) {
        var inChunkSize: Int = 128
            private set
        private val headers = HashMap<Int, ChunkHeaderState>()
        private val partialPayloads = HashMap<Int, ByteArrayOutputStream>()

        fun readNextMessage(): IncomingMessage? {
            while (true) {
                val b0 = input.read()
                if (b0 < 0) return null
                val fmt = (b0 ushr 6) and 0x03
                var csid = b0 and 0x3F
                if (csid == 0) {
                    val b1 = input.read()
                    if (b1 < 0) return null
                    csid = 64 + b1
                } else if (csid == 1) {
                    val b1 = input.read()
                    val b2 = input.read()
                    if (b1 < 0 || b2 < 0) return null
                    csid = 64 + b1 + b2 * 256
                }

                val st = headers.getOrPut(csid) { ChunkHeaderState() }
                var extTsNeeded = false
                when (fmt) {
                    0 -> {
                        val ts = readUint24(input)
                        st.length = readUint24(input)
                        st.msgTypeId = input.readUnsignedByte()
                        st.streamId = readInt32LE(input)
                        extTsNeeded = ts == 0xFFFFFF
                        st.timestamp = if (extTsNeeded) readInt32BE(input) else ts
                    }
                    1 -> {
                        val delta = readUint24(input)
                        st.length = readUint24(input)
                        st.msgTypeId = input.readUnsignedByte()
                        extTsNeeded = delta == 0xFFFFFF
                        st.tsDelta = if (extTsNeeded) readInt32BE(input) else delta
                        st.timestamp += st.tsDelta
                    }
                    2 -> {
                        val delta = readUint24(input)
                        extTsNeeded = delta == 0xFFFFFF
                        st.tsDelta = if (extTsNeeded) readInt32BE(input) else delta
                        st.timestamp += st.tsDelta
                    }
                    3 -> {
                        if (st.timestamp >= 0xFFFFFF || st.tsDelta >= 0xFFFFFF) {
                            // Some servers omit extended timestamp on fmt3, peek safely not trivial on stream;
                            // fmt3 continuation in RTMP control channel rarely uses > 0xFFFFFF
                        }
                    }
                }

                val targetLen = st.length.coerceIn(0, 262_144)
                val acc = partialPayloads.getOrPut(csid) { ByteArrayOutputStream(targetLen) }
                val remaining = (targetLen - acc.size()).coerceAtLeast(0)
                val toRead = minOf(inChunkSize, remaining)
                if (toRead > 0) {
                    val buf = ByteArray(toRead)
                    input.readFully(buf)
                    acc.write(buf)
                }

                if (acc.size() >= targetLen) {
                    val fullPayload = acc.toByteArray()
                    partialPayloads.remove(csid)
                    return parseCompleteMessage(st.msgTypeId, fullPayload)
                }
            }
        }

        fun parseCompleteMessage(msgTypeId: Int, payload: ByteArray): IncomingMessage {
            return when (msgTypeId) {
                1 -> {
                    val size = if (payload.size >= 4) beInt32(payload, 0) and 0x7FFFFFFF else 128
                    inChunkSize = size.coerceIn(128, 65536)
                    IncomingMessage.SetChunkSize(inChunkSize)
                }
                4 -> {
                    if (payload.size >= 6) {
                        val eventType = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                        val param = beInt32(payload, 2)
                        if (eventType == 6) IncomingMessage.PingRequest(param)
                        else IncomingMessage.Other(msgTypeId)
                    } else IncomingMessage.Other(msgTypeId)
                }
                5 -> {
                    val size = if (payload.size >= 4) beInt32(payload, 0) else 2500000
                    IncomingMessage.WindowAckSize(size)
                }
                20 -> parseAmf0Command(payload)
                17 -> {
                    // AMF3 command prefix byte 0x00 followed by AMF0
                    if (payload.isNotEmpty() && payload[0].toInt() == 0) {
                        parseAmf0Command(payload.copyOfRange(1, payload.size))
                    } else IncomingMessage.Other(msgTypeId)
                }
                else -> IncomingMessage.Other(msgTypeId)
            }
        }
    }

    fun parseAmf0Command(payload: ByteArray): IncomingMessage.AmfCommand {
        var pos = 0
        fun has(n: Int) = pos + n <= payload.size
        fun readStr(): String {
            if (!has(2)) return ""
            val len = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
            pos += 2
            if (!has(len)) return ""
            val s = String(payload, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        var cmdName = ""
        var txId = 0.0
        var statusCode = ""
        var description = ""

        if (has(1) && payload[pos].toInt() == 0x02) {
            pos++
            cmdName = readStr()
        }
        if (has(1) && payload[pos].toInt() == 0x00) {
            pos++
            if (has(8)) {
                var bits = 0L
                for (i in 0 until 8) {
                    bits = (bits shl 8) or (payload[pos + i].toLong() and 0xFFL)
                }
                txId = java.lang.Double.longBitsToDouble(bits)
                pos += 8
            }
        }

        // Scan remaining AMF0 payload conservatively for "code" / "description" strings inside status object
        val asAscii = String(payload, pos, (payload.size - pos).coerceAtLeast(0), Charsets.UTF_8)
        statusCode = extractAmfField(asAscii, "code")
        description = extractAmfField(asAscii, "description")

        return IncomingMessage.AmfCommand(cmdName, txId, statusCode, description)
    }

    private fun extractAmfField(raw: String, key: String): String {
        val idx = raw.indexOf(key)
        if (idx < 0) return ""
        val after = idx + key.length
        if (after + 3 >= raw.length) return ""
        // AMF0 object property: 2-byte len + key + 0x02 + 2-byte len + string
        if (raw[after].code == 0x02) {
            val strLen = ((raw[after + 1].code and 0xFF) shl 8) or (raw[after + 2].code and 0xFF)
            val start = after + 3
            if (strLen in 1..256 && start + strLen <= raw.length) {
                return raw.substring(start, start + strLen)
            }
        }
        return ""
    }

    private fun readUint24(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        return (b0 shl 16) or (b1 shl 8) or b2
    }

    private fun readInt32BE(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        val b3 = input.readUnsignedByte()
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun readInt32LE(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        val b3 = input.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun beInt32(a: ByteArray, off: Int): Int =
        ((a[off].toInt() and 0xFF) shl 24) or
            ((a[off + 1].toInt() and 0xFF) shl 16) or
            ((a[off + 2].toInt() and 0xFF) shl 8) or
            (a[off + 3].toInt() and 0xFF)
}

/**
 * Bounded-queue RTMP / RTMPS sender implementing:
 * - RTMP C0/C1/C2 handshake
 * - Verified server AMF0 _result / onStatus (NetStream.Publish.Start) confirmation before LIVE state
 * - Continuous server Ping Request -> Ping Response & WindowAck handling
 * - GOP-aware queue eviction (never leaves orphaned P-frames to cause green macroblocks)
 */
class RtmpFlvSender(
    private val baseUrl: String,
    private val streamKey: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val videoBitrateKbps: Int,
    private val audioBitrateKbps: Int,
    private val onConnected: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val onNetworkDrop: () -> Unit
) {
    private data class Packet(
        val isVideo: Boolean,
        val isKey: Boolean,
        val timestampMs: Long,
        val payload: ByteArray
    )

    private val queue = ArrayBlockingQueue<Packet>(150)
    private val queueLock = Any()
    private val running = AtomicBoolean(false)
    private val queuedBytes = AtomicLong(0L)
    private val videoSeqSent = AtomicBoolean(false)
    private val audioSeqSent = AtomicBoolean(false)
    private val needsKeyframeAfterDrop = AtomicBoolean(false)
    private val lastWriteDurationMs = AtomicLong(0L)

    @Volatile private var pendingVideoSeq: ByteArray? = null
    @Volatile private var pendingAudioSeq: ByteArray? = null
    private var worker: Thread? = null
    private var readerThread: Thread? = null
    private var socket: Socket? = null
    private val writeLock = Any()
    private val flvVideoPrefix = ByteArray(5)
    private val flvAudioPrefix = ByteArray(2)
    private val chunkSize = 4096

    fun start(videoSeqHeader: ByteArray?, audioSeqHeader: ByteArray?) {
        if (running.getAndSet(true)) return
        pendingVideoSeq = videoSeqHeader
        pendingAudioSeq = audioSeqHeader
        worker = Thread({
            runSession()
        }, "MobileLabs-RTMP").apply { start() }
    }

    fun updateVideoConfig(avccConfig: ByteArray) {
        if (avccConfig.isEmpty()) return
        pendingVideoSeq = avccConfig
    }

    fun updateAudioConfig(ascConfig: ByteArray) {
        if (ascConfig.isEmpty()) return
        pendingAudioSeq = ascConfig
    }

    fun enqueueVideo(bytes: ByteArray, timestampMs: Long, isKey: Boolean) {
        if (!running.get()) return
        enqueueBounded(Packet(isVideo = true, isKey = isKey, timestampMs = timestampMs, payload = bytes))
    }

    fun enqueueAudio(bytes: ByteArray, timestampMs: Long) {
        if (!running.get()) return
        enqueueBounded(Packet(isVideo = false, isKey = false, timestampMs = timestampMs, payload = bytes))
    }

    fun queueDepth(): Int = queue.size
    fun bufferedBytes(): Long = queuedBytes.get()
    fun congestionPressure(): Float {
        val qFrac = (queue.size / 150f).coerceIn(0f, 1f)
        val writeSlowFrac = (lastWriteDurationMs.get() / 220f).coerceIn(0f, 1f)
        return maxOf(qFrac, writeSlowFrac)
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        synchronized(queueLock) {
            queue.clear()
            queuedBytes.set(0L)
        }
        runCatching { worker?.join(500) }
        runCatching { readerThread?.join(200) }
        worker = null
        readerThread = null
    }

    private fun enqueueBounded(pkt: Packet) {
        synchronized(queueLock) {
            if (queue.remainingCapacity() == 0) {
                // GOP-aware eviction: drop oldest packets up to next keyframe rather than leaving corrupt P-frames
                while (queue.isNotEmpty() && queue.remainingCapacity() < 12) {
                    val dropped = queue.poll() ?: break
                    queuedBytes.addAndGet(-dropped.payload.size.toLong())
                    onNetworkDrop()
                    if (dropped.isVideo) {
                        needsKeyframeAfterDrop.set(true)
                    }
                    val next = queue.peek()
                    if (next != null && next.isVideo && next.isKey && queue.remainingCapacity() >= 4) {
                        break
                    }
                }
            }
            if (queue.offer(pkt)) {
                queuedBytes.addAndGet(pkt.payload.size.toLong())
            }
        }
    }

    private fun runSession() {
        try {
            val parsed = parseRtmpUrl(baseUrl)
            val sock = if (parsed.isTls) {
                SSLSocketFactory.getDefault().createSocket()
            } else {
                Socket()
            }
            sock.connect(InetSocketAddress(parsed.host, parsed.port), 6000)
            sock.soTimeout = 15_000
            sock.tcpNoDelay = true
            socket = sock

            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 16384))

            // RTMP Simple Handshake (C0 + C1 -> S0 + S1 + S2 -> C2)
            output.writeByte(0x03)
            val c1 = ByteArray(1536)
            output.write(c1)
            output.flush()

            val s0 = input.readByte().toInt()
            if (s0 != 0x03) error("Unsupported RTMP server version $s0")
            val s1 = ByteArray(1536)
            input.readFully(s1)
            val s2 = ByteArray(1536)
            input.readFully(s2)
            output.write(s1)
            output.flush()

            // Switch outgoing chunk size to 4096 bytes & issue connect -> createStream -> publish
            synchronized(writeLock) {
                sendSetChunkSize(output, chunkSize)
                sendConnectChunk(output, parsed.app, baseUrl)
                sendCreateStreamChunk(output)
                sendPublishChunk(output, streamKey)
                output.flush()
            }

            // Start active RTMP RX loop to verify publish ack AND answer Ping/WindowAck requests
            val publishLatch = CountDownLatch(1)
            val publishError = AtomicReference<String?>(null)
            val totalBytesReadApprox = AtomicLong(0L)

            readerThread = Thread({
                val reader = RtmpProtocolParser.StreamReader(input)
                while (running.get()) {
                    val msg = runCatching { reader.readNextMessage() }.getOrNull() ?: break
                    totalBytesReadApprox.addAndGet(64L)
                    when (msg) {
                        is RtmpProtocolParser.IncomingMessage.PingRequest -> {
                            synchronized(writeLock) {
                                runCatching {
                                    sendPingResponse(output, msg.timestamp)
                                    output.flush()
                                }
                            }
                        }
                        is RtmpProtocolParser.IncomingMessage.WindowAckSize -> {
                            synchronized(writeLock) {
                                runCatching {
                                    sendAck(output, msg.size)
                                    output.flush()
                                }
                            }
                        }
                        is RtmpProtocolParser.IncomingMessage.AmfCommand -> {
                            if (msg.commandName == "_error") {
                                publishError.set(msg.description.ifBlank {
                                    msg.statusCode.ifBlank { "RTMP server rejected publish" }
                                })
                                publishLatch.countDown()
                            } else if (msg.commandName == "onStatus") {
                                if (msg.statusCode.contains("Failed", true) ||
                                    msg.statusCode.contains("BadName", true) ||
                                    msg.statusCode.contains("Rejected", true)
                                ) {
                                    publishError.set(msg.description.ifBlank { msg.statusCode })
                                    publishLatch.countDown()
                                } else if (msg.statusCode.contains("Publish.Start", true)) {
                                    publishLatch.countDown()
                                }
                            } else if (msg.commandName == "_result" && msg.transactionId >= 2.0) {
                                // createStream succeeded; some minimal RTMP servers send _result without NetStream.Publish.Start
                                if (msg.transactionId >= 3.0) {
                                    publishLatch.countDown()
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                if (running.get() && publishLatch.count > 0) {
                    publishError.compareAndSet(null, "RTMP server closed connection during handshake")
                    publishLatch.countDown()
                }
            }, "MobileLabs-RTMP-Rx").apply { isDaemon = true; start() }

            val acknowledged = publishLatch.await(5000, TimeUnit.MILLISECONDS)
            val err = publishError.get()
            if (!running.get()) return
            if (err != null) error(err)
            if (!acknowledged) {
                error("Timed out waiting for RTMP publish confirmation")
            }

            // Verified server publish acknowledgment! Notify SessionController honestly
            onConnected()

            synchronized(writeLock) {
                sendMetadataChunk(output)
                pendingVideoSeq?.let { seq ->
                    writeFlvVideoTag(output, seq, 0L, isKey = true, isSeqHeader = true)
                    videoSeqSent.set(true)
                }
                pendingAudioSeq?.let { seq ->
                    writeFlvAudioTag(output, seq, 0L, isSeqHeader = true)
                    audioSeqSent.set(true)
                }
                output.flush()
            }

            var firstMediaPtsMs = -1L
            var hasSeenKeyframe = false
            while (running.get()) {
                if (!videoSeqSent.get()) {
                    pendingVideoSeq?.let { seq ->
                        synchronized(writeLock) {
                            writeFlvVideoTag(output, seq, 0L, isKey = true, isSeqHeader = true)
                            videoSeqSent.set(true)
                            output.flush()
                        }
                    }
                }
                if (!audioSeqSent.get()) {
                    pendingAudioSeq?.let { seq ->
                        synchronized(writeLock) {
                            writeFlvAudioTag(output, seq, 0L, isSeqHeader = true)
                            audioSeqSent.set(true)
                            output.flush()
                        }
                    }
                }

                val pkt = queue.poll()
                if (pkt == null) {
                    Thread.sleep(4)
                    continue
                }
                queuedBytes.addAndGet(-pkt.payload.size.toLong())

                if (pkt.isVideo && (!videoSeqSent.get() || (!hasSeenKeyframe && !pkt.isKey))) {
                    continue
                }
                if (pkt.isVideo && needsKeyframeAfterDrop.get()) {
                    if (!pkt.isKey) continue
                    needsKeyframeAfterDrop.set(false)
                }
                if (pkt.isVideo && pkt.isKey) {
                    hasSeenKeyframe = true
                }

                if (firstMediaPtsMs < 0L) {
                    firstMediaPtsMs = pkt.timestampMs
                }
                val relTs = (pkt.timestampMs - firstMediaPtsMs).coerceAtLeast(0L)
                val writeStartMs = android.os.SystemClock.elapsedRealtime()
                synchronized(writeLock) {
                    if (pkt.isVideo) {
                        writeFlvVideoTag(output, pkt.payload, relTs, isKey = pkt.isKey, isSeqHeader = false)
                    } else if (audioSeqSent.get()) {
                        writeFlvAudioTag(output, pkt.payload, relTs, isSeqHeader = false)
                    }
                    if (queue.isEmpty()) {
                        output.flush()
                    }
                }
                lastWriteDurationMs.set((android.os.SystemClock.elapsedRealtime() - writeStartMs).coerceAtLeast(0L))
            }
        } catch (t: Throwable) {
            if (running.get()) {
                onFailure("RTMP disconnected: ${t.message ?: "connection error"}")
            }
        } finally {
            runCatching { socket?.close() }
            socket = null
        }
    }

    private data class ParsedRtmp(val isTls: Boolean, val host: String, val port: Int, val app: String)

    private fun parseRtmpUrl(url: String): ParsedRtmp {
        val trimmed = url.trim()
        val isTls = trimmed.startsWith("rtmps://", ignoreCase = true)
        val withoutScheme = trimmed
            .removePrefix("rtmps://")
            .removePrefix("rtmp://")
        val slashIdx = withoutScheme.indexOf('/')
        val hostPort = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
        val app = if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1).ifBlank { "live" } else "live"
        val colonIdx = hostPort.indexOf(':')
        val host = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
        val port = if (colonIdx >= 0) {
            hostPort.substring(colonIdx + 1).toIntOrNull() ?: if (isTls) 443 else 1935
        } else {
            if (isTls) 443 else 1935
        }
        return ParsedRtmp(isTls, host, port, app)
    }

    private fun sendSetChunkSize(out: DataOutputStream, size: Int) {
        val payload = byteArrayOf(
            ((size ushr 24) and 0x7F).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte()
        )
        writeRtmpChunk(out, csid = 2, msgTypeId = 1, streamId = 0, ts = 0, payload = payload, activeChunkSize = 128)
    }

    private fun sendAck(out: DataOutputStream, bytesRead: Int) {
        val payload = byteArrayOf(
            ((bytesRead ushr 24) and 0xFF).toByte(),
            ((bytesRead ushr 16) and 0xFF).toByte(),
            ((bytesRead ushr 8) and 0xFF).toByte(),
            (bytesRead and 0xFF).toByte()
        )
        writeRtmpChunk(out, csid = 2, msgTypeId = 3, streamId = 0, ts = 0, payload = payload)
    }

    private fun sendPingResponse(out: DataOutputStream, timestamp: Int) {
        val payload = byteArrayOf(
            0x00, 0x07, // Event 7 = PingResponse
            ((timestamp ushr 24) and 0xFF).toByte(),
            ((timestamp ushr 16) and 0xFF).toByte(),
            ((timestamp ushr 8) and 0xFF).toByte(),
            (timestamp and 0xFF).toByte()
        )
        writeRtmpChunk(out, csid = 2, msgTypeId = 4, streamId = 0, ts = 0, payload = payload)
    }

    private fun sendConnectChunk(out: DataOutputStream, app: String, tcUrl: String) {
        val amf = ByteArrayOutputStream()
        writeAmfString(amf, "connect")
        writeAmfNumber(amf, 1.0)
        amf.write(0x03)
        writeAmfProp(amf, "app", app)
        writeAmfProp(amf, "type", "nonprivate")
        writeAmfProp(amf, "tcUrl", tcUrl)
        writeAmfObjectEnd(amf)
        writeRtmpChunk(out, csid = 3, msgTypeId = 20, streamId = 0, ts = 0, payload = amf.toByteArray())
    }

    private fun sendCreateStreamChunk(out: DataOutputStream) {
        val amf = ByteArrayOutputStream()
        writeAmfString(amf, "createStream")
        writeAmfNumber(amf, 2.0)
        amf.write(0x05)
        writeRtmpChunk(out, csid = 3, msgTypeId = 20, streamId = 0, ts = 0, payload = amf.toByteArray())
    }

    private fun sendPublishChunk(out: DataOutputStream, key: String) {
        val amf = ByteArrayOutputStream()
        writeAmfString(amf, "publish")
        writeAmfNumber(amf, 3.0)
        amf.write(0x05)
        writeAmfString(amf, key)
        writeAmfString(amf, "live")
        writeRtmpChunk(out, csid = 8, msgTypeId = 20, streamId = 1, ts = 0, payload = amf.toByteArray())
    }

    private fun sendMetadataChunk(out: DataOutputStream) {
        val amf = ByteArrayOutputStream()
        writeAmfString(amf, "@setDataFrame")
        writeAmfString(amf, "onMetaData")
        amf.write(0x08)
        val count = 8
        amf.write((count ushr 24) and 0xFF)
        amf.write((count ushr 16) and 0xFF)
        amf.write((count ushr 8) and 0xFF)
        amf.write(count and 0xFF)
        writeAmfNumProp(amf, "width", width.toDouble())
        writeAmfNumProp(amf, "height", height.toDouble())
        writeAmfNumProp(amf, "framerate", fps.toDouble())
        writeAmfNumProp(amf, "videocodecid", 7.0)
        writeAmfNumProp(amf, "videodatarate", videoBitrateKbps.toDouble())
        writeAmfNumProp(amf, "audiocodecid", 10.0)
        writeAmfNumProp(amf, "audiodatarate", audioBitrateKbps.toDouble())
        writeAmfProp(amf, "encoder", "Mobile Labs (libobs/MediaCodec)")
        writeAmfObjectEnd(amf)
        writeRtmpChunk(out, csid = 4, msgTypeId = 18, streamId = 1, ts = 0, payload = amf.toByteArray())
    }

    private fun writeFlvVideoTag(
        out: DataOutputStream,
        payload: ByteArray,
        tsMs: Long,
        isKey: Boolean,
        isSeqHeader: Boolean
    ) {
        flvVideoPrefix[0] = if (isKey) 0x17 else 0x27
        flvVideoPrefix[1] = if (isSeqHeader) 0x00 else 0x01
        flvVideoPrefix[2] = 0x00
        flvVideoPrefix[3] = 0x00
        flvVideoPrefix[4] = 0x00
        writeRtmpChunkWithPrefix(
            out = out,
            csid = 6,
            msgTypeId = 9,
            streamId = 1,
            ts = tsMs,
            prefix = flvVideoPrefix,
            prefixLen = 5,
            payload = payload
        )
    }

    private fun writeFlvAudioTag(out: DataOutputStream, payload: ByteArray, tsMs: Long, isSeqHeader: Boolean) {
        flvAudioPrefix[0] = 0xAF.toByte()
        flvAudioPrefix[1] = if (isSeqHeader) 0x00 else 0x01
        writeRtmpChunkWithPrefix(
            out = out,
            csid = 4,
            msgTypeId = 8,
            streamId = 1,
            ts = tsMs,
            prefix = flvAudioPrefix,
            prefixLen = 2,
            payload = payload
        )
    }

    private fun writeRtmpChunkWithPrefix(
        out: DataOutputStream,
        csid: Int,
        msgTypeId: Int,
        streamId: Int,
        ts: Long,
        prefix: ByteArray,
        prefixLen: Int,
        payload: ByteArray,
        activeChunkSize: Int = chunkSize
    ) {
        val totalLen = prefixLen + payload.size
        val extended = ts >= 0xFFFFFFL
        val hdrTs = if (extended) 0xFFFFFFL else ts.coerceAtLeast(0L)
        var offset = 0
        var first = true
        while (offset < totalLen) {
            var remainingInChunk = minOf(activeChunkSize, totalLen - offset)
            if (first) {
                out.writeByte(csid and 0x3F)
                out.writeByte(((hdrTs ushr 16) and 0xFF).toInt())
                out.writeByte(((hdrTs ushr 8) and 0xFF).toInt())
                out.writeByte((hdrTs and 0xFF).toInt())
                out.writeByte((totalLen ushr 16) and 0xFF)
                out.writeByte((totalLen ushr 8) and 0xFF)
                out.writeByte(totalLen and 0xFF)
                out.writeByte(msgTypeId)
                out.writeByte(streamId and 0xFF)
                out.writeByte((streamId ushr 8) and 0xFF)
                out.writeByte((streamId ushr 16) and 0xFF)
                out.writeByte((streamId ushr 24) and 0xFF)
                if (extended) {
                    writeInt32BE(out, ts)
                }
                first = false
            } else {
                out.writeByte(0xC0 or (csid and 0x3F))
                if (extended) {
                    writeInt32BE(out, ts)
                }
            }

            if (offset < prefixLen) {
                val takePrefix = minOf(remainingInChunk, prefixLen - offset)
                out.write(prefix, offset, takePrefix)
                offset += takePrefix
                remainingInChunk -= takePrefix
            }
            if (remainingInChunk > 0) {
                val payloadOff = offset - prefixLen
                out.write(payload, payloadOff, remainingInChunk)
                offset += remainingInChunk
            }
        }
    }

    private fun writeRtmpChunk(
        out: DataOutputStream,
        csid: Int,
        msgTypeId: Int,
        streamId: Int,
        ts: Long,
        payload: ByteArray,
        activeChunkSize: Int = chunkSize
    ) {
        val extended = ts >= 0xFFFFFFL
        val hdrTs = if (extended) 0xFFFFFFL else ts.coerceAtLeast(0L)
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val take = minOf(activeChunkSize, payload.size - offset)
            if (first) {
                out.writeByte(csid and 0x3F)
                out.writeByte(((hdrTs ushr 16) and 0xFF).toInt())
                out.writeByte(((hdrTs ushr 8) and 0xFF).toInt())
                out.writeByte((hdrTs and 0xFF).toInt())
                out.writeByte((payload.size ushr 16) and 0xFF)
                out.writeByte((payload.size ushr 8) and 0xFF)
                out.writeByte(payload.size and 0xFF)
                out.writeByte(msgTypeId)
                out.writeByte(streamId and 0xFF)
                out.writeByte((streamId ushr 8) and 0xFF)
                out.writeByte((streamId ushr 16) and 0xFF)
                out.writeByte((streamId ushr 24) and 0xFF)
                if (extended) {
                    writeInt32BE(out, ts)
                }
                first = false
            } else {
                out.writeByte(0xC0 or (csid and 0x3F))
                if (extended) {
                    writeInt32BE(out, ts)
                }
            }
            out.write(payload, offset, take)
            offset += take
        }
    }

    private fun writeInt32BE(out: DataOutputStream, value: Long) {
        out.writeByte(((value ushr 24) and 0xFF).toInt())
        out.writeByte(((value ushr 16) and 0xFF).toInt())
        out.writeByte(((value ushr 8) and 0xFF).toInt())
        out.writeByte((value and 0xFF).toInt())
    }

    private fun writeAmfString(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write(0x02)
        out.write((b.size ushr 8) and 0xFF)
        out.write(b.size and 0xFF)
        out.write(b)
    }

    private fun writeAmfProp(out: ByteArrayOutputStream, key: String, value: String) {
        val kb = key.toByteArray(Charsets.UTF_8)
        out.write((kb.size ushr 8) and 0xFF)
        out.write(kb.size and 0xFF)
        out.write(kb)
        writeAmfString(out, value)
    }

    private fun writeAmfNumProp(out: ByteArrayOutputStream, key: String, value: Double) {
        val kb = key.toByteArray(Charsets.UTF_8)
        out.write((kb.size ushr 8) and 0xFF)
        out.write(kb.size and 0xFF)
        out.write(kb)
        writeAmfNumber(out, value)
    }

    private fun writeAmfNumber(out: ByteArrayOutputStream, value: Double) {
        out.write(0x00)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        for (shift in 56 downTo 0 step 8) {
            out.write(((bits ushr shift) and 0xFF).toInt())
        }
    }

    private fun writeAmfObjectEnd(out: ByteArrayOutputStream) {
        out.write(0x00)
        out.write(0x00)
        out.write(0x09)
    }
}
