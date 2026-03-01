package app.control

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : FlutterActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var scrcpySession: ScrcpySessionWithAdb? = null
    private lateinit var channel: MethodChannel
    private var textureRegistry: TextureRegistry? = null
    private var connectedDevice: String? = null

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
                "connectWithLog" -> {
                    val host = call.argument<String>("host") ?: ""
                    val port = call.argument<Int>("port") ?: 5555
                    executor.submit {
                        val (success, log) = connectToDeviceWithLog(host, port)
                        runOnUiThread {
                            result.success(mapOf("success" to success, "log" to log))
                        }
                    }
                }
                "disconnect" -> {
                    executor.submit {
                        stopMirrorInternal()
                        connectedDevice?.let { device ->
                            runAdbCommand(arrayOf("disconnect", device))
                        }
                        connectedDevice = null
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
                        runAdbShell("input tap $x $y")
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
                        runAdbShell("input swipe $x1 $y1 $x2 $y2 $duration")
                        runOnUiThread { result.success(null) }
                    }
                }
                "keycode" -> {
                    val keycode = call.argument<Int>("keycode") ?: 0
                    executor.submit {
                        runAdbShell("input keyevent $keycode")
                        runOnUiThread { result.success(null) }
                    }
                }
                "text" -> {
                    val text = call.argument<String>("text") ?: ""
                    executor.submit {
                        val escaped = text.replace(" ", "%s")
                        runAdbShell("input text \"$escaped\"")
                        runOnUiThread { result.success(null) }
                    }
                }
                "screenOff" -> {
                    executor.submit {
                        runAdbShell("input keyevent 223")
                        runOnUiThread { result.success(null) }
                    }
                }
                "screenOn" -> {
                    executor.submit {
                        runAdbShell("input keyevent 224")
                        runOnUiThread { result.success(null) }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun connectToDevice(host: String, port: Int): Boolean {
        val (_, log) = connectWithAdbBinary(host, port)
        return log.contains("connected", ignoreCase = true) || log.contains("already connected", ignoreCase = true)
    }

    private fun connectToDeviceWithLog(host: String, port: Int): Pair<Boolean, String> {
        val (success, log) = connectWithAdbBinary(host, port)
        return Pair(success, log)
    }

    private fun connectWithAdbBinary(host: String, port: Int): Pair<Boolean, String> {
        val logBuilder = StringBuilder()
        logBuilder.appendLine("使用adb二进制文件连接 $host:$port")
        
        val adbPath = getAdbBinaryPath(applicationContext)
        if (adbPath == null) {
            val error = "无法获取adb工具"
            logBuilder.appendLine(error)
            return Pair(false, logBuilder.toString())
        }
        
        return try {
            val address = "$host:$port"
            val pb = ProcessBuilder(adbPath, "connect", address)
            pb.directory(applicationContext.filesDir)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = applicationContext.filesDir.absolutePath
            pb.environment()["ANDROID_SDK_ROOT"] = applicationContext.filesDir.absolutePath
            
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            
            logBuilder.appendLine("adb输出: $output")
            logBuilder.appendLine("退出码: $exitCode")
            
            val success = output.contains("connected", ignoreCase = true) || 
                         output.contains("already connected", ignoreCase = true)
            
            if (success) {
                connectedDevice = address
                logBuilder.appendLine("设备已连接: $address")
            }
            
            Pair(success, logBuilder.toString())
        } catch (e: Exception) {
            val errorMsg = "连接失败: ${e.message}"
            logBuilder.appendLine(errorMsg)
            Pair(false, logBuilder.toString())
        }
    }
    
    private fun runAdbShell(command: String): String {
        return runAdbCommand(arrayOf("shell", command))
    }
    
    private fun runAdbCommand(args: Array<String>): String {
        val adbPath = getAdbBinaryPath(applicationContext) ?: return ""
        return try {
            val pb = ProcessBuilder(listOf(adbPath) + args)
            pb.directory(applicationContext.filesDir)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = applicationContext.filesDir.absolutePath
            val process = pb.start()
            // 先等待进程完成（带超时），再读取输出
            val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ""
            }
            process.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun getAdbBinaryPath(context: Context): String? {
        val abi = Build.SUPPORTED_ABIS[0]
        val assetName = when {
            abi.contains("arm64") -> "adb-arm64"
            abi.contains("arm") -> "adb-arm"
            else -> return null
        }
        
        val adbFile = File(context.filesDir, "adb")
        
        if (!adbFile.exists() || adbFile.length() == 0L) {
            try {
                context.assets.open(assetName).use { input ->
                    adbFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                adbFile.setExecutable(true, false)
                adbFile.setReadable(true, false)
            } catch (e: Exception) {
                return null
            }
        }
        
        return if (adbFile.canExecute()) adbFile.absolutePath else null
    }

    private fun startMirrorInternal(maxSize: Int, maxFps: Int, bitRate: Int): Map<String, Any>? {
        if (scrcpySession != null) return null
        if (connectedDevice == null) return null
        val scrcpyJar = ensureScrcpyServer(applicationContext) ?: return null
        val textureRegistry = this.textureRegistry ?: return null
        val adbPath = getAdbBinaryPath(applicationContext) ?: return null
        return try {
            val session = ScrcpySessionWithAdb(
                textureRegistry,
                adbPath,
                applicationContext.filesDir,
                scrcpyJar,
                maxSize,
                maxFps,
                bitRate
            )
            val info = session.start()
            scrcpySession = session
            info
        } catch (e: Exception) {
            Log.e("Scrcpy", "启动镜像失败: ${e.message}", e)
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
        return try {
            val url = URL("https://github.com/Genymobile/scrcpy/releases/download/v3.3.4/scrcpy-server-v3.3.4.jar")
            val connection = url.openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.getInputStream().use { input ->
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
            Log.e("Scrcpy", "下载scrcpy-server失败: ${e.message}")
            null
        }
    }

    private fun pairWithAdb(host: String, port: Int, code: String): Pair<Boolean, String> {
        if (host.isBlank() || port <= 0 || code.isBlank()) {
            return Pair(false, "配对参数不完整")
        }
        return try {
            val pairResult = AdbPairing.pair(host, port, code, applicationContext)
            if (pairResult.first) {
                Pair(true, "配对成功")
            } else {
                Pair(false, pairResult.second)
            }
        } catch (e: Exception) {
            Pair(false, "配对失败: ${e.message}")
        }
    }
}

private class ScrcpySessionWithAdb(
    private val textureRegistry: TextureRegistry,
    private val adbPath: String,
    private val workingDir: File,
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
        
        // 使用adb push推送文件
        val pushResult = runAdb(arrayOf("push", scrcpyJar.absolutePath, "/data/local/tmp/scrcpy-server.jar"))
        if (pushResult.contains("error", ignoreCase = true)) {
            return null
        }
        
        // 使用adb reverse设置端口转发
        runAdb(arrayOf("reverse", "localabstract:scrcpy", "tcp:$port"))
        
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
        
        // 启动scrcpy服务
        runAdbShell("$cmd >/dev/null 2>/dev/null &")
        
        // 等待服务启动
        Thread.sleep(500)
        
        serverSocket = ServerSocket(port)
        serverSocket?.reuseAddress = true
        serverSocket?.soTimeout = 10000
        try {
            socket = serverSocket?.accept()
        } catch (e: Exception) {
            serverSocket?.close()
            return null
        }
        val clientSocket = socket ?: run {
            serverSocket?.close()
            return null
        }
        val input = BufferedInputStream(clientSocket.getInputStream())
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
        try {
            socket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        textureEntry?.release()
        decoder = null
        socket = null
        serverSocket = null
        textureEntry = null
        // 清除reverse转发（忽略错误）
        try {
            runAdb(arrayOf("reverse", "--remove", "localabstract:scrcpy"))
        } catch (_: Exception) {}
    }

    private fun runAdb(args: Array<String>): String {
        return try {
            val pb = ProcessBuilder(listOf(adbPath) + args)
            pb.directory(workingDir)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = workingDir.absolutePath
            val process = pb.start()
            // 先等待进程完成（带超时），再读取输出
            val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ""
            }
            process.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            ""
        }
    }

    private fun runAdbShell(command: String): String {
        return runAdb(arrayOf("shell", command))
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
            var buffer = ByteArray(1024 * 1024)
            while (running.get()) {
                if (!readFully(header)) break
                val headerBuf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                headerBuf.long
                val packetSize = headerBuf.int
                if (packetSize <= 0) {
                    break
                }
                // 动态扩容buffer
                if (packetSize > buffer.size) {
                    buffer = ByteArray(packetSize)
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
        } catch (_: Exception) {
        }
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
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

private object AdbPairing {
    fun pair(host: String, port: Int, code: String, context: Context): Pair<Boolean, String> {
        val adbPath = getAdbBinary(context)
        
        if (adbPath == null) {
            return Pair(false, "无法获取adb工具")
        }
        
        return pairWithAdb(adbPath, host, port, code, context)
    }
    
    private fun getAdbBinary(context: Context): String? {
        val abi = Build.SUPPORTED_ABIS[0]
        val assetName = when {
            abi.contains("arm64") -> "adb-arm64"
            abi.contains("arm") -> "adb-arm"
            else -> return null
        }
        
        val adbFile = File(context.filesDir, "adb")
        
        if (!adbFile.exists() || adbFile.length() == 0L) {
            try {
                context.assets.open(assetName).use { input ->
                    adbFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                adbFile.setExecutable(true, false)
                adbFile.setReadable(true, false)
            } catch (e: Exception) {
                return null
            }
        }
        
        return if (adbFile.canExecute()) adbFile.absolutePath else null
    }
    
    private fun pairWithAdb(adbPath: String, host: String, port: Int, code: String, context: Context): Pair<Boolean, String> {
        return try {
            val address = "$host:$port"
            val pb = ProcessBuilder(adbPath, "pair", address, code)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["ANDROID_SDK_ROOT"] = context.filesDir.absolutePath
            pb.environment()["ADB_TRACE"] = "0"
            
            val process = pb.start()
            // 先等待进程完成（带超时），再读取输出
            val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Pair(false, "配对超时")
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.exitValue()
            
            if (exitCode == 0 || output.contains("Successfully paired", ignoreCase = true)) {
                Pair(true, "配对成功")
            } else {
                Pair(false, if (output.isEmpty()) "配对失败" else output)
            }
        } catch (e: Exception) {
            Pair(false, "配对失败: ${e.message}")
        }
    }
}
