package com.antivocale.app.transcription

import com.antivocale.app.data.ModelFamily

/**
 * TASK-513 (GH #93): detects the model family from the candidate file names,
 * so a manual import can prefill the family instead of failing with a
 * transducer-shaped validation error.
 *
 * Detection probes every family's own copy plan against the candidate set:
 * the plan (the same single role table the import validates with) succeeds
 * exactly when that family can consume the set. A set matching several plans
 * (Whisper and Canary share their file shape) comes back [Result.Ambiguous];
 * the caller narrows it with filename/URL hints ([narrow]) or asks the user.
 * One shape is genuinely two families: model.onnx + tokens.txt fits both CTC
 * and SenseVoice, so that pair always comes back Ambiguous (the model.onnx
 * metadata is the only true discriminator, read later by the importer).
 * The transducer plan's mismatch guard throws when a transducer-shaped set
 * reaches another family; that throw is caught and treated as non-candidate.
 */
object ModelFamilyDetector {

    sealed class Result {
        /** Exactly one family consumes the set. */
        data class Detected(val family: ModelFamily) : Result()

        /** Several families share this file shape; narrow by hints or ask the user. */
        data class Ambiguous(val candidates: List<ModelFamily>) : Result()

        /** No family's copy plan matches: the set is not importable as-is. */
        data object Unknown : Result()
    }

    fun detect(files: List<String>): Result {
        // Plans are superset-tolerant: the Whisper plan happily maps a
        // transducer set and leaves the joiner unmapped. Exactness is the
        // discriminator: a plan that consumes EVERY candidate file is an
        // exact shape match; partial matches rank below it (and only
        // surface when nothing exact exists).
        val matching = ModelFamily.entries.mapNotNull { family ->
            val plan = runCatching {
                ModelFamilySupport.forFamily(family).buildCopyPlan(files)
            }.getOrNull() ?: return@mapNotNull null
            family to plan
        }
        val exact = matching.filter { it.second.keys.size == files.size }
        // A [encoder*.onnx, tokens] set is CTC's exact shape (with or without
        // sidecar files: exact match, or the sole survivor of the partial
        // tier), but it is also exactly a transducer/whisper/canary export
        // one or two files short: an interrupted copy or partial download.
        // Importing it as CTC succeeds at copy time and dies at native load
        // with no hint of what was wrong; routed to the chooser instead,
        // every offered pick fails fast naming what is missing, and the
        // folder name can still narrow a genuine full export. A ctc-hinted
        // name (GigaAM's v3_ctc, istupakov's encoder-ctc) stays detected;
        // a generic model.onnx set is the pre-existing CTC/SenseVoice
        // ambiguity, untouched here.
        val soleDetected = when {
            exact.size == 1 -> exact[0].first
            exact.isEmpty() && matching.size == 1 -> matching[0].first
            else -> null
        }
        // GH #89: a truncated MOONSHINE set must land here too. The v1 file
        // names carry no "encoder"/"decoder" substring (preprocess/encode/
        // uncached_decode/cached_decode), so without the moonshine-name tell a
        // lost decode file would leave CTC the sole partial match and import a
        // moonshine preprocess as a CTC encoder: no metadata gate, native
        // _Exit(-1) at first load. The v2 names (encoder_model.* /
        // decoder_model_merged.*) DO contain "encoder", so both generations
        // reach this guard through one tell.
        // Tight tells only (review round): MoonshineSupport::isRoleName, the
        // same container-gated role test the plan uses (single definition,
        // so a future role rename cannot leave this list stale), so a stray
        // session.ort or an unrelated "preprocessing-notes.txt" sidecar
        // cannot flip a clean CTC detection into the chooser. The
        // verification round caught "encode" missing here: a v1 folder
        // reduced to encode+tokens imported as CTC and died at native load,
        // the exact class this guard exists for.
        val moonshineNames = files.any(MoonshineSupport::isRoleName)
        // MOONSHINE joins the offered picks only when a moonshine-shaped file
        // is actually present: a pick whose import can only fail is noise.
        if (soleDetected == ModelFamily.CTC &&
            (files.any { it.contains("encoder", ignoreCase = true) } || moonshineNames) &&
            files.none { it.contains("decoder", ignoreCase = true) } &&
            files.none { it.contains("ctc", ignoreCase = true) }
        ) {
            val base = listOf(ModelFamily.TRANSDUCER, ModelFamily.CTC,
                ModelFamily.WHISPER, ModelFamily.CANARY)
            return Result.Ambiguous(if (moonshineNames) base + ModelFamily.MOONSHINE else base)
        }
        return when {
            exact.size == 1 -> Result.Detected(exact[0].first)
            exact.size > 1 -> Result.Ambiguous(exact.map { it.first })
            matching.size == 1 -> Result.Detected(matching[0].first)
            matching.isEmpty() -> Result.Unknown
            else -> Result.Ambiguous(matching.map { it.first })
        }
    }

    /**
     * Narrows an ambiguous candidate set by a filename or URL hint
     * ("canary", "whisper"...). Null when the hint matches none of them.
     */
    fun narrow(candidates: List<ModelFamily>, hint: String?): ModelFamily? {
        if (hint.isNullOrBlank()) return null
        // The hint is a path: only the TERMINAL segment names this folder.
        // Ancestor directories (Download/whisper-alternatives/canary-180m)
        // must not vote, and neither must ordering: any candidate token in
        // the terminal segment resolves to that candidate (whisper and
        // canary never share a folder name in practice; if both matched,
        // the chooser is the honest answer). (Code-review round 2, 513.)
        val terminal = hint.trimEnd('/')
            .substringAfterLast('/')
            .lowercase()
        // Word-boundary match, symmetric in shape: the candidate splits by
        // its own enum spelling (SENSE_VOICE -> sense, voice) and matches
        // when ALL its words appear as terminal tokens. A bare 'ctc' inside
        // an unrelated word (DetectCore) does not vote; 'sense_voice' and
        // 'sense-voice' both match SenseVoice. (Round 4.)
        val tokens = terminal.split(Regex("[^a-z0-9]")).filter { it.isNotEmpty() }
        val matchedWords = candidates.map { candidate ->
            candidate to candidate.name.lowercase().split('_')
        }.filter { (_, words) -> words.all { it in tokens } }
        // A terminal name carrying tokens of MULTIPLE candidates stays a tie
        // the hint cannot break: the chooser decides, not enum order (round 3:
        // firstOrNull picked CTC for exactly that name, importing with the
        // wrong config). KNOWN LIMITATION (GH #89 review): every published
        // dolphin repo name also carries 'ctc', so a dolphin set opens the
        // chooser where a CTC pick imports structurally and dies at native
        // load; splitting "ctc as architecture qualifier" from "genuinely
        // mixed repo" needs the file shape or a chooser preselect, tracked
        // on TASK-623.
        return if (matchedWords.size == 1) matchedWords[0].first else null
    }
}
