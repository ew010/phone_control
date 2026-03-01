package app.control

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Base64
import android.view.Surface
import androidx.annotation.RequiresApi
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.view.TextureRegistry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MainActivity : FlutterActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var adbConnection: AdbConnection? = null
    private var scrcpySession: ScrcpySession? = null
    private lateinit var channel: MethodChannel
    private var textureRegistry: TextureRegistry? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        textureRegistry = flutterEngine.renderer
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "adb_phone_control")
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "connect" -> {
                    val host = call.argument<String>("host") ?: ""
                    val port = call.argument<Int>("port") ?: 5555
                    executor.submit {
                        val success = connectToDevice(host, port)
                        runOnUiThread { result.success(success) }
                    }
                }
                "disconnect" -> {
                    executor.submit {
                        stopMirrorInternal()
                        adbConnection?.close()
                        adbConnection = null
                        runOnUiThread { result.success(null) }
                    }
                }
                "startMirror" -> {
                    val maxSize = call.argument<Int>("maxSize") ?: 1024
                    val maxFps = call.argument<Int>("maxFps") ?: 30
                    val bitRate = call.argument<Int>("bitRate") ?: 8000000
                    executor.submit {
                        val sessionInfo = startMirrorInternal(maxSize, maxFps, bitRate)
                        runOnUiThread { result.success(sessionInfo) }
                    }
                }
                "stopMirror" -> {
                    executor.submit {
                        stopMirrorInternal()
                        runOnUiThread { result.success(null) }
                    }
                }
                "pair" -> {
                    val host = call.argument<String>("host") ?: ""
                    val port = call.argument<Int>("port") ?: 0
                    val code = call.argument<String>("code") ?: ""
                    executor.submit {
                        val pairResult = pairWithAdb(host, port, code)
                        runOnUiThread {
                            result.success(
                                mapOf(
                                    "success" to pairResult.first,
                                    "message" to pairResult.second
                                )
                            )
                        }
                    }
                }
                "tap" -> {
                    val x = call.argument<Int>("x") ?: 0
                    val y = call.argument<Int>("y") ?: 0
                    executor.submit {
                        adbConnection?.shell("input tap $x $y")
                        runOnUiThread { result.success(null) }
                    }
                }
                "swipe" -> {
                    val x1 = call.argument<Int>("x1") ?: 0
                    val y1 = call.argument<Int>("y1") ?: 0
                    val x2 = call.argument<Int>("x2") ?: 0
                    val y2 = call.argument<Int>("y2") ?: 0
                    val duration = call.argument<Int>("durationMs") ?: 300
                    executor.submit {
                        adbConnection?.shell("input swipe $x1 $y1 $x2 $y2 $duration")
                        runOnUiThread { result.success(null) }
                    }
                }
                "keycode" -> {
                    val keycode = call.argument<Int>("keycode") ?: 0
                    executor.submit {
                        adbConnection?.shell("input keyevent $keycode")
                        runOnUiThread { result.success(null) }
                    }
                }
                "text" -> {
                    val text = call.argument<String>("text") ?: ""
                    executor.submit {
                        val escaped = text.replace(" ", "%s")
                        adbConnection?.shell("input text \"$escaped\"")
                        runOnUiThread { result.success(null) }
                    }
                }
                "screenOff" -> {
                    executor.submit {
                        adbConnection?.shell("input keyevent 223")
                        runOnUiThread { result.success(null) }
                    }
                }
                "screenOn" -> {
                    executor.submit {
                        adbConnection?.shell("input keyevent 224")
                        runOnUiThread { result.success(null) }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun connectToDevice(host: String, port: Int): Boolean {
        return try {
            val connection = AdbConnection(host, port, applicationContext)
            connection.connect()
            adbConnection?.close()
            adbConnection = connection
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startMirrorInternal(maxSize: Int, maxFps: Int, bitRate: Int): Map<String, Any>? {
        val connection = adbConnection ?: return null
        if (scrcpySession != null) return null
        val scrcpyJar = ensureScrcpyServer(applicationContext) ?: return null
        val textureRegistry = this.textureRegistry ?: return null
        return try {
            val session = ScrcpySession(
                textureRegistry,
                connection,
                scrcpyJar,
                maxSize,
                maxFps,
                bitRate
            )
            val info = session.start()
            scrcpySession = session
            info
        } catch (e: Exception) {
            null
        }
    }

    private fun stopMirrorInternal() {
        scrcpySession?.stop()
        scrcpySession = null
    }

    private fun ensureScrcpyServer(context: Context): File? {
        val dir = File(context.filesDir, "scrcpy")
        if (!dir.exists()) dir.mkdirs()
        val jar = File(dir, "scrcpy-server.jar")
        if (jar.exists() && jar.length() > 0) return jar
        val url = URL("https://github.com/Genymobile/scrcpy/releases/download/v3.3.4/scrcpy-server-v3.3.4.jar")
        return try {
            url.openStream().use { input ->
                FileOutputStream(jar).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            jar
        } catch (e: Exception) {
            null
        }
    }

    private fun pairWithAdb(host: String, port: Int, code: String): Pair<Boolean, String> {
        if (host.isBlank() || port <= 0 || code.isBlank()) {
            return Pair(false, "配对参数不完整")
        }
        val adbPath = findAdbBinary()
        return try {
            val address = "$host:$port"
            val command = if (adbPath == null) listOf("adb", "pair", address, code) else listOf(adbPath, "pair", address, code)
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Pair(true, output)
            } else {
                Pair(false, if (output.isEmpty()) "配对失败" else output)
            }
        } catch (e: Exception) {
            Pair(false, "未找到adb可执行文件")
        }
    }

    private fun findAdbBinary(): String? {
        val candidates = listOf(
            "/system/bin/adb",
            "/system/xbin/adb",
            "/data/local/tmp/adb",
            "/data/local/adb"
        )
        for (path in candidates) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                return file.absolutePath
            }
        }
        return null
    }
}

private class ScrcpySession(
    private val textureRegistry: TextureRegistry,
    private val connection: AdbConnection,
    private val scrcpyJar: File,
    private val maxSize: Int,
    private val maxFps: Int,
    private val bitRate: Int
) {
    private var textureEntry: TextureRegistry.SurfaceTextureEntry? = null
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var decoder: ScrcpyVideoDecoder? = null
    private val stopped = AtomicBoolean(false)

    fun start(): Map<String, Any>? {
        val port = 27183
        connection.pushFile(scrcpyJar, "/data/local/tmp/scrcpy-server.jar")
        connection.reverseForward(port, "scrcpy")
        val scid = (Math.random() * Int.MAX_VALUE).toInt()
        val cmd = listOf(
            "CLASSPATH=/data/local/tmp/scrcpy-server.jar",
            "app_process / com.genymobile.scrcpy.Server 3.3.4",
            "scid=$scid",
            "log_level=info",
            "audio=false",
            "control=false",
            "send_device_meta=false",
            "send_frame_meta=true",
            "video_codec=h264",
            "tunnel_forward=true",
            "tunnel_port=$port",
            "max_size=$maxSize",
            "max_fps=$maxFps",
            "bit_rate=$bitRate"
        ).joinToString(" ")
        connection.shell("$cmd >/dev/null 2>/dev/null &")
        serverSocket = ServerSocket(port)
        serverSocket?.reuseAddress = true
        socket = serverSocket?.accept()
        val input = BufferedInputStream(socket!!.getInputStream())
        val meta = ByteArray(12)
        if (!readFully(input, meta)) return null
        val metaBuf = ByteBuffer.wrap(meta).order(ByteOrder.BIG_ENDIAN)
        val codecId = metaBuf.int
        val width = metaBuf.int
        val height = metaBuf.int
        if (codecId == 0) return null
        val texture = textureRegistry.createSurfaceTexture()
        textureEntry = texture
        val surfaceTexture = texture.surfaceTexture()
        surfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(surfaceTexture)
        val videoDecoder = ScrcpyVideoDecoder(surface, input)
        decoder = videoDecoder
        videoDecoder.start(width, height)
        return mapOf("textureId" to texture.id(), "width" to width, "height" to height)
    }

    fun stop() {
        if (stopped.getAndSet(true)) return
        decoder?.stop()
        socket?.close()
        serverSocket?.close()
        textureEntry?.release()
        decoder = null
        socket = null
        serverSocket = null
        textureEntry = null
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }
}

private class ScrcpyVideoDecoder(
    private val surface: Surface,
    private val input: InputStream
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    fun start(width: Int, height: Int) {
        val mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        mediaCodec.configure(format, surface, null, 0)
        mediaCodec.start()
        codec = mediaCodec
        running.set(true)
        thread = Thread {
            val header = ByteArray(12)
            val buffer = ByteArray(1024 * 1024)
            while (running.get()) {
                if (!readFully(header)) break
                val headerBuf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                headerBuf.long
                val packetSize = headerBuf.int
                if (packetSize <= 0 || packetSize > buffer.size) {
                    break
                }
                if (!readFully(buffer, packetSize)) break
                val codecRef = codec ?: break
                val inputIndex = codecRef.dequeueInputBuffer(5000)
                if (inputIndex >= 0) {
                    val inputBuffer = codecRef.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(buffer, 0, packetSize)
                    codecRef.queueInputBuffer(inputIndex, 0, packetSize, 0, 0)
                }
                val info = MediaCodec.BufferInfo()
                var outputIndex = codecRef.dequeueOutputBuffer(info, 0)
                while (outputIndex >= 0) {
                    codecRef.releaseOutputBuffer(outputIndex, true)
                    outputIndex = codecRef.dequeueOutputBuffer(info, 0)
                }
            }
        }
        thread?.start()
    }

    fun stop() {
        running.set(false)
        try {
            thread?.join(500)
        } catch (e: Exception) {
        }
        codec?.stop()
        codec?.release()
        codec = null
    }

    private fun readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun readFully(buffer: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }
}

private class AdbConnection(
    private val host: String,
    private val port: Int,
    private val context: Context
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val lock = Any()
    private var localId = 1
    private var remoteId = 0
    private var keyPair: KeyPair? = null

    fun connect() {
        synchronized(lock) {
            socket = Socket(host, port)
            socket?.tcpNoDelay = true
            input = DataInputStream(BufferedInputStream(socket!!.getInputStream()))
            output = DataOutputStream(BufferedOutputStream(socket!!.getOutputStream()))
            sendPacket(AdbCommand.CNXN, 0x01000000, 4096, "host::\u0000".toByteArray())
            while (true) {
                val packet = readPacket()
                when (packet.command) {
                    AdbCommand.AUTH -> handleAuth(packet)
                    AdbCommand.CNXN -> {
                        remoteId = packet.arg0
                        return
                    }
                    else -> {}
                }
            }
        }
    }

    fun shell(command: String): String {
        val stream = openStream("shell:$command")
        val out = ByteArrayOutputStream()
        while (true) {
            val packet = readPacket()
            if (packet.arg1 != stream.localId) continue
            when (packet.command) {
                AdbCommand.WRTE -> {
                    out.write(packet.data)
                    sendPacket(AdbCommand.OKAY, stream.localId, stream.remoteId, ByteArray(0))
                }
                AdbCommand.CLSE -> {
                    sendPacket(AdbCommand.CLSE, stream.localId, stream.remoteId, ByteArray(0))
                    break
                }
            }
        }
        return out.toString()
    }

    fun pushFile(localFile: File, remotePath: String) {
        val stream = openStream("sync:")
        val path = "$remotePath,33204"
        sendStreamData(stream, encodeSync("SEND", path.toByteArray()))
        val buffer = ByteArray(64 * 1024)
        FileInputStream(localFile).use { inputFile ->
            while (true) {
                val read = inputFile.read(buffer)
                if (read <= 0) break
                sendStreamData(stream, encodeSync("DATA", buffer, read))
            }
        }
        sendStreamData(stream, encodeSync("DONE", intToBytesLE((System.currentTimeMillis() / 1000).toInt())))
        val response = readSyncResponse(stream)
        if (!response) {
            throw IllegalStateException("push failed")
        }
    }

    fun reverseForward(port: Int, localAbstract: String) {
        val stream = openStream("reverse:forward:tcp:$port;localabstract:$localAbstract")
        while (true) {
            val packet = readPacket()
            if (packet.arg1 != stream.localId) continue
            if (packet.command == AdbCommand.CLSE) {
                sendPacket(AdbCommand.CLSE, stream.localId, stream.remoteId, ByteArray(0))
                break
            }
            if (packet.command == AdbCommand.WRTE) {
                sendPacket(AdbCommand.OKAY, stream.localId, stream.remoteId, ByteArray(0))
            }
        }
    }

    fun close() {
        synchronized(lock) {
            try {
                socket?.close()
            } catch (e: Exception) {
            }
            socket = null
            input = null
            output = null
        }
    }

    private fun openStream(service: String): AdbStream {
        val local = nextLocalId()
        sendPacket(AdbCommand.OPEN, local, 0, (service + "\u0000").toByteArray())
        while (true) {
            val packet = readPacket()
            if (packet.arg1 != local) continue
            if (packet.command == AdbCommand.OKAY) {
                return AdbStream(local, packet.arg0)
            }
            if (packet.command == AdbCommand.CLSE) {
                throw IllegalStateException("stream closed")
            }
        }
    }

    private fun readSyncResponse(stream: AdbStream): Boolean {
        val buffer = ByteArray(8)
        var offset = 0
        while (offset < buffer.size) {
            val packet = readPacket()
            if (packet.arg1 != stream.localId) continue
            if (packet.command == AdbCommand.WRTE) {
                val chunk = packet.data
                val toCopy = min(buffer.size - offset, chunk.size)
                System.arraycopy(chunk, 0, buffer, offset, toCopy)
                offset += toCopy
                sendPacket(AdbCommand.OKAY, stream.localId, stream.remoteId, ByteArray(0))
            } else if (packet.command == AdbCommand.CLSE) {
                break
            }
        }
        val id = String(buffer, 0, 4)
        return id == "OKAY"
    }

    private fun handleAuth(packet: AdbPacket) {
        val type = packet.arg0
        val payload = packet.data
        if (type == 1) {
            val keys = getOrCreateKeys()
            val signature = Signature.getInstance("SHA1withRSA")
            signature.initSign(keys.private)
            signature.update(payload)
            val signed = signature.sign()
            sendPacket(AdbCommand.AUTH, 2, 0, signed)
        } else {
            val keys = getOrCreateKeys()
            val publicKey = encodePublicKey(keys.public)
            sendPacket(AdbCommand.AUTH, 3, 0, publicKey)
        }
    }

    private fun getOrCreateKeys(): KeyPair {
        if (keyPair != null) return keyPair!!
        val privateFile = File(context.filesDir, "adbkey")
        val publicFile = File(context.filesDir, "adbkey.pub")
        if (privateFile.exists() && publicFile.exists()) {
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
            keyPair = KeyPair(publicKey, privateKey)
            return keyPair!!
        }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        privateFile.writeBytes(pair.private.encoded)
        publicFile.writeBytes(pair.public.encoded)
        keyPair = pair
        return pair
    }

    private fun encodePublicKey(publicKey: PublicKey): ByteArray {
        val encoded = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        return "$encoded adb_phone_control\u0000".toByteArray()
    }

    private fun sendStreamData(stream: AdbStream, data: ByteArray) {
        sendPacket(AdbCommand.WRTE, stream.localId, stream.remoteId, data)
    }

    private fun nextLocalId(): Int {
        localId += 1
        return localId
    }

    private fun sendPacket(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val out = output ?: throw IllegalStateException("no connection")
        val checksum = data.sumOf { it.toInt() and 0xff }
        val magic = command xor -0x1
        out.writeIntLE(command)
        out.writeIntLE(arg0)
        out.writeIntLE(arg1)
        out.writeIntLE(data.size)
        out.writeIntLE(checksum)
        out.writeIntLE(magic)
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }

    private fun readPacket(): AdbPacket {
        val inp = input ?: throw IllegalStateException("no connection")
        val command = inp.readIntLE()
        val arg0 = inp.readIntLE()
        val arg1 = inp.readIntLE()
        val length = inp.readIntLE()
        inp.readIntLE()
        inp.readIntLE()
        val data = ByteArray(length)
        if (length > 0) {
            inp.readFully(data)
        }
        return AdbPacket(command, arg0, arg1, data)
    }

    private fun encodeSync(id: String, data: ByteArray): ByteArray {
        val header = id.toByteArray()
        val buffer = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(header)
        buffer.putInt(data.size)
        buffer.put(data)
        return buffer.array()
    }

    private fun encodeSync(id: String, data: ByteArray, length: Int): ByteArray {
        val header = id.toByteArray()
        val buffer = ByteBuffer.allocate(8 + length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(header)
        buffer.putInt(length)
        buffer.put(data, 0, length)
        return buffer.array()
    }

    private fun intToBytesLE(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}

private data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

private data class AdbStream(val localId: Int, val remoteId: Int)

private object AdbCommand {
    val CNXN = command("CNXN")
    val AUTH = command("AUTH")
    val OPEN = command("OPEN")
    val OKAY = command("OKAY")
    val CLSE = command("CLSE")
    val WRTE = command("WRTE")

    private fun command(text: String): Int {
        val bytes = text.toByteArray()
        return (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24)
    }
}

private fun DataInputStream.readIntLE(): Int {
    val bytes = ByteArray(4)
    readFully(bytes)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
}

private fun DataOutputStream.writeIntLE(value: Int) {
    val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    write(buffer)
}
