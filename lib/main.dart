import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

void main() {
  runApp(const AdbPhoneControlApp());
}

class AdbPhoneControlApp extends StatelessWidget {
  const AdbPhoneControlApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ADB Phone Control',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo)),
      home: const ControlHomePage(),
    );
  }
}

class ControlHomePage extends StatefulWidget {
  const ControlHomePage({super.key});

  @override
  State<ControlHomePage> createState() => _ControlHomePageState();
}

class _ControlHomePageState extends State<ControlHomePage> {
  final _controller = AdbScrcpyController();
  final _hostController = TextEditingController(text: '192.168.0.100');
  final _portController = TextEditingController();
  final _pairHostController = TextEditingController(text: '192.168.0.100');
  final _pairPortController = TextEditingController(text: '37099');
  final _pairCodeController = TextEditingController();
  final _maxSizeController = TextEditingController(text: '1024');
  final _maxFpsController = TextEditingController(text: '30');
  final _bitRateController = TextEditingController(text: '8000000');
  final _textController = TextEditingController();
  bool _connected = false;
  bool _streaming = false;
  int? _textureId;
  Size? _videoSize;
  String _status = '未连接';
  String _debugLog = '';
  Offset? _panStart;
  DateTime? _panStartTime;

  void _addLog(String msg) {
    setState(() {
      _debugLog = '$msg\n$_debugLog';
      if (_debugLog.length > 2000) {
        _debugLog = _debugLog.substring(0, 2000);
      }
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    _hostController.dispose();
    _portController.dispose();
    _pairHostController.dispose();
    _pairPortController.dispose();
    _pairCodeController.dispose();
    _maxSizeController.dispose();
    _maxFpsController.dispose();
    _bitRateController.dispose();
    _textController.dispose();
    super.dispose();
  }

  Future<void> _connect() async {
    final host = _hostController.text.trim();
    final port = int.tryParse(_portController.text.trim()) ?? 5555;
    if (host.isEmpty) {
      setState(() => _status = '请输入IP地址');
      return;
    }
    setState(() {
      _status = '连接中...';
    });
    _addLog('开始连接 $host:$port');
    try {
      final result = await _controller.connectWithLog(host, port);
      if (!mounted) return;
      _addLog('连接结果: ${result['success']}, 日志: ${result['log']}');
      final ok = result['success'] as bool? ?? false;
      setState(() {
        _connected = ok;
        _status = ok ? '已连接 $host:$port' : '连接失败: ${result['log']}';
      });
    } catch (e) {
      if (!mounted) return;
      _addLog('连接异常: $e');
      setState(() {
        _status = '连接异常: $e';
      });
    }
  }

  Future<void> _disconnect() async {
    await _controller.disconnect();
    if (!mounted) return;
    setState(() {
      _connected = false;
      _streaming = false;
      _textureId = null;
      _videoSize = null;
      _status = '已断开';
    });
  }

  Future<void> _pair() async {
    final host = _pairHostController.text.trim();
    final port = int.tryParse(_pairPortController.text.trim()) ?? 37099;
    final code = _pairCodeController.text.trim();
    if (code.isEmpty) return;
    setState(() {
      _status = '配对中...';
    });
    final result = await _controller.pair(host, port, code);
    if (!mounted) return;
    setState(() {
      if (result == '配对成功') {
        _hostController.text = host;
        _portController.clear();
        _status = '配对成功！请查看设备上"无线调试"页面显示的端口号，填入上方端口框';
      } else {
        _status = result;
      }
    });
  }

  Future<void> _scanQrCode() async {
    final result = await showDialog<String>(
      context: context,
      builder: (context) => _QrScanDialog(),
    );
    if (result != null && mounted) {
      final uri = Uri.tryParse(result);
      if (uri != null && uri.host.isNotEmpty) {
        _pairHostController.text = uri.host;
        if (uri.hasPort) {
          _pairPortController.text = uri.port.toString();
        }
        final code = uri.queryParameters['code'] ?? uri.queryParameters['p'];
        if (code != null) {
          _pairCodeController.text = code;
        }
      }
    }
  }

  Future<void> _screenOff() async {
    await _controller.screenOff();
  }

  Future<void> _screenOn() async {
    await _controller.screenOn();
  }

  Future<void> _startMirror() async {
    final maxSize = int.tryParse(_maxSizeController.text.trim()) ?? 1024;
    final maxFps = int.tryParse(_maxFpsController.text.trim()) ?? 30;
    final bitRate = int.tryParse(_bitRateController.text.trim()) ?? 8000000;
    setState(() {
      _status = '启动镜像中...';
    });
    final session = await _controller.startMirror(
      maxSize: maxSize,
      maxFps: maxFps,
      bitRate: bitRate,
    );
    if (!mounted) return;
    setState(() {
      _textureId = session?.textureId;
      _videoSize = session == null ? null : Size(session.width.toDouble(), session.height.toDouble());
      _streaming = session != null;
      _status = session == null ? '启动失败' : '镜像中 ${session.width}x${session.height}';
    });
  }

  Future<void> _stopMirror() async {
    await _controller.stopMirror();
    if (!mounted) return;
    setState(() {
      _streaming = false;
      _textureId = null;
      _videoSize = null;
      _status = '镜像已停止';
    });
  }

  Future<void> _sendText() async {
    final text = _textController.text;
    if (text.isEmpty) return;
    await _controller.sendText(text);
    _textController.clear();
  }

  Offset _mapToDevice(Offset local, Size viewSize) {
    final videoSize = _videoSize;
    if (videoSize == null) return local;
    final scaleX = videoSize.width / viewSize.width;
    final scaleY = videoSize.height / viewSize.height;
    return Offset(local.dx * scaleX, local.dy * scaleY);
  }

  @override
  Widget build(BuildContext context) {
    final canControl = _connected && _streaming;
    return Scaffold(
      appBar: AppBar(
        title: const Text('ADB + scrcpy 控制器'),
      ),
      body: SafeArea(
        child: Column(
          children: [
            SingleChildScrollView(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(_status, style: Theme.of(context).textTheme.bodyMedium),
                  if (_debugLog.isNotEmpty)
                    Container(
                      width: double.infinity,
                      margin: const EdgeInsets.only(top: 4, bottom: 4),
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: Colors.grey[900],
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(
                        _debugLog,
                        style: const TextStyle(
                          color: Colors.green,
                          fontSize: 11,
                          fontFamily: 'monospace',
                        ),
                      ),
                    ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    crossAxisAlignment: WrapCrossAlignment.end,
                    children: [
                      SizedBox(
                        width: 160,
                        child: TextField(
                          controller: _hostController,
                          decoration: const InputDecoration(labelText: '目标IP'),
                        ),
                      ),
                      SizedBox(
                        width: 120,
                        child: TextField(
                          controller: _portController,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(
                            labelText: '调试端口',
                            hintText: '设备上显示的端口',
                          ),
                        ),
                      ),
                      ElevatedButton(
                        onPressed: _connected ? _disconnect : _connect,
                        child: Text(_connected ? '断开' : '连接'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    crossAxisAlignment: WrapCrossAlignment.end,
                    children: [
                      SizedBox(
                        width: 160,
                        child: TextField(
                          controller: _pairHostController,
                          decoration: const InputDecoration(labelText: '配对IP'),
                        ),
                      ),
                      SizedBox(
                        width: 100,
                        child: TextField(
                          controller: _pairPortController,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(labelText: '配对端口'),
                        ),
                      ),
                      SizedBox(
                        width: 100,
                        child: TextField(
                          controller: _pairCodeController,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(labelText: '配对码'),
                        ),
                      ),
                      ElevatedButton.icon(
                        onPressed: _scanQrCode,
                        icon: const Icon(Icons.qr_code_scanner, size: 18),
                        label: const Text('扫描'),
                      ),
                      ElevatedButton(
                        onPressed: _pair,
                        child: const Text('配对'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    crossAxisAlignment: WrapCrossAlignment.end,
                    children: [
                      SizedBox(
                        width: 100,
                        child: TextField(
                          controller: _maxSizeController,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(labelText: '分辨率'),
                        ),
                      ),
                      SizedBox(
                        width: 80,
                        child: TextField(
                          controller: _maxFpsController,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(labelText: 'FPS'),
                        ),
                      ),
                      SizedBox(
                        width: 100,
                        child: TextField(
                          controller: _bitRateController,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(labelText: '码率'),
                        ),
                      ),
                      ElevatedButton(
                        onPressed: _streaming ? _stopMirror : (_connected ? _startMirror : null),
                        child: Text(_streaming ? '停止' : '镜像'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            Expanded(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final viewSize = Size(constraints.maxWidth, constraints.maxHeight);
                  return Container(
                    color: Colors.black,
                    child: _textureId == null
                        ? const Center(child: Text('未开始镜像', style: TextStyle(color: Colors.white)))
                        : GestureDetector(
                            onTapDown: canControl
                                ? (details) async {
                                    final mapped = _mapToDevice(details.localPosition, viewSize);
                                    await _controller.tap(mapped.dx.round(), mapped.dy.round());
                                  }
                                : null,
                            onPanStart: canControl
                                ? (details) {
                                    _panStart = details.localPosition;
                                    _panStartTime = DateTime.now();
                                  }
                                : null,
                            onPanEnd: canControl
                                ? (details) async {
                                    final start = _panStart;
                                    final startTime = _panStartTime;
                                    if (start == null || startTime == null) return;
                                    final velocity = details.velocity.pixelsPerSecond;
                                    final endPoint = start + Offset(
                                      velocity.dx.sign * min(200, velocity.distance),
                                      velocity.dy.sign * min(200, velocity.distance),
                                    );
                                    final duration = DateTime.now().difference(startTime).inMilliseconds;
                                    final mappedStart = _mapToDevice(start, viewSize);
                                    final mappedEnd = _mapToDevice(endPoint, viewSize);
                                    await _controller.swipe(
                                      mappedStart.dx.round(),
                                      mappedStart.dy.round(),
                                      mappedEnd.dx.round(),
                                      mappedEnd.dy.round(),
                                      max(200, duration),
                                    );
                                    _panStart = null;
                                    _panStartTime = null;
                                  }
                                : null,
                            child: Texture(textureId: _textureId!),
                          ),
                  );
                },
              ),
            ),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.all(12),
              child: Row(
                children: [
                  SizedBox(
                    width: 200,
                    child: TextField(
                      controller: _textController,
                      decoration: const InputDecoration(labelText: '输入文本'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: _connected ? _sendText : null,
                    child: const Text('发送'),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: _connected ? () => _controller.sendKeycode(4) : null,
                    child: const Text('返回'),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: _connected ? () => _controller.sendKeycode(3) : null,
                    child: const Text('主页'),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: _connected ? _screenOff : null,
                    child: const Text('熄屏'),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: _connected ? _screenOn : null,
                    child: const Text('亮屏'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class MirrorSession {
  MirrorSession({required this.textureId, required this.width, required this.height});
  final int textureId;
  final int width;
  final int height;
}

class AdbScrcpyController {
  static const MethodChannel _channel = MethodChannel('adb_phone_control');

  Future<bool> connect(String host, int port) async {
    final result = await _channel.invokeMethod<bool>('connect', {
      'host': host,
      'port': port,
    });
    return result ?? false;
  }

  Future<Map<String, dynamic>> connectWithLog(String host, int port) async {
    final result = await _channel.invokeMethod<Map>('connectWithLog', {
      'host': host,
      'port': port,
    });
    return {
      'success': result?['success'] ?? false,
      'log': result?['log'] ?? '无日志',
    };
  }

  Future<void> disconnect() async {
    await _channel.invokeMethod('disconnect');
  }

  Future<MirrorSession?> startMirror({
    required int maxSize,
    required int maxFps,
    required int bitRate,
  }) async {
    final result = await _channel.invokeMethod<Map>('startMirror', {
      'maxSize': maxSize,
      'maxFps': maxFps,
      'bitRate': bitRate,
    });
    if (result == null) return null;
    return MirrorSession(
      textureId: result['textureId'] as int,
      width: result['width'] as int,
      height: result['height'] as int,
    );
  }

  Future<void> stopMirror() async {
    await _channel.invokeMethod('stopMirror');
  }

  Future<void> tap(int x, int y) async {
    await _channel.invokeMethod('tap', {'x': x, 'y': y});
  }

  Future<void> swipe(int x1, int y1, int x2, int y2, int durationMs) async {
    await _channel.invokeMethod('swipe', {
      'x1': x1,
      'y1': y1,
      'x2': x2,
      'y2': y2,
      'durationMs': durationMs,
    });
  }

  Future<void> sendKeycode(int keycode) async {
    await _channel.invokeMethod('keycode', {'keycode': keycode});
  }

  Future<void> sendText(String text) async {
    await _channel.invokeMethod('text', {'text': text});
  }

  Future<String> pair(String host, int port, String code) async {
    final result = await _channel.invokeMethod<Map>('pair', {
      'host': host,
      'port': port,
      'code': code,
    });
    if (result == null) return '配对失败';
    final success = result['success'] as bool? ?? false;
    final message = result['message'] as String? ?? '';
    return success ? '配对成功' : (message.isEmpty ? '配对失败' : message);
  }

  Future<void> screenOff() async {
    await _channel.invokeMethod('screenOff');
  }

  Future<void> screenOn() async {
    await _channel.invokeMethod('screenOn');
  }

  void dispose() {}
}

class _QrScanDialog extends StatefulWidget {
  @override
  State<_QrScanDialog> createState() => _QrScanDialogState();
}

class _QrScanDialogState extends State<_QrScanDialog> {
  final MobileScannerController _controller = MobileScannerController(
    detectionSpeed: DetectionSpeed.noDuplicates,
  );
  bool _scanned = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('扫描配对二维码'),
      content: SizedBox(
        width: 300,
        height: 300,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: MobileScanner(
            controller: _controller,
            onDetect: (capture) {
              if (_scanned) return;
              final barcodes = capture.barcodes;
              for (final barcode in barcodes) {
                final value = barcode.rawValue;
                if (value != null) {
                  _scanned = true;
                  Navigator.of(context).pop(value);
                  return;
                }
              }
            },
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
      ],
    );
  }
}
