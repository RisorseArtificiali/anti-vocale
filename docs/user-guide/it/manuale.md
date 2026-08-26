# Manuale utente di Anti-Vocale

Anti-Vocale trascrive i messaggi vocali sul tuo dispositivo Android, interamente offline. L'audio non lascia mai il telefono: la trascrizione avviene in locale con modelli AI aperti, senza account, senza servizi cloud, senza telemetria.

Manuale aggiornato alla versione 1.11.

## Indice

1. [Primi passi](#primi-passi)
2. [Scegliere un modello](#scegliere-un-modello)
3. [Trascrivere: il flusso quotidiano](#trascrivere-il-flusso-quotidiano)
4. [Audio lunghi, coda e ritentativi](#audio-lunghi-coda-e-ritentativi)
5. [Modelli community e importazioni](#modelli-community-e-importazioni)
6. [Tasker e automazione](#tasker-e-automazione)
7. [Impostazioni per app](#impostazioni-per-app)
8. [Privacy](#privacy)
9. [Risoluzione dei problemi](#risoluzione-dei-problemi)
10. [Domande frequenti](#domande-frequenti)

## Primi passi

1. Installa Anti-Vocale dal tuo store (Google Play o F-Droid) o da un APK rilasciato su GitHub.
2. Apri l'app una volta. Nella scheda **Modelli** trovi i modelli integrati disponibili per il download.
3. Scarica un modello. Per la maggior parte delle persone la prima scelta consigliata è **Parakeet TDT (stock int8, 464 MB)**: veloce, leggero e copre 25 lingue europee.
4. Per trascrivere, condividi un messaggio vocale da una qualsiasi app di messaggistica (WhatsApp, Telegram, Signal e altre) verso Anti-Vocale. Compare una notifica durante l'elaborazione, poi una seconda notifica con il testo.
5. Tocca la notifica del risultato per copiare, condividere o rimandare il testo nella chat da cui proviene il vocale.

Non serve altra configurazione. Tutto ciò che segue è facoltativo.

## Scegliere un modello

I modelli differiscono per dimensione, velocità, copertura linguistica e precisione. La scheda Modelli mostra i fatti essenziali di ogni scheda prima del download. Orientamento rapido:

| Modello | Dimensione | Lingue | Note |
|---|---|---|---|
| Parakeet TDT stock int8 | 464 MB | 25 europee | Veloce e leggero; la scelta predefinita |
| Parakeet TDT SmoothQuant | 862 MB | 25 europee | Più preciso, più pesante; richiede più RAM |
| Whisper Turbo | 988 MB | 101 | Miglior equilibrio nella famiglia Whisper |
| Whisper Medium | 903 MB | 101 | Più lento di Turbo, non migliore per la maggior parte degli audio |
| Whisper Small | 358 MB | 101 | Il più leggero dei Whisper; qualità discreta |
| Whisper Distil Italian | 938 MB | Solo italiano | Migliore precisione italiana del set integrato |
| Qwen3-ASR | 938 MB | Multilingue | Architettura alternativa |
| Nemotron streaming | 640 MB | Multilingue | Mostra il testo mentre parli (streaming) |
| GigaAM v3 | 326 MB | Russo | Specialista russo |

Regole pratiche:
- Se trascrivi soprattutto una lingua, un modello specializzato (Distil Italian, GigaAM) batte un generalista della stessa dimensione.
- Se il telefono ha 4 GB di RAM o meno, preferisci modelli sotto i 500 MB.
- I modelli Gemma (elencati a parte nella scheda Modelli) sono modelli linguistici più grandi che sanno anche trascrivere. Interessanti per sperimentare, ma più pesanti e lenti dei modelli ASR dedicati.

## Trascrivere: il flusso quotidiano

- Condividi un vocale verso Anti-Vocale. L'elaborazione parte subito, anche a schermo spento.
- La notifica del risultato offre: **Copia**, **Condividi** e, quando l'app di origine è supportata, **Invia a [App]** che incolla il testo direttamente nella chat da cui proveniva il vocale.
- Con Copia automatica attiva (Impostazioni), il testo è già negli appunti quando arriva la notifica; la notifica te lo dice.
- Ogni trascrizione resta nella scheda **Cronologia** con modello usato, durata e tempo di elaborazione. Tieni premuta una voce per riprovare, copiare, eliminare o segnalare un risultato sbagliato via email.
- con Salvataggio automatico (Impostazioni) ogni trascrizione viene scritta anche come file .txt in una cartella a tua scelta.

## Audio lunghi, coda e ritentativi

- Qualsiasi durata funziona con qualsiasi modello: le registrazioni lunghe vengono divise e ricomposte automaticamente. (Le versioni precedenti avevano un limite di 6:40 con Parakeet; è stato eliminato.)
- Condividi più messaggi di seguito: entrano in coda. Ogni elemento in coda si può annullare singolarmente dalla propria notifica mentre un'altra trascrizione è in corso.
- Una trascrizione fallita si può riprovare con un tocco dalla scheda Cronologia.

## Modelli community e importazioni

Il catalogo integrato non copre tutte le lingue. Anti-Vocale include un catalogo community di modelli extra che si importano con due tocchi: scheda Modelli, Avanzate, ONNX Sherpa, Importa dal catalogo, filtra per la tua lingua, tocca il modello, conferma. I modelli community attuali includono arabo (dialettale), russo, spagnolo, tedesco (streaming) e svizzero tedesco.

Gli utenti avanzati possono inoltre:
- importare un modello dall'URL di un repository Hugging Face o da un link a una voce di catalogo (ramo avanzato nello stesso dialogo);
- importare un insieme di file modello da una cartella sul telefono;
- puntare l'app a un indice di catalogo diverso (azione "cambia" accanto alla fonte del catalogo) mantenuto da chiunque, per esempio la tua community.

Il formato di importazione e i requisiti dei file sono documentati in [modelli esterni](../../external-models.md).

## Tasker e automazione

Anti-Vocale accetta un broadcast che Tasker (o qualunque app di automazione) può inviare per trascrivere un file senza toccare l'interfaccia:

```
Action: com.antivocale.app.PROCESS_REQUEST
Extras: request_type=audio, file_path=/percorso/del/audio, task_id=tuo-id
Opzionale: backend_id=<id modello> per scegliere il modello per quella richiesta
```

Il risultato torna con un broadcast di risposta. La guida completa con esempi è nella [guida Tasker](../../TASKER_GUIDE.md).

## Impostazioni per app

Per ogni app da cui condividi (WhatsApp, Telegram, ...) puoi configurare separatamente: se mostrare l'azione di invio, se copiare automaticamente e il suono di notifica. Scheda Impostazioni, Impostazioni per app.

## Privacy

- La trascrizione è 100% sul dispositivo. Nessun audio, testo o metadato lascia mai il tuo telefono.
- L'app non ha il permesso internet per trascrivere; la rete si usa solo quando scarichi esplicitamente un modello.
- La cronologia resta sul dispositivo ed è tua: svuotala quando vuoi dalla scheda Cronologia.
- La build Play include la segnalazione crash di Crashlytics (visibile e disattivabile nelle impostazioni Android); la build F-Droid non ne ha.

## Risoluzione dei problemi

**La trascrizione non finisce mai / la notifica scompare.**
Alcuni marchi (Vivo, OPPO, alcuni Xiaomi e Samsung) sospendono le app in background in modo aggressivo. Apri Anti-Vocale una volta e, se te lo propone, concedi l'esenzione batteria; oppure trova l'app nelle impostazioni batteria e impostala Senza restrizioni. L'app rileva la situazione e la spiega in una notifica quando capita.

**"Memoria insufficiente" o crash con modelli grandi.**
I modelli indicano la loro dimensione sulla scheda. Su telefoni con 4 GB di RAM o meno usa modelli sotto i 500 MB. Se una trascrizione fallisce con un errore di memoria, prova un file più corto, un modello più piccolo o chiudere altre app.

**La qualità della trascrizione è scarsa.**
Prova un modello specializzato per la tua lingua (vedi la tabella sopra). Tieni premuta la voce sbagliata in Cronologia e usa Segnala per inviarci i dettagli (modello, durata, tempi; l'estratto della trascrizione solo se scegli di includerlo).

**NNAPI fa crashare l'app.**
Se hai attivo il provider NNAPI nelle Impostazioni e l'app crasha, al prossimo avvio torna automaticamente alla CPU. NNAPI dipende molto dal chipset del telefono; se crasha ripetutamente, lascialo su CPU.

## Domande frequenti

**Funziona senza internet?**
Sì. Dopo aver scaricato un modello, la trascrizione funziona interamente offline.

**Quali app di messaggistica sono supportate?**
Qualunque app possa condividere un file audio. L'azione di invio attualmente è mirata a un sottoinsieme di app (WhatsApp, Telegram e altre rilevate automaticamente).

**Dove sono le mie trascrizioni?**
Nella scheda Cronologia e, a scelta, come file .txt in una cartella che scegli tu. Nulla è salvato altrove.

**Può trascrivere i vocali automaticamente appena arrivano?**
Non ancora. È in roadmap; oggi condividere richiede un tocco.

**Perché ci sono due store (Play e F-Droid)?**
Stessa app, stesse funzioni. F-Droid la compila da sorgente senza componenti proprietari; Play aggiunge la segnalazione automatica dei crash.

**È davvero privata?**
Sì. Il codice sorgente è aperto; puoi verificare che nessun dato lascia il dispositivo. Vedi l'informativa privacy nel repository.

---

Trovato un errore o manca qualcosa? Apri una segnalazione su [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues).
