"""Re-export distil-large-v3-it encoder with correct sherpa-onnx metadata.

This script re-exports the encoder from scratch using the original PyTorch model,
producing a clean ONNX file with metadata that matches what sherpa-onnx expects.

Key insight about sherpa-onnx prompt construction (from source code):
  1. initial_tokens = sot_sequence from metadata
  2. If is_multilingual: overwrite [1]=lang_id, [2]=task, then append no_timestamps
  3. If NOT is_multilingual: use sot_sequence as-is, then append no_timestamps
  4. no_timestamps is ALWAYS appended (unless enable_segment_timestamps=true)

For our Italian-only distil model with is_multilingual=0:
  - sot_sequence should be [sot, it_lang, transcribe] (3 tokens)
  - sherpa-onnx appends no_timestamps → [sot, it_lang, transcribe, no_timestamps]
  - Total 4 tokens — matches what distil expects

Usage:
  python3 reexport-encoder-clean.py
"""
import gc
import os
import sys

import torch
import torch.nn as nn
import torch.nn.functional as F
import whisper
from pathlib import Path

torch.set_num_threads(1)
torch.set_num_interop_threads(1)

SCRIPT_DIR = Path(__file__).parent
OUT_DIR = SCRIPT_DIR / "output-clean"
OUT_DIR.mkdir(exist_ok=True)

# ── Step 1: Load model ──
print("Step 1: Loading original model...")
model = whisper.load_model(str(SCRIPT_DIR / "original_model.pt"))
model.eval()

dims = model.dims
print(f"  n_mels={dims.n_mels}, n_audio_ctx={dims.n_audio_ctx}")
print(f"  n_text_layer={len(model.decoder.blocks)}, n_audio_layer={dims.n_audio_layer}")

# ── Step 2: Patch AudioEncoder.forward ──
original_forward = whisper.model.AudioEncoder.forward

def patched_audio_encoder_forward(self, x):
    x = F.gelu(self.conv1(x))
    x = F.gelu(self.conv2(x))
    x = x.permute(0, 2, 1)
    x = (x + self.positional_embedding[:x.shape[1]]).to(x.dtype)
    for block in self.blocks:
        x = block(x)
    x = self.ln_post(x)
    return x

whisper.model.AudioEncoder.forward = patched_audio_encoder_forward

# ── Step 3: AudioEncoderTensorCache (from sherpa-onnx) ──
class AudioEncoderTensorCache(nn.Module):
    def __init__(self, encoder, decoder):
        super().__init__()
        self.audioEncoder = encoder
        self.textDecoder = decoder

    def forward(self, x):
        audio_features = self.audioEncoder(x)
        n_layer_cross_k_list = []
        n_layer_cross_v_list = []
        for block in self.textDecoder.blocks:
            n_layer_cross_k_list.append(block.cross_attn.key(audio_features))
            n_layer_cross_v_list.append(block.cross_attn.value(audio_features))
        return torch.stack(n_layer_cross_k_list), torch.stack(n_layer_cross_v_list)

# ── Step 4: Trace with small audio (5s) ──
print("Step 2: Tracing encoder (5s audio)...")
encoder_cache = AudioEncoderTensorCache(model.encoder, model.decoder)

dummy_mel = torch.zeros(1, dims.n_mels, 500, dtype=torch.float32)

with torch.no_grad():
    k, v = encoder_cache(dummy_mel)
    print(f"  cross_k shape = {k.shape} (expected {dims.n_text_layer} layers)")
    assert k.shape == (dims.n_text_layer, 1, 250, dims.n_text_state)

    traced = torch.jit.trace(encoder_cache, dummy_mel)

del dummy_mel, k, v
gc.collect()

# ── Step 5: Export to ONNX ──
unquantized_path = OUT_DIR / "distil-large-v3-it-encoder.unquantized.onnx"
encoder_path = OUT_DIR / "distil-large-v3-it-encoder.int8.onnx"

print("Step 3: Exporting to ONNX (opset 17)...")
with torch.no_grad():
    torch.onnx.export(
        traced,
        torch.zeros(1, dims.n_mels, 500, dtype=torch.float32),
        str(unquantized_path),
        opset_version=17,
        dynamo=False,
        input_names=["mel"],
        output_names=["n_layer_cross_k", "n_layer_cross_v"],
        dynamic_axes={
            "mel": {0: "n_audio", 2: "T"},
            "n_layer_cross_k": {1: "n_audio", 2: "T"},
            "n_layer_cross_v": {1: "n_audio", 2: "T"},
        },
    )

del traced, encoder_cache
gc.collect()
print(f"  Unquantized: {unquantized_path.stat().st_size / 1e6:.1f}MB")

# ── Step 6: Quantize ──
print("Step 4: Quantizing to int8...")
from onnxruntime.quantization import quantize_dynamic, QuantType

quantize_dynamic(
    model_input=str(unquantized_path),
    model_output=str(encoder_path),
    op_types_to_quantize=["MatMul"],
    weight_type=QuantType.QInt8,
)
print(f"  Quantized: {encoder_path.stat().st_size / 1e6:.1f}MB")
unquantized_path.unlink()

# ── Step 7: Add metadata ──
print("Step 5: Adding metadata...")
import onnx

model_onnx = onnx.load(str(encoder_path))
while len(model_onnx.metadata_props):
    model_onnx.metadata_props.pop()

# Get tokenizer values from the actual distil model
tokenizer = whisper.tokenizer.get_tokenizer(model.is_multilingual,
                                            num_languages=model.num_languages)

# Italian language token ID
it_idx = list(tokenizer.all_language_codes).index("it")
italian_token_id = tokenizer.all_language_tokens[it_idx]

# sot_sequence = [sot, italian, transcribe] (3 tokens)
# sherpa-onnx will append no_timestamps to make 4 tokens
sot_sequence = f"{tokenizer.sot},{italian_token_id},{tokenizer.transcribe}"

metadata = {
    # Model architecture
    "model_type": "whisper-distil-large-v3-it",
    "version": "1",
    "n_mels": str(dims.n_mels),
    "n_vocab": str(dims.n_vocab),
    "n_audio_ctx": str(dims.n_audio_ctx),
    "n_audio_state": str(dims.n_audio_state),
    "n_audio_head": str(dims.n_audio_head),
    "n_audio_layer": str(dims.n_audio_layer),
    "n_text_ctx": str(dims.n_text_ctx),
    "n_text_state": str(dims.n_text_state),
    "n_text_head": str(dims.n_text_head),
    "n_text_layer": str(len(model.decoder.blocks)),
    "encoder_n_audio_layer": str(dims.n_audio_layer),
    "decoder_n_text_layer": str(len(model.decoder.blocks)),
    "subsampling": "2",
    "dim_feedforward": "5120",
    "nb_max_frames": "1500",

    # Tokenizer metadata REQUIRED by sherpa-onnx
    "sot": str(tokenizer.sot),
    "eot": str(tokenizer.eot),
    "transcribe": str(tokenizer.transcribe),
    "translate": str(tokenizer.translate),
    "no_timestamps": str(tokenizer.no_timestamps),
    "no_speech": str(tokenizer.no_speech),
    "blank_id": "50257",
    "sot_sequence": sot_sequence,
    "sot_prev": str(tokenizer.sot_prev),
    "sot_lm": str(tokenizer.sot_lm),

    # Italian-only model: is_multilingual=0 means sherpa-onnx uses
    # sot_sequence as-is without overwriting language slot
    "is_multilingual": "0",

    # Original model info
    "language": "it",
    "task": "transcribe",
    "num_languages": str(model.num_languages),
}

for key, value in metadata.items():
    meta = model_onnx.metadata_props.add()
    meta.key = key
    meta.value = str(value)

onnx.save(model_onnx, str(encoder_path))
del model_onnx
gc.collect()

# ── Summary ──
print(f"\nDone! Encoder: {encoder_path}")
print(f"Size: {encoder_path.stat().st_size / 1e6:.1f}MB")
print(f"\nMetadata ({len(metadata)} keys):")
for k, v in sorted(metadata.items()):
    print(f"  {k} = {v}")

print(f"\nPrompt that sherpa-onnx will construct:")
print(f"  sot_sequence from metadata: [{sot_sequence}]")
print(f"  is_multilingual=0, so no overwrite")
print(f"  Appends no_timestamps={tokenizer.no_timestamps}")
print(f"  Final: [{sot_sequence},{tokenizer.no_timestamps}]")

# Cleanup
del model
gc.collect()
