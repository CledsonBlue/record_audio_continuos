import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Audio Recorder',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: const AudioScreen(),
    );
  }
}

class AudioScreen extends StatefulWidget {
  const AudioScreen({super.key});

  @override
  AudioScreenState createState() => AudioScreenState();
}

class AudioScreenState extends State<AudioScreen> {
  bool _isRecording = false;
  final List<String> _audioChunks = [];
  String _status = 'Pressione para iniciar';
  final player = AudioPlayer();

  @override
  void initState() {
    super.initState();
    _setupAudioListeners();
  }

  void _setupAudioListeners() {
    AudioRecorder.audioChunksStream.listen(
      (filePath) {
        setState(() {
          _audioChunks.add(filePath);
          _status = 'Chunk recebido: ${_audioChunks.length}';
        });
        _processAudioChunk(filePath);
      },
      onError: (error) {
        setState(() => _status = 'Erro: $error');
      },
    );
  }

  Future<void> _processAudioChunk(String filePath) async {
    // Implementar conversão para texto e integração com ChatGPT
    print('Processando chunk: $filePath');
  }

  Future<void> _toggleRecording() async {
    try {
      if (_isRecording) {
        await AudioRecorder.stopRecording();
        setState(() {
          _isRecording = false;
          _status = 'Gravação parada';
        });
      } else {
        await AudioRecorder.startRecording();
        setState(() {
          _isRecording = true;
          _status = 'Gravando...';
        });
      }
    } on PlatformException catch (e) {
      setState(() => _status = 'Erro: ${e.message}');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Gravador de Voz Contínuo')),
      body: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              _isRecording ? Icons.mic : Icons.mic_none,
              size: 80,
              color: _isRecording ? Colors.red : Colors.grey,
            ),
            const SizedBox(height: 20),
            Text(
              _status,
              style: const TextStyle(fontSize: 18),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 20),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                ElevatedButton(
                  onPressed: _toggleRecording,
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 30,
                      vertical: 15,
                    ),
                    backgroundColor: _isRecording ? Colors.red : Colors.green,
                  ),
                  child: Text(
                    _isRecording ? 'PARAR' : 'INICIAR',
                    style: const TextStyle(color: Colors.white),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            Expanded(
              child: ListView.builder(
                itemCount: _audioChunks.length,
                itemBuilder:
                    (context, index) => ListTile(
                      leading: const Icon(Icons.audio_file),
                      title: Text('Chunk ${index + 1}'),
                      subtitle: Text(_audioChunks[index]),
                      trailing: GestureDetector(
                        onTap: () {
                          player.play(DeviceFileSource(_audioChunks[index]));
                        },
                        child: Icon(Icons.play_arrow),
                      ),
                    ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class AudioRecorder {
  static const MethodChannel _methodChannel = MethodChannel('audio_test');
  static const EventChannel _eventChannel = EventChannel('audio_events');

  static Future<void> startRecording() async {
    try {
      print('[Flutter] Iniciando gravação...');
      await _methodChannel.invokeMethod('startRecording');
    } on PlatformException catch (e) {
      print('[Flutter] Erro ao iniciar: ${e.message}');
      rethrow;
    }
  }

  static Future<void> stopRecording() async {
    try {
      print('[Flutter] Parando gravação...');
      await _methodChannel.invokeMethod('stopRecording');
    } on PlatformException catch (e) {
      print('[Flutter] Erro ao parar: ${e.message}');
      rethrow;
    }
  }

  static Stream<String> get audioChunksStream => _eventChannel
      .receiveBroadcastStream()
      .map((event) => event as String)
      .handleError((error) {
        print('[Flutter] Erro no stream: $error');
        throw error;
      });
}
