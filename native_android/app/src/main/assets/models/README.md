# Model Files

This directory should contain the AI model files. These are too large for git and must be downloaded separately.

## Required Models

### STT (Speech-to-Text)
- **Whisper** (Recommended): `ggml-base.en.bin` (148MB) or `ggml-tiny.en.bin` (39MB)
  - Place in: `whisper/`
  - Download from: https://huggingface.co/ggerganov/whisper.cpp

- **Moonshine** (Alternative): 
  - `moonshine-tiny-encoder.onnx` (17MB)
  - `moonshine-tiny-cached-decoder.onnx` (43MB)
  - `moonshine-tiny-uncached-decoder.onnx` (51MB)
  - `moonshine-tiny-preprocessor.onnx` (<1MB)
  - Place in: `stt_moonshine/`
  - Download from: https://huggingface.co/UsefulSensors/moonshine

### VAD (Voice Activity Detection)
- **Silero VAD**: `silero_vad.onnx` (2.3MB)
  - Place in: `vad/`
  - Download from: https://github.com/snakers4/silero-vad

### TTS (Text-to-Speech)
- **Kokoro** (Optional): `kokoro-v0_19.onnx`
  - Place in: `tts_kokoro/`
  - Download from: https://huggingface.co/hexgrad/Kokoro-82M

### LLM
- **TinyLlama**: `tinyllama-1.1b-chat-v1.0.Q4_0.gguf` (608MB)
  - Place in: `llama/`
  - Download from: https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF

## Download Script

You can also use the download script (if available):
```bash
cd native_android
./download_models.sh
```
