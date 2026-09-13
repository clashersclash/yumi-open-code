# Training Yumi’s strict wake word

This recipe trains the permitted phrase family:

- `hey yumi`
- `hello yumi`
- `hi yumi`

It intentionally trains `hey`, `yummy`, `you me`, `hey you`, and other close sounds as **negatives**. Do not put those phrases into the positive folder.

## 1. Create a clean Python environment

```bash
python -m venv .venv
# Windows PowerShell
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install git+https://github.com/arcosoph/nanowakeword.git
```

Confirm the command exists:

```bash
nanowakeword --help
```

If your installed release exposes `nanowakeword-train` instead, use that name for the commands below.

## 2. Keep the project layout

Run these commands from the `wakeword-training` folder. NanoWakeWord creates most data folders itself, but create the noise directory now:

```powershell
New-Item -ItemType Directory -Force data\background_noise | Out-Null
```

Put several minutes of legal, ordinary background recordings in `data/background_noise`: fan, keyboard, TV, traffic, other people talking, and quiet rooms. Do not put the wake phrase in this folder.

The supplied [yumi_wakeword_v1.yaml](yumi_wakeword_v1.yaml) generates synthetic speech. For better real-phone performance, also place recordings from at least 10 people in `data/positive` and negatives in `data/negative` before feature generation. Use 16 kHz mono WAV when recording manually.

## 3. Train

First run — creates clips, features, then trains:

```bash
nanowakeword -c yumi_wakeword_v1.yaml
```

Do not use `--overwrite` unless you intentionally want to regenerate existing features. After the data/features exist, set `generate_clips: false` and `transform_clips: false` in the YAML before a train-only experiment.

## 4. Test for false triggers before Android integration

Keep a separate test set that training never sees. It should include at least 100 recordings each of `hey`, `yummy`, `you me`, conversations, music/TV, and background noise—plus 100 recordings for each allowed phrase. Test on the same phone and from normal speaking distances.

Record these measurements at several thresholds: false accepts, missed wake words, and wake latency. A score threshold of `0.99` is a deployment choice, not a training setting. Start by measuring at `0.90`, `0.95`, and `0.99`; choose the highest threshold that still reliably recognizes all three permitted phrases. `0.99` may cause many missed activations if recordings or microphones differ from training.

## 5. What Android needs

The exported `yumi.onnx` is only the classifier. It expects `16 × 96` feature embeddings, so Android also needs the exact mel/embedding pre-processing model and settings NanoWakeWord used during training. Copy the complete exported inference package, not only `yumi.onnx`. Once that package is available, the app can run the classifier and enforce the calibrated threshold.

Official references: [NanoWakeWord repository](https://github.com/arcosoph/nanowakeword), [configuration guide](https://github.com/arcosoph/nanowakeword/blob/main/CONFIGURATION_GUIDE.md).
