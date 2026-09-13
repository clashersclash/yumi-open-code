# OpenWakeWord assets needed for Yumi

The original Python app uses OpenWakeWord with ONNX inference. Its fast `0.9900` score is produced by a pipeline of **three** models:

1. `yumi.onnx` — your trained wake-word classifier
2. `melspectrogram.onnx` — converts 16 kHz mono PCM to mel features
3. `embedding_model.onnx` — converts mel features to the 96-wide embeddings expected by `yumi.onnx`

## Find them in the working Python environment

Activate the same Python environment where `rumi.py` worked, then run:

```powershell
python -c "import openwakeword, pathlib; p=pathlib.Path(openwakeword.__file__).parent/'resources'/'models'; print(p); [print(x.name) for x in p.glob('*')]"
```

Copy these exact ONNX files from the printed folder:

```text
melspectrogram.onnx
embedding_model.onnx
```

Copy your existing trained classifier too:

```text
yumi.onnx
```

## Put them in this Android source folder

Create this folder if it does not exist:

```text
app/src/main/assets/openwakeword/
```

The final layout must be:

```text
app/src/main/assets/openwakeword/yumi.onnx
app/src/main/assets/openwakeword/melspectrogram.onnx
app/src/main/assets/openwakeword/embedding_model.onnx
```

Do not substitute `.tflite` files: the current Yumi classifier is ONNX and must use the matching ONNX pipeline. Do not rename the files.

## Then tell Codex

Reply `assets copied` and I will wire the three-model streaming pipeline into the Android app, use the real `0.99f` threshold, and rebuild both APKs.
