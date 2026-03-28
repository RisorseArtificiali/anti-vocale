"""Re-export encoder only with patched positional embedding slicing.
Memory-optimized: single thread, gc between stages, small trace input."""
import gc
import os
import sys
import torch
import torch.nn as nn
import torch.nn.functional as F
import whisper
from pathlib import Path
from typing import Optional, Dict, Any

# Memory constraints
torch.set_num_threads(1)
torch.set_num_interop_threads(1)

OUT_DIR = "./sherpa-onnx-whisper-distil-large-v3-it"
os.makedirs(OUT_DIR, exist_ok=True)

# ── Step 1: Load model ──
print("Step 1: Loading model...")
model = whisper.load_model("./original_model.pt")
model.eval()

dims = model.dims
print(f"  n_mels={dims.n_mels}, n_audio_ctx={dims.n_audio_ctx}, n_text_layer={dims.n_text_layer}")
print(f"  n_audio_state={dims.n_audio_state}, n_text_state={dims.n_text_state}")

# ── Step 2: Patch AudioEncoder.forward with positional embedding slicing ──
# This is the same patch from sherpa-onnx's export_onnx.py
original_forward = whisper.model.AudioEncoder.forward

def patched_audio_encoder_forward(self: whisper.model.AudioEncoder, x: torch.Tensor):
    """
    x : torch.Tensor, shape = (batch_size, n_mels, n_ctx)
    """
    x = F.gelu(self.conv1(x))
    x = F.gelu(self.conv2(x))
    x = x.permute(0, 2, 1)
    x = (x + self.positional_embedding[:x.shape[1]]).to(x.dtype)
    for block in self.blocks:
        x = block(x)
    x = self.ln_post(x)
    return x

whisper.model.AudioEncoder.forward = patched_audio_encoder_forward
print("  Patched AudioEncoder.forward")

# ── Step 3: Define AudioEncoderTensorCache (from sherpa-onnx) ──
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
print("Step 2: Wrapping and tracing encoder (5s audio)...")
encoder_cache = AudioEncoderTensorCache(model.encoder, model.decoder)

# 5 seconds = 500 mel frames
dummy_mel = torch.zeros(1, dims.n_mels, 500, dtype=torch.float32)

with torch.no_grad():
    # First run to verify
    k, v = encoder_cache(dummy_mel)
    expected_seq = 250  # 500 frames / stride 2
    print(f"  Verify: cross_k shape = {k.shape}, expected ({dims.n_text_layer}, 1, {expected_seq}, {dims.n_text_state})")
    assert k.shape == (dims.n_text_layer, 1, expected_seq, dims.n_text_state), f"Shape mismatch: {k.shape}"

    # Trace
    traced = torch.jit.trace(encoder_cache, dummy_mel)

del dummy_mel, k, v
gc.collect()
print("  Trace complete")

# ── Step 5: Export to ONNX ──
unquantized_path = os.path.join(OUT_DIR, "distil-large-v3-it-encoder.unquantized.onnx")
encoder_path = os.path.join(OUT_DIR, "distil-large-v3-it-encoder.int8.onnx")

print("Step 3: Exporting to ONNX (opset 17)...")
with torch.no_grad():
    torch.onnx.export(
        traced,
        torch.zeros(1, dims.n_mels, 500, dtype=torch.float32),
        unquantized_path,
        opset_version=17,
        dynamo=False,  # legacy trace-based export (required for dynamic_axes)
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
print(f"  Unquantized: {os.path.getsize(unquantized_path) / 1e6:.1f}MB")

# ── Step 6: Verify Slice node ──
import onnx
model_onnx = onnx.load(unquantized_path)
op_types = [n.op_type for n in model_onnx.graph.node]
has_slice = "Slice" in op_types
print(f"  Slice node present: {has_slice} (total ops: {len(op_types)})")
if not has_slice:
    print("  ERROR: No Slice node found!")
    sys.exit(1)
del model_onnx
gc.collect()

# ── Step 7: Quantize ──
print("Step 4: Quantizing to int8...")
from onnxruntime.quantization import quantize_dynamic, QuantType

quantize_dynamic(
    model_input=unquantized_path,
    model_output=encoder_path,
    op_types_to_quantize=["MatMul"],
    weight_type=QuantType.QInt8,
)
print(f"  Quantized: {os.path.getsize(encoder_path) / 1e6:.1f}MB")
os.remove(unquantized_path)

# ── Step 8: Add metadata ──
print("Step 5: Adding metadata...")
model_onnx = onnx.load(encoder_path)

# Remove old metadata
while len(model_onnx.metadata_props):
    model_onnx.metadata_props.pop()

metadata = {
    "model_type": "whisper-distil-large-v3-it",
    "version": "1",
    "n_mels": "128",
    "n_vocab": "51866",
    "n_audio_ctx": "1500",
    "n_audio_state": "1280",
    "n_audio_head": "20",
    "n_audio_layer": "32",
    "n_text_ctx": "448",
    "n_text_state": "1280",
    "n_text_head": "20",
    "n_text_layer": "2",
    "encoder_n_audio_layer": "32",
    "decoder_n_text_layer": "2",
    "sot": "50258",
    "eot": "50257",
    "sot_sequence": "50258,50274,50360,50364",
    "blank_id": "50257",
    "no_timestamps_id": "50364",
    "num_languages": "99",
    "language": "it",
    "task": "transcribe",
    "nb_max_frames": "1500",
    "subsampling": "2",
    "dim_feedforward": "5120",
}

for key, value in metadata.items():
    meta = model_onnx.metadata_props.add()
    meta.key = key
    meta.value = value

onnx.save(model_onnx, encoder_path)
del model_onnx
gc.collect()

# ── Cleanup ──
del model
gc.collect()

print(f"\nDone! Encoder: {encoder_path}")
print(f"Size: {os.path.getsize(encoder_path) / 1e6:.1f}MB")
print(f"Metadata: {len(metadata)} fields")
print(f"sot_sequence: {metadata['sot_sequence']}")
