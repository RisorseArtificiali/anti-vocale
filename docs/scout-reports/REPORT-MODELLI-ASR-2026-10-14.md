# Report: modelli ASR candidati per il catalogo esterno di Anti-Vocale

Data: 14 ott 2026. Fonte: ricerca web (sherpa-onnx docs, HuggingFace NVIDIA, benchmark 2026). Prodotto da Hermes (gateway paolo/default) su richiesta di Paolo.
Vincoli: modelli compatibili sherpa-onnx (import community già supportato dall'app); nessuna distinzione fascia hardware — il catalogo può contenere sia modelli per telefoni potenti sia per telefoni modesti.

## Contesto installazioni (ott 2026, 1.186 totali)
IT 329 (27,7%), DE 217 (18,3%), RU 61, USA 52, IL 41, IR 36, NL 33, BR 31, FR 31, IN 23, ES 21, UK 20, CH 17, PL 16, UA 16. IT+DE = 46%. IL+IR+RU+UA = 154 (13%).

---

## Candidato 1 — Omnilingual ASR 300M (Meta) — 1600+ lingue
- sherpa-onnx: `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`
- CTC int8, ~348 MB, solo non-streaming, sample rate libero (no obbligo 16 kHz)
- Licenza: verificare il file LICENSE nel tarball (modello Meta;_CODE considerare il term di uso)
- Copre: ebraico, farsi, russo, ucraino, arabo (dialetti), hindi, indonesiano, swahili, e ~1600 lingue — l'unica opzione on-device per IL/FA entro sherpa-onnx
- Uso consigliato: fallback universale per lingue oggi non coperte (IL 41, IR 36, code lunghe)
- Docs: https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html

## Candidato 2 — Parakeet TDT 0.6b v3 (NVIDIA) — 25 lingue europee
- Stessa famiglia del Parakeet TDT già in app (v2 = solo EN); upgrade naturale, import sherpa-onnx già pronto (`multi-parakeet_tdt_0.6b_v3`)
- 600M param, CC-BY-4.0, auto-detect lingua senza prompt
- WER FLEURS: IT 3,0 / DE 5,0 / FR 5,2 / ES 3,5 / PT 4,8 / NL 7,5 / PL 7,3 / RU 5,5 / UK 6,8 / EL 20,7 / HU 15,7 / RO 12,4 / CS 11,0 / DA 18,4 / FI 13,2 / SV 15,1 / ET 17,7 / LT 20,4 / LV 22,8 / MT 20,5 / HR 12,5 / SK 8,8 / SL 24,0 / BG 12,6 / EN 4,9
- Media multilingua 9,7% WER (meglio di Whisper-large-v3 9,9% con meno della metà dei parametri)
- Uso consigliato: upgrade del core europeo (IT/DE/FR/ES/PT/RU/UK/PL + est-Europa)
- HF: https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3

## Candidato 3 — Moonshine v2 (Useful Sensors) — modelli monolingua leggeri
- Release sherpa-onnx 2026-02-27: base/ar, base/es, base/uk, base/vi, base/ja, base/zh, tiny/ja, tiny/ko, base/en
- Modello dedicato **arabo** (Egitto 15 + Medio Oriente) e **ucraino** (16 utenti): meglio di un multilingua generico
- Non copre ebraico né farsi
- Uso consigliato: opzione leggera per AR/UK/ES/VI quando il multilingua pesa troppo
- Docs: https://k2-fsa.github.io/sherpa/onnx/moonshine/models-v2.html

## Scartati
- Canary-Qwen 2.5B, Granite Speech 8B: troppi parametri per on-device
- Whisper Large v3 Turbo (809M): pesante per il target dichiarato
- Canary-1B-v2 (25 lingue, 8,1% WER): 1B param, borderline mobile

## Note critiche
- Ebraico (41 utenti) e farsi (36): SOLO Omnilingual li copre entro sherpa-onnx. Test qualità prioritario.
- jch (autore galene-stt, thread LinuxFr) segnala Whisper-large deludente con accenti: conferma la necessità di alternative per il parlato reale.
- Verificare per ogni candidato: dimensione reale int8, licenza ridistribuibile nel catalogo, RAM a runtime.

## Azione richiesta
Valutare integrazione nel catalogo esterno (download on-demand tipo sherpa-onnx): priorità 1 Parakeet v3 (sostituisce/affianca il core), priorità 2 Omnilingual (nuove lingue), priorità 3 Moonshine v2 (AR/UK leggero).
