# Research Report: Granularità dei timestamp nei sottotitoli (norme professionali, pratica ASR, rapporto con la diarizzazione)

**Data**: 2026-09-17
**Profondità**: standard
**Confidenza**: ALTA sulle norme (fonti primarie BBC e Netflix), MEDIA-ALTA sulla pratica ASR (WhisperX come pipeline rappresentativo)

## Sintesi esecutiva

La granularità dei cue nei sottotitoli professionali non è derivata dall'audio ma da regole editoriali di leggibilità: **una frase completa per cue**, velocità di lettura 160-180 parole/minuto (circa 15-20 caratteri/secondo), durata minima 5/6 di secondo e massima 7 secondi, sincronia con inizio-fine parlato entro 1,5 secondi. Il cambio di interlocutore è una regola di confine esplicita ("i sottotitoli di un nuovo parlante devono comparire quando il nuovo parlante inizia a parlare"), ma la diarizzazione è un **moltiplicatore per contenuti multi-parlante**, non un prerequisito: per i voice message monologo (il caso d'uso dominante di Anti-Vocale) le regole frase+pausa+cap bastano, e i turni di parola vengono già intercettati parzialmente dallo split sulle pause. Le soglie scelte nello slice 2 (pausa 700ms, cap 7s) risultano allineate alle norme; mancano il minimo di durata e l'estensione dell'out-time.

## Trovate

### 1. La regola primaria è editoriale: una frase per cue

La BBC è esplicita: "ogni sottotitolo dovrebbe comprendere una singola frase completa", con eccezioni per discorso veloce e frasi molto corte o molto lunghe [1]. La segmentazione avviene quindi sui confini sintattici (punto, punto interrogativo/esclamativo), non su finestre temporali fisse. Netflix impone la stessa struttura via limiti di durata e di riga [2]. Questo conferma che la granularità "sentence-level" è il target corretto per l'export SRT/VTT (lo slice 2 di TASK-487).

### 2. I numeri delle norme (fonti primarie)

| Parametro | Norma | Valore |
|---|---|---|
| Velocità di lettura | BBC [1] | 160-180 parole/minuto (0,3-0,375 s/parola) |
| Velocità di lettura (industry) | ebrary/TED [3][4] | 15-17 cps TV, fino a 20-21 cps contenuti per adulti |
| Durata massima cue | Netflix TTSG [2] | 7 secondi |
| Durata minima cue | Netflix TTSG [2] | 5/6 di secondo (circa 0,83 s; 20 frame a 24 fps) |
| Minimo per parola | BBC [1] | circa 0,3 s/parola (1,2 s per una frase di 4 parole) |
| Sincronia | BBC [1] | entrata sull'inizio del parlato; non oltre 1,5 s prima o dopo la fine |
| Out-time senza cue successivo | Netflix [2] | almeno mezzo secondo oltre la fine dell'audio |
| Righe per cue | BBC [1] | massimo 2 (16:9), spezzate su punteggiatura |
| "Subtitle zero" | BBC [1] | durata limitata a 3 frame |

### 3. Il cambio di parlante è un confine di cue esplicito, ma in fase di styling

BBC, sezione sincronizzazione: "quando due o più persone parlano, i sottotitoli del nuovo parlante devono, per quanto possibile, comparire quando il nuovo parlante inizia a parlare" [1]. La sezione "Identifying speakers" [1] tratta però il COME si distingue il parlante (colore preferito, virgolette, spostamento orizzontale, etichette "JOHN:"), cioè un problema di presentazione sovrapposto alla segmentazione, non sostitutivo di essa. Nei workflow automatici moderni la diarizzazione (chi parla quando) è un passaggio separato che viene allineato a posteriori sui timestamp delle parole [5][6].

### 4. La pratica ASR: pipeline a più stadi, diarizzazione opzionale

Il pipeline rappresentativo (WhisperX, riferimento de-facto per sottotitoli da ASR) è: VAD per isolare il parlato, segmentazione, tokenizzazione in frasi (nltk sent_tokenize, esplicitamente "for better subtitling"), forced alignment per timestamp a livello di parola, e diarizzazione pyannote OPZIONALE via token HF per etichettare i parlanti [5]. Conferma che (a) la granularità a frase è il target anche nel mondo automatico, (b) la diarizzazione è un componente aggiuntivo, non un prerequisito del timing.

### 5. Implicazioni per Anti-Vocale

- **Le soglie dello slice 2 sono nelle norme**: cap 7s = massimo Netflix; la media osservata sui device (5,6 s/cue a circa 15 cps) è dentro la velocità di lettura BBC/industry [verifica sui file reali del 2026-09-17].
- **Gap verso le norme (piccoli, candidati slice futuro)**: nessuna durata minima (Netflix: 5/6 s; oggi un "Sì." può durare 300 ms); nessuna estensione dell'out-time (Netflix: +0,5 s quando non segue un cue; alcuni decoder lo mascherano, come nota la BBC [1]).
- **Diarizzazione: serve prima? No, ma è il moltiplicatore giusto dopo.** Per voice message monologo (dominante) le regole attuali bastano. Per chiamate e conversazioni a due (la vocale di prova del maintainer del 2026-09-16 era esattamente un dialogo a due insegnanti) i turni sono il confine naturale: oggi vengono intercettati SOLO quando cadono in una pausa sopra 700 ms, e domande/risposte di parlanti diversi possono finire nello stesso cue. La via indicata dalle norme è: confini di cue sui turni + marcatura parlante (in VTT esistono i voice tag nativi `<v Speaker 1>`; in SRT l'etichetta "S1:" o il trattino dialogo). sherpa-onnx distribuisce già moduli di diarizzazione (pyannote 3.1) e la issue GH #83 del repo chiede esattamente questa feature; la risposta del maintainer dell'agosto 2026 la dava per fattibile on-device.
- **Collegamento con lo slice 3 di TASK-487**: il flag di granularità per modello (SENTENCE/CHUNK/NONE) può estendersi con la dimensione parlante quando la diarizzazione arriverà, senza cambiare il contratto dei formati.

## Valutazione di confidenza

- ALTA: numeri e regole BBC e Netflix (fonti primarie ufficiali, lette direttamente).
- MEDIA-ALTA: la pipeline WhisperX come rappresentativa della pratica (repo ufficiale, ma è UNA implementazione).
- MEDIA: l'affermazione che lo split su pause catturi "molti" turni di parola (inferenza dal materiale + dai file reali del device, non misurata sistematicamente).

## Fonti

1. BBC Subtitle Guidelines (fonte primaria, sezioni Presentation, Timing, Identifying speakers): https://www.bbc.co.uk/accessibility/forproducts/guides/subtitles
2. Netflix Timed Text Style Guide, General Requirements e Subtitle Timing Guidelines (fonti primarie): https://partnerhelp.netflixstudios.com/hc/en-us/articles/215758617-Timed-Text-Style-Guide-General-Requirements e https://partnerhelp.netflixstudios.com/hc/en-us/articles/360051554394-Timed-Text-Style-Guide-Subtitle-Timing-Guidelines
3. Subtitle display rates: characters per second and words per minute (ebrary, testo accademico su norma e pratica): https://ebrary.net/282575/language_literature/subtitle_display_rates_characters_second_words_minute
4. TED Translators, Subtitling tips (21 cps max, 42 caratteri/riga): https://www.ted.com/participate/translate/subtitling-tips
5. WhisperX (pipeline ASR-sottotitoli: VAD, sent_tokenize, forced alignment, diarizzazione opzionale): https://github.com/m-bain/whisperX
6. Speaker Recognition in Subtitles: Diarization Explained (dizionari di termini e workflow diarizzazione-sottotitoli): https://subvideo.ai/guides/speaker-recognition-in-subtitles
