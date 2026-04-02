#!/usr/bin/env python3
"""
Export bofenghuang/whisper-large-v3-distil-it-v0.2 to sherpa-onnx ONNX format.

Based on k2-fsa/sherpa-onnx/scripts/whisper/export-onnx.py
Adapted for the distil-italian model which uses n_mels=128.

Outputs:
  - distil-large-v3-it-encoder.int8.onnx  (quantized encoder)
  - distil-large-v3-it-decoder.int8.onnx  (quantized decoder)
  - distil-large-v3-it-tokens.txt         (tokenizer vocabulary)

Usage:
  python3 export-distil-italian-onnx.py [--output-dir ./output]

No GPU required. Runs on CPU in ~15-30 minutes.
"""

import argparse
import subprocess
import sys
from pathlib import Path

import torch
import torch.nn as nn


# ---------------------------------------------------------------------------
# From sherpa-onnx/scripts/whisper/export-onnx.py
# ---------------------------------------------------------------------------

class MultiHeadAttentionSelf(nn.Module):
    def __init__(self, inMultiHeadAttention):
        super().__init__()
        self.multiHeadAttention = inMultiHeadAttention

    def forward(self, x, k_cache, v_cache, mask):
        q = self.multiHeadAttention.query(x)
        k = self.multiHeadAttention.key(x)
        v = self.multiHeadAttention.value(x)
        k_cache[:, -k.shape[1]:, :] = k
        v_cache[:, -v.shape[1]:, :] = v
        wv, qk = self.multiHeadAttention.qkv_attention(q, k_cache, v_cache, mask)
        return self.multiHeadAttention.out(wv), k_cache, v_cache


class MultiHeadAttentionCross(nn.Module):
    def __init__(self, inMultiHeadAttention):
        super().__init__()
        self.multiHeadAttention = inMultiHeadAttention

    def forward(self, x, k, v):
        q = self.multiHeadAttention.query(x)
        wv, qk = self.multiHeadAttention.qkv_attention(q, k, v)
        return self.multiHeadAttention.out(wv)


class ResidualAttentionBlockTensorCache(nn.Module):
    def __init__(self, inResidualAttentionBlock):
        super().__init__()
        self.originalBlock = inResidualAttentionBlock
        self.attn = MultiHeadAttentionSelf(inResidualAttentionBlock.attn)
        self.cross_attn = (
            MultiHeadAttentionCross(inResidualAttentionBlock.cross_attn)
            if inResidualAttentionBlock.cross_attn
            else None
        )

    def forward(self, x, self_k_cache, self_v_cache, cross_k, cross_v, mask):
        self_attn_x, self_k_cache_updated, self_v_cache_updated = self.attn(
            self.originalBlock.attn_ln(x), self_k_cache, self_v_cache, mask=mask
        )
        x = x + self_attn_x
        if self.cross_attn:
            x = x + self.cross_attn(
                self.originalBlock.cross_attn_ln(x), cross_k, cross_v
            )
        x = x + self.originalBlock.mlp(self.originalBlock.mlp_ln(x))
        return x, self_k_cache_updated, self_v_cache_updated


class AudioEncoderTensorCache(nn.Module):
    def __init__(self, inAudioEncoder, inTextDecoder):
        super().__init__()
        self.audioEncoder = inAudioEncoder
        self.textDecoder = inTextDecoder

    def forward(self, mel):
        audio_features = self.audioEncoder(mel)

        n_layer_cross_k_list = []
        n_layer_cross_v_list = []
        for block in self.textDecoder.blocks:
            n_layer_cross_k_list.append(block.cross_attn.key(audio_features))
            n_layer_cross_v_list.append(block.cross_attn.value(audio_features))

        return torch.stack(n_layer_cross_k_list), torch.stack(n_layer_cross_v_list)


class TextDecoderTensorCache(nn.Module):
    def __init__(self, inTextDecoder, in_n_ctx):
        super().__init__()
        self.textDecoder = inTextDecoder
        self.n_ctx = in_n_ctx
        self.blocks = nn.ModuleList(
            [ResidualAttentionBlockTensorCache(block) for block in inTextDecoder.blocks]
        )

    def forward(self, tokens, n_layer_self_k_cache, n_layer_self_v_cache,
                n_layer_cross_k, n_layer_cross_v, offset):
        x = (
            self.textDecoder.token_embedding(tokens)
            + self.textDecoder.positional_embedding[
                offset[0]: offset[0] + tokens.shape[-1]
            ]
        )
        x = x.to(n_layer_cross_k[0].dtype)
        for i, block in enumerate(self.blocks):
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

        x = self.textDecoder.ln(x)
        logits = (
            torch.matmul(
                self.textDecoder.token_embedding.weight.to(x.dtype),
                x.permute(0, 2, 1),
            ).permute(0, 2, 1).float()
        )
        return logits, n_layer_self_k_cache, n_layer_self_v_cache


def add_meta_data(filename, meta_data):
    import onnx
    model = onnx.load(filename)
    while model.metadata_props:
        model.metadata_props.pop()
    for k, v in meta_data.items():
        model.metadata_props.append(onnx.StringStringEntryProto(key=k, value=str(v)))
    onnx.save(model, filename)


def convert_tokens(model, output_dir):
    import whisper
    whisper_dir = Path(whisper.__file__).parent
    multilingual = model.is_multilingual
    tokenizer = whisper.tokenizer.get_tokenizer(
        model.is_multilingual, num_languages=model.num_languages
    )
    tokenizer_file = whisper_dir / "assets" / (
        "multilingual.tiktoken" if multilingual else "gpt2.tiktoken"
    )
    if not tokenizer_file.is_file():
        raise ValueError(f"Cannot find {tokenizer_file}")
    with open(tokenizer_file, "r") as f:
        contents = f.read()

    tokens_path = output_dir / "distil-large-v3-it-tokens.txt"
    with open(tokens_path, "w", encoding="utf-8") as f:
        f.write(contents)
    print(f"  Extracted tokens: {tokens_path}")
    return tokens_path


def install_deps():
    deps = [
        "torch", "torchaudio", "openai-whisper",
        "onnxruntime", "onnx", "onnxscript",
        "soundfile", "librosa", "huggingface_hub",
    ]
    for dep in deps:
        print(f"  pip install {dep} ...")
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", dep],
                       check=True)


def main():
    parser = argparse.ArgumentParser(
        description="Export distil-italian whisper to sherpa-onnx ONNX format")
    parser.add_argument("--output-dir",
                        default="./sherpa-onnx-whisper-distil-large-v3-it",
                        help="Output directory for ONNX files")
    args = parser.parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    model_name = "distil-large-v3-it"

    # ---- Step 1: Install dependencies ----
    print("=" * 60)
    print("Step 1/7: Installing dependencies...")
    print("=" * 60)
    install_deps()

    # Disable SDPA to avoid TypeError with is_causal Tensor vs bool
    from whisper.model import disable_sdpa

    # ---- Step 2: Download model ----
    print()
    print("=" * 60)
    print("Step 2/7: Downloading model from HuggingFace...")
    print("=" * 60)
    checkpoint = Path("./original_model.pt")
    if not checkpoint.exists():
        print("  Downloading original_model.pt (~1.5GB)...")
        from huggingface_hub import hf_hub_download
        hf_hub_download(
            repo_id="bofenghuang/whisper-large-v3-distil-it-v0.2",
            filename="original_model.pt",
            local_dir=".",
            local_dir_use_symlinks=False,
        )
    else:
        print(f"  Found existing {checkpoint}")

    # ---- Step 3: Load model ----
    print()
    print("=" * 60)
    print("Step 3/7: Loading Whisper model...")
    print("=" * 60)
    import whisper

    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)

    with disable_sdpa():
        model = whisper.load_model(str(checkpoint))

        dims = model.dims
        n_decoder_layers = len(model.decoder.blocks)
        n_audio_ctx = dims.n_audio_ctx
        n_text_ctx = dims.n_text_ctx
        n_text_state = dims.n_text_state
        n_text_head = dims.n_text_head
        n_vocab = dims.n_vocab
        n_mels = 128  # distil-large-v3 uses 128 mel bins

        print(f"  Layers: {n_decoder_layers}, n_mels: {n_mels}")
        print(f"  Audio ctx: {n_audio_ctx}, Text ctx: {n_text_ctx}")
        print(f"  Text state: {n_text_state}, Vocab: {n_vocab}")

        model.eval()

        encoder_onnx = output_dir / f"{model_name}-encoder.onnx"

        # ---- Step 4: Export encoder (skip if already done) ----
        if encoder_onnx.exists():
            print()
            print("=" * 60)
            print("Step 4/7: Exporting encoder... SKIPPED (already exists)")
            print("=" * 60)
        else:
            print()
            print("=" * 60)
            print("Step 4/7: Exporting encoder...")
            print("=" * 60)

            encoder = AudioEncoderTensorCache(model.encoder, model.decoder)

            # Use real mel spectrogram (same as sherpa-onnx script)
            audio = torch.rand(16000 * 30)
            audio = whisper.pad_or_trim(audio)
            mel = whisper.log_mel_spectrogram(audio, n_mels=n_mels).unsqueeze(0)

            n_layer_cross_k, n_layer_cross_v = encoder(mel)

            torch.onnx.export(
                encoder, mel, str(encoder_onnx),
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

            tokenizer = whisper.tokenizer.get_tokenizer(model.is_multilingual,
                                                           num_languages=model.num_languages)

            encoder_meta = {
                "model_type": f"whisper-{model_name}",
                "version": "1",
                "maintainer": "bofenghuang",
                "n_mels": dims.n_mels,
                "n_audio_ctx": n_audio_ctx,
                "n_audio_state": dims.n_audio_state,
                "n_audio_head": dims.n_audio_head,
                "n_audio_layer": dims.n_audio_layer,
                "n_vocab": n_vocab,
                "n_text_ctx": n_text_ctx,
                "n_text_state": n_text_state,
                "n_text_head": n_text_head,
                "n_text_layer": n_decoder_layers,
                "language": "it",
                "license": "apache-2.0",
                # Tokenizer metadata required by sherpa-onnx
                "sot_sequence": ",".join(list(map(str, tokenizer.sot_sequence))),
                "all_language_tokens": ",".join(
                    list(map(str, tokenizer.all_language_tokens))
                ),
                "all_language_codes": ",".join(tokenizer.all_language_codes),
                "sot": tokenizer.sot,
                "sot_index": tokenizer.sot_sequence.index(tokenizer.sot),
                "eot": tokenizer.eot,
                "blank_id": tokenizer.encode(" "),
                "is_multilingual": int(model.is_multilingual),
                "no_speech": tokenizer.no_speech,
                "non_speech_tokens": ",".join(
                    list(map(str, tokenizer.non_speech_tokens))
                ),
                "transcribe": tokenizer.transcribe,
                "translate": tokenizer.translate,
                "sot_prev": tokenizer.sot_prev,
                "sot_lm": tokenizer.sot_lm,
                "no_timestamps": tokenizer.no_timestamps,
            }
            add_meta_data(str(encoder_onnx), encoder_meta)
            print(f"  Saved: {encoder_onnx}")

            # Free encoder memory before decoder export
            del encoder
            del mel
            import gc
            gc.collect()

        # ---- Step 5: Export decoder ----
        print()
        print("=" * 60)
        print("Step 5/7: Exporting decoder...")
        print("=" * 60)

        decoder = TextDecoderTensorCache(model.decoder, n_text_ctx)

        # Use shorter audio for decoder trace to reduce memory
        audio = torch.rand(16000 * 10)  # 10s instead of 30s
        audio = whisper.pad_or_trim(audio)
        mel = whisper.log_mel_spectrogram(audio, n_mels=n_mels).unsqueeze(0)

        # Run encoder to get cross-attention KV caches
        encoder_wrapper = AudioEncoderTensorCache(model.encoder, model.decoder)
        n_layer_cross_k, n_layer_cross_v = encoder_wrapper(mel)
        del encoder_wrapper
        import gc
        gc.collect()

        n_audio = mel.shape[0]
        tokenizer = whisper.tokenizer.get_tokenizer(model.is_multilingual,
                                                       num_languages=model.num_languages)

        # First export: 3 tokens (initial prompt)
        tokens = torch.tensor([[tokenizer.sot, tokenizer.sot, tokenizer.sot]] * n_audio)
        n_layer_self_k_cache = torch.zeros(
            n_decoder_layers, n_audio, n_text_ctx, n_text_state)
        n_layer_self_v_cache = torch.zeros(
            n_decoder_layers, n_audio, n_text_ctx, n_text_state)
        offset = torch.zeros(1, dtype=torch.int64)

        logits, n_layer_self_k_cache, n_layer_self_v_cache = decoder(
            tokens, n_layer_self_k_cache, n_layer_self_v_cache,
            n_layer_cross_k, n_layer_cross_v, offset,
        )

        # Second export: 1 token (incremental decode step)
        offset = torch.tensor([tokens.shape[1]], dtype=torch.int64)
        tokens = torch.tensor([[tokenizer.sot]] * n_audio)
        logits, _, _ = decoder(
            tokens, n_layer_self_k_cache, n_layer_self_v_cache,
            n_layer_cross_k, n_layer_cross_v, offset,
        )

        decoder_onnx = output_dir / f"{model_name}-decoder.onnx"
        torch.onnx.export(
            decoder,
            (tokens, n_layer_self_k_cache, n_layer_self_v_cache,
             n_layer_cross_k, n_layer_cross_v, offset),
            str(decoder_onnx),
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
            output_names=["logits", "out_n_layer_self_k_cache",
                          "out_n_layer_self_v_cache"],
            dynamic_axes={
                "tokens": {0: "n_audio", 1: "n_tokens"},
                "in_n_layer_self_k_cache": {1: "n_audio"},
                "in_n_layer_self_v_cache": {1: "n_audio"},
                "n_layer_cross_k": {1: "n_audio", 2: "T"},
                "n_layer_cross_v": {1: "n_audio", 2: "T"},
            },
        )
        print(f"  Saved: {decoder_onnx}")

        # ---- Step 6: Quantize to int8 ----
        print()
        print("=" * 60)
        print("Step 6/7: Quantizing to int8...")
        print("=" * 60)
        from onnxruntime.quantization import quantize_dynamic, QuantType

        encoder_int8 = output_dir / f"{model_name}-encoder.int8.onnx"
        quantize_dynamic(
            model_input=str(encoder_onnx),
            model_output=str(encoder_int8),
            op_types_to_quantize=["MatMul"],
            weight_type=QuantType.QInt8,
        )
        print(f"  Saved: {encoder_int8}")

        decoder_int8 = output_dir / f"{model_name}-decoder.int8.onnx"
        quantize_dynamic(
            model_input=str(decoder_onnx),
            model_output=str(decoder_int8),
            op_types_to_quantize=["MatMul"],
            weight_type=QuantType.QInt8,
        )
        print(f"  Saved: {decoder_int8}")

        encoder_onnx.unlink()
        decoder_onnx.unlink()
        print("  Cleaned up unquantized files")

        # ---- Step 7: Extract tokens ----
        print()
        print("=" * 60)
        print("Step 7/7: Extracting tokens...")
        print("=" * 60)
        tokens_path = convert_tokens(model, output_dir)

        # ---- Summary ----
        print()
        print("=" * 60)
        print("DONE! Output files:")
        print("=" * 60)
        total = 0
        for f in sorted(output_dir.iterdir()):
            size_mb = f.stat().st_size / (1024 * 1024)
            total += size_mb
            print(f"  {f.name:50s} {size_mb:8.1f} MB")
        print(f"  {'TOTAL':50s} {total:8.1f} MB")


if __name__ == "__main__":
    main()
