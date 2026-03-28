"""Re-export decoder using sherpa-onnx's TextDecoderTensorCache pattern.

The bofenghuang decoder ONNX has a broadcast error in self-attention
("4 by 8" in blocks.0/attn/Add_4) that prevents it from running on
onnxruntime. This script re-exports using sherpa-onnx's proven approach
which wraps the decoder in TextDecoderTensorCache.
"""
import gc
import os
import sys
import torch
import torch.nn as nn
import torch.nn.functional as F
import whisper
from pathlib import Path
from typing import Optional

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
print(f"  n_text_head={dims.n_text_head}, n_audio_head={dims.n_audio_head}")
print(f"  n_text_ctx={dims.n_text_ctx}, n_vocab={dims.n_vocab}")

# ── Step 2: Import sherpa-onnx's wrapper classes ──
from whisper.model import (
    AudioEncoder,
    MultiHeadAttention,
    ResidualAttentionBlock,
    TextDecoder,
)


class MultiHeadAttentionCross(nn.Module):
    def __init__(self, mha: MultiHeadAttention):
        super().__init__()
        self.multiHeadAttention = mha

    def forward(self, x, k, v, mask=None):
        q = self.multiHeadAttention.query(x)
        wv, qk = self.multiHeadAttention.qkv_attention(q, k, v, mask)
        return self.multiHeadAttention.out(wv)


class MultiHeadAttentionSelf(nn.Module):
    def __init__(self, mha: MultiHeadAttention):
        super().__init__()
        self.multiHeadAttention = mha

    def forward(self, x, k_cache, v_cache, mask):
        q = self.multiHeadAttention.query(x)
        k = self.multiHeadAttention.key(x)
        v = self.multiHeadAttention.value(x)
        k_cache[:, -k.shape[1]:, :] = k
        v_cache[:, -v.shape[1]:, :] = v
        # Self-attention always needs causal masking.
        # Bypass the mask parameter and call qkv_attention with True for is_causal.
        n_batch, n_ctx, n_state = q.shape
        scale = (n_state // self.multiHeadAttention.n_head) ** -0.25
        q = q.view(*q.shape[:2], self.multiHeadAttention.n_head, -1).permute(0, 2, 1, 3)
        k = k.view(*k.shape[:2], self.multiHeadAttention.n_head, -1).permute(0, 2, 1, 3)
        v = v.view(*v.shape[:2], self.multiHeadAttention.n_head, -1).permute(0, 2, 1, 3)
        from torch.nn.functional import scaled_dot_product_attention
        a = scaled_dot_product_attention(q, k, v, is_causal=True)
        wv = a.permute(0, 2, 1, 3).flatten(start_dim=2)
        return self.multiHeadAttention.out(wv), k_cache, v_cache


class ResidualAttentionBlockTensorCache(nn.Module):
    """Wraps a distil-whisper ResidualAttentionBlock for ONNX export.

    Distil-whisper v3 uses a different structure than standard Whisper:
    - Separate attn_ln, cross_attn_ln, mlp_ln layer norms
    - Single mlp (Sequential) instead of list mlps
    - kv_cache parameter on attention (we manage cache ourselves)
    """
    def __init__(self, block: ResidualAttentionBlock):
        super().__init__()
        self.attn = MultiHeadAttentionSelf(block.attn)
        self.attn_ln = block.attn_ln
        self.cross_attn = MultiHeadAttentionCross(block.cross_attn) if block.cross_attn else None
        self.cross_attn_ln = block.cross_attn_ln
        self.mlp = block.mlp
        self.mlp_ln = block.mlp_ln

    def forward(self, x, self_k_cache, self_v_cache, cross_k, cross_v, mask):
        # Self-attention with pre-norm
        x = x + self.attn(self.attn_ln(x), self_k_cache, self_v_cache, mask)[0]

        # Cross-attention with pre-norm
        if self.cross_attn is not None:
            x = x + self.cross_attn(self.cross_attn_ln(x), cross_k, cross_v)

        # MLP with pre-norm
        x = x + self.mlp(self.mlp_ln(x))

        return x, self_k_cache, self_v_cache


class TextDecoderTensorCache(nn.Module):
    def __init__(self, decoder: TextDecoder, n_ctx: int):
        super().__init__()
        self.textDecoder = decoder
        self.n_ctx = n_ctx
        self.blocks = []
        for block in self.textDecoder.blocks:
            self.blocks.append(ResidualAttentionBlockTensorCache(block))

    def forward(self, tokens, n_layer_self_k_cache, n_layer_self_v_cache,
                n_layer_cross_k, n_layer_cross_v, offset):
        x = (
            self.textDecoder.token_embedding(tokens)
            + self.textDecoder.positional_embedding[
                offset[0]: offset[0] + tokens.shape[-1]
            ]
        )
        x = x.to(n_layer_cross_k[0].dtype)

        i = 0
        for block in self.blocks:
            self_k_cache = n_layer_self_k_cache[i, :, :offset[0] + tokens.shape[-1], :]
            self_v_cache = n_layer_self_v_cache[i, :, :offset[0] + tokens.shape[-1], :]
            x, self_k_cache, self_v_cache = block(
                x,
                self_k_cache=self_k_cache,
                self_v_cache=self_v_cache,
                cross_k=n_layer_cross_k[i],
                cross_v=n_layer_cross_v[i],
                mask=self.textDecoder.mask,
            )
            n_layer_self_k_cache[i, :, :offset[0] + tokens.shape[-1], :] = self_k_cache
            n_layer_self_v_cache[i, :, :offset[0] + tokens.shape[-1], :] = self_v_cache
            i += 1

        x = self.textDecoder.ln(x)
        logits = (
            torch.matmul(
                self.textDecoder.token_embedding.weight.to(x.dtype),
                x.permute(0, 2, 1),
            )
            .permute(0, 2, 1)
            .float()
        )
        return logits, n_layer_self_k_cache, n_layer_self_v_cache


# ── Step 3: Create decoder wrapper ──
print("Step 2: Creating TextDecoderTensorCache wrapper...")
decoder_cache = TextDecoderTensorCache(model.decoder, dims.n_text_ctx)

# ── Step 4: Trace decoder ──
print("Step 3: Tracing decoder...")

# Dummy inputs matching what sherpa-onnx uses
tokens = torch.tensor([[50258, 50274, 50360, 50364]], dtype=torch.int64)  # sot_sequence
n_text_layer = dims.n_text_layer  # 2
n_text_ctx = dims.n_text_ctx  # 448
n_text_state = dims.n_text_state  # 1280
T = 10  # small cross-attention sequence length for tracing

self_k_cache = torch.zeros(n_text_layer, 1, n_text_ctx, n_text_state, dtype=torch.float32)
self_v_cache = torch.zeros(n_text_layer, 1, n_text_ctx, n_text_state, dtype=torch.float32)
cross_k = torch.randn(n_text_layer, 1, T, n_text_state, dtype=torch.float32)
cross_v = torch.randn(n_text_layer, 1, T, n_text_state, dtype=torch.float32)
offset = torch.tensor([tokens.shape[1]], dtype=torch.int64)

with torch.no_grad():
    # First verify
    logits, out_k, out_v = decoder_cache(
        tokens, self_k_cache, self_v_cache, cross_k, cross_v, offset
    )
    print(f"  Verify: logits shape = {logits.shape}, expected (1, 4, {dims.n_vocab})")
    assert logits.shape == (1, 4, dims.n_vocab), f"Shape mismatch: {logits.shape}"
    print(f"  Verify: out_k shape = {out_k.shape}")
    print(f"  Verify: out_v shape = {out_v.shape}")

    # Trace
    traced_decoder = torch.jit.trace(
        decoder_cache,
        (tokens, self_k_cache, self_v_cache, cross_k, cross_v, offset)
    )

del tokens, self_k_cache, self_v_cache, cross_k, cross_v, offset, logits, out_k, out_v
gc.collect()
print("  Trace complete")

# ── Step 5: Export to ONNX ──
unquantized_path = os.path.join(OUT_DIR, "distil-large-v3-it-decoder.unquantized.onnx")
decoder_path = os.path.join(OUT_DIR, "distil-large-v3-it-decoder.int8.onnx")

print("Step 4: Exporting to ONNX (opset 17)...")
T_trace = 10  # same as trace input

with torch.no_grad():
    tokens_t = torch.tensor([[50258, 50274, 50360, 50364]], dtype=torch.int64)
    self_k_t = torch.zeros(n_text_layer, 1, n_text_ctx, n_text_state, dtype=torch.float32)
    self_v_t = torch.zeros(n_text_layer, 1, n_text_ctx, n_text_state, dtype=torch.float32)
    cross_k_t = torch.randn(n_text_layer, 1, T_trace, n_text_state, dtype=torch.float32)
    cross_v_t = torch.randn(n_text_layer, 1, T_trace, n_text_state, dtype=torch.float32)
    offset_t = torch.tensor([4], dtype=torch.int64)

    torch.onnx.export(
        traced_decoder,
        (tokens_t, self_k_t, self_v_t, cross_k_t, cross_v_t, offset_t),
        unquantized_path,
        opset_version=17,
        dynamo=False,
        input_names=[
            "tokens",
            "in_n_layer_self_k_cache",
            "in_n_layer_self_v_cache",
            "n_layer_cross_k",
            "n_layer_cross_v",
            "offset",
        ],
        output_names=[
            "logits",
            "out_n_layer_self_k_cache",
            "out_n_layer_self_v_cache",
        ],
        dynamic_axes={
            "tokens": {0: "n_audio", 1: "n_tokens"},
            "in_n_layer_self_k_cache": {1: "n_audio"},
            "in_n_layer_self_v_cache": {1: "n_audio"},
            "n_layer_cross_k": {1: "n_audio", 2: "T"},
            "n_layer_cross_v": {1: "n_audio", 2: "T"},
            "logits": {0: "logits_dim_0", 1: "logits_dim_1"},
            "out_n_layer_self_k_cache": {1: "n_audio"},
            "out_n_layer_self_v_cache": {1: "n_audio"},
        },
    )

del traced_decoder, tokens_t, self_k_t, self_v_t, cross_k_t, cross_v_t, offset_t
gc.collect()
print(f"  Unquantized: {os.path.getsize(unquantized_path) / 1e6:.1f}MB")

# ── Step 6: Quantize ──
print("Step 5: Quantizing to int8...")
from onnxruntime.quantization import quantize_dynamic, QuantType

quantize_dynamic(
    model_input=unquantized_path,
    model_output=decoder_path,
    op_types_to_quantize=["MatMul"],
    weight_type=QuantType.QInt8,
)
print(f"  Quantized: {os.path.getsize(decoder_path) / 1e6:.1f}MB")
os.remove(unquantized_path)

# ── Step 7: Add metadata (matching sherpa-onnx format) ──
print("Step 6: Adding metadata...")
import onnx

model_onnx = onnx.load(decoder_path)

while len(model_onnx.metadata_props):
    model_onnx.metadata_props.pop()

metadata = {
    "model_type": "whisper-distil-large-v3-it",
    "version": "1",
    "n_mels": str(dims.n_mels),
    "n_audio_ctx": str(dims.n_audio_ctx),
    "n_audio_state": str(dims.n_audio_state),
    "n_audio_head": str(dims.n_audio_head),
    "n_audio_layer": str(len(model.encoder.blocks)),
    "n_vocab": str(dims.n_vocab),
    "n_text_ctx": str(dims.n_text_ctx),
    "n_text_state": str(dims.n_text_state),
    "n_text_head": str(dims.n_text_head),
    "n_text_layer": str(dims.n_text_layer),
    "sot_sequence": "50258,50274,50360,50364",
    "sot": "50258",
    "eot": "50257",
    "blank_id": "50257",
    "no_timestamps": "50364",
}

for key, value in metadata.items():
    meta = model_onnx.metadata_props.add()
    meta.key = key
    meta.value = value

onnx.save(model_onnx, decoder_path)
del model_onnx
gc.collect()

# ── Cleanup ──
del model, decoder_cache
gc.collect()

print(f"\nDone! Decoder: {decoder_path}")
print(f"Size: {os.path.getsize(decoder_path) / 1e6:.1f}MB")
