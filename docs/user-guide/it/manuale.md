# Manuale utente di Anti-Vocale

Anti-Vocale trascrive i messaggi vocali sul tuo dispositivo Android, interamente offline. L'audio non lascia mai il telefono: la trascrizione avviene in locale con modelli AI aperti, senza account, senza servizi cloud, senza telemetria.

Manuale aggiornato alla versione 1.15.

## Indice

1. [Primi passi](#primi-passi)
2. [Scegliere un modello](#scegliere-un-modello)
3. [Trascrivere: il flusso quotidiano](#trascrivere-il-flusso-quotidiano)
4. [Note vocali WhatsApp, automaticamente](#note-vocali-whatsapp-automaticamente)
5. [Risultati: etichette, formati e finestra mobile](#risultati-etichette-formati-e-finestra-mobile)
6. [Audio lunghi, coda e ritentativi](#audio-lunghi-coda-e-ritentativi)
7. [Modelli community e importazioni](#modelli-community-e-importazioni)
8. [Tasker e automazione](#tasker-e-automazione)
9. [Impostazioni per app](#impostazioni-per-app)
10. [Privacy](#privacy)
11. [Risoluzione dei problemi](#risoluzione-dei-problemi)
12. [Domande frequenti](#domande-frequenti)

Le sezioni 8a, 8b e 8c coprono le superfici di automazione: il controllo cartella programmato, l'offload remoto e il provider Open Transcribe.

## Primi passi

1. Installa Anti-Vocale dal tuo store (Google Play o F-Droid) o da un APK rilasciato su GitHub.
2. Apri l'app una volta. Nella scheda **Modelli** trovi i modelli integrati disponibili per il download.
3. Scarica un modello. Per la maggior parte delle persone la prima scelta consigliata è **Parakeet TDT (stock int8, 640 MB)**: veloce, leggero e copre 25 lingue europee.
4. Per trascrivere, condividi un messaggio vocale da una qualsiasi app di messaggistica (WhatsApp, Telegram, Signal e altre) verso Anti-Vocale. Compare una notifica durante l'elaborazione, poi una seconda notifica con il testo.
5. Tocca la notifica del risultato per copiare, condividere o rimandare il testo nella chat da cui proviene il vocale.

Non serve altra configurazione. Tutto ciò che segue è facoltativo.

## Scegliere un modello

I modelli differiscono per dimensione, velocità, copertura linguistica e precisione. La scheda Modelli mostra i fatti essenziali di ogni scheda prima del download. Orientamento rapido:

| Modello | Dimensione | Lingue | Note |
|---|---|---|---|
| Parakeet TDT stock int8 | 640 MB | 25 europee | Veloce e leggero; la scelta predefinita |
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
- Ogni trascrizione resta nella scheda **Cronologia** con modello usato, durata e tempo di elaborazione. Tieni premuta una voce per riprovare, copiare, eliminare o segnalare un risultato sbagliato via email; **Ri-trascrivi** fa passare lo stesso audio per un modello diverso a tua scelta.
- Con un modello **Whisper o SenseVoice**, ogni trascrizione espansa porta un chip lingua: la bandiera della lingua che il modello ha rilevato, con un tocco per verificare o bloccare quando il tiro è sbagliato (i modelli piccoli ogni tanto sbagliano). Le altre famiglie di modelli non riportano una lingua rilevata, quindi il chip resta spento per loro; l'interruttore nelle Impostazioni lo dice e resta disattivato.
- Con Salvataggio automatico (Impostazioni) ogni trascrizione viene scritta anche come file .txt in una cartella a tua scelta.

## Note vocali WhatsApp, automaticamente

Anti-Vocale può trascrivere le nuove note vocali WhatsApp nel momento in cui arrivano, senza alcun tocco. Il testo finisce nella scheda Cronologia e in una notifica di risultato; se Android blocca un avvio in background (per esempio a schermo spento), una notifica con un tocco avvia la trascrizione al posto suo.

La configurazione richiede un minuto, in **Impostazioni > Note vocali**:

1. Attiva **Trascrizione note vocali** e concedi l'accesso all'audio quando Android lo chiede. Se hai rifiutato due volte, Android non mostra più nessun dialogo; il suggerimento rosso che compare al suo posto apre la pagina di sistema dove il permesso si può ancora concedere.
2. Scegli una volta sola la cartella WhatsApp Voice Notes attraverso il selettore guidato. Una cartella sbagliata viene rifiutata sul momento; puoi cambiarla o rimuoverla in qualunque momento dalla stessa scheda.
3. Facoltativo: attiva anche il **riconoscimento del mittente** e concedi l'accesso alle notifiche. La riga della Cronologia viene così etichettata con il nome del mittente.

Due regole oneste governano le etichette. Un nome compare solo quando la notifica della nota lo conferma, cioè la notifica è arrivata mentre la funzione era accesa e la durata dichiarata corrisponde all'audio: i nomi non vengono mai indovinati. E le note scaricate a mano (con l'auto-download spento) restano senza etichetta, perché la loro notifica è troppo vecchia per essere attendibile. La lista d'attesa delle notifiche recenti vive solo in memoria e scompare con la funzione spenta.

La privacy è lo stesso contratto di sempre: con il rilevamento attivo legge soltanto l'elenco della cartella delle note vocali WhatsApp; l'audio di una nota viene letto solo per trascriverlo, interamente su questo dispositivo; con la funzione spenta non viene letto nulla.

Un avvertimento, detto chiaramente: sui telefoni con gestori di batteria aggressivi, le note che arrivano mentre il sistema ha sospeso l'app possono andare perse finché non si risveglia. Se ti affidi alla modalità automatica, escludere Anti-Vocale dall'ottimizzazione della batteria la rende affidabile. Solo WhatsApp per ora; Telegram è in roadmap.

## Risultati: etichette, formati e finestra mobile

- **Etichette dei parlanti**: si attivano dalle Impostazioni e le trascrizioni delle conversazioni ricevono l'attribuzione del parlante frase per frase, resa come turni SPEAKER nel risultato e in ogni esportazione.
- **Condividi più del testo**: da una riga di risultato, condividi come testo semplice, testo con timestamp, SRT o VTT. I formati con i tempi richiedono una trascrizione che porta indicatori temporali. "Condividi come…" si attiva dalle Impostazioni.
- **Picture-in-Picture**: mentre una trascrizione è in corso, una finestra mobile può mostrare il testo che appare dal vivo, così puoi continuare a usare le altre app.
- **Ri-trascrivi**: riprova qualsiasi voce con un modello diverso direttamente dalla sua riga, e confronta.

## Audio lunghi, coda e ritentativi

- Qualsiasi durata funziona con qualsiasi modello: le registrazioni lunghe vengono divise e ricomposte automaticamente. (Le versioni precedenti avevano un limite di 6:40 con Parakeet; è stato eliminato.)
- Condividi più messaggi di seguito: entrano in coda. Ogni elemento in coda si può annullare singolarmente dalla propria notifica mentre un'altra trascrizione è in corso.
- Una trascrizione fallita si può riprovare con un tocco dalla scheda Cronologia.

## Modelli community e importazioni

Il catalogo integrato non copre tutte le lingue. Anti-Vocale include un catalogo community di modelli extra che si importano con due tocchi: scheda Modelli, Avanzate, ONNX Sherpa, Importa dal catalogo, filtra per la tua lingua, tocca il modello, conferma. Il catalogo cresce di continuo e oggi conta oltre 30 voci validate: arabo (dialettale, completo e leggero), tedesco (streaming e un fine-tune Whisper), spagnolo (streaming e Moonshine leggero), svizzero tedesco, ebraico, persiano, russo, vietnamita, coreano, ucraino, sei lingue indiane (bengalese, gujarati, hindi, marathi, tamil, telugu), Canary Flash per inglese e francese, SenseVoice, Dolphin, Orukeet (un fine-tune di Parakeet che copre 24 lingue compreso l'italiano) e un Whisper Tiny multilingue a bassa RAM. Se la tua lingua manca, apri una issue: è così che le voci si aggiungono.

Gli utenti avanzati possono inoltre:
- importare un modello dall'URL di un repository Hugging Face o da un link a una voce di catalogo (ramo avanzato nello stesso dialogo);
- importare un insieme di file modello da una cartella sul telefono;
- esportare un modello scaricato come **bundle offline** e importarlo su un altro dispositivo, così un modello scaricato una volta si sposta senza ricaricarlo;
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

## Controllo cartella programmato

Anti-Vocale può sorvegliare le cartelle che scegli e trascrivere automaticamente i nuovi file audio: Impostazioni, Controllo cartella programmato, Osserva una cartella. Le scansioni girano con la frequenza che il sistema concede; i risparmiatori di batteria aggressivi possono ritardarle, e il pulsante Scansiona ora funziona sempre. Ogni nuovo file audio viene trascritto con il modello selezionato e finisce nella scheda Cronologia come qualsiasi altra esecuzione. Da Android 11 in poi il selettore cartelle può aprire posizioni dello storage condiviso come la cartella media di un'app di messaggistica, il che rende possibile la trascrizione automatica dei vocali di una chat senza condividerli uno a uno.

## Offload remoto (sperimentale)

Se gestisci un tuo server di trascrizione, Anti-Vocale può delegare ad esso le trascrizioni lunghe o pesanti: Impostazioni, Offload remoto (sperimentale). Funziona qualsiasi server compatibile OpenAI che parli l'API /v1/audio/transcriptions (OmniVoice, il server whisper.cpp e altri). L'informativa in app lo dice chiaramente: con la funzione attiva, l'audio delle trascrizioni che deleghi viene inviato solo all'indirizzo del server che inserisci, ovunque si trovi (la tua rete di casa o qualsiasi punto di internet); tutto il resto viene trascritto sul dispositivo, e la funzione è spenta di default.

## Open Transcribe (sperimentale)

Le app che parlano il contratto aperto Open Transcribe, come Forkgram (un client Telegram), possono usare Anti-Vocale come trascrittore dei messaggi vocali integrato: attiva **Impostazioni > Automazione e offload > API di trascrizione Open Transcribe** (spenta per impostazione predefinita), poi scegli Anti-Vocale nelle impostazioni di trascrizione offline del client. I messaggi vocali vengono trascritti con i modelli dell'app stessa e l'audio non lascia mai il dispositivo. Spegnere l'interruttore toglie di nuovo l'app dai selettori dei client.

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

**Le note vocali automatiche smettono di arrivare.**
Due cause consuete. Se la cartella di WhatsApp è stata eliminata e ricreata (una reinstallazione, uno strumento di pulizia), il permesso della cartella salvato può morire: apri Impostazioni > Note vocali e scegli di nuovo la cartella dalla scheda. Se il tuo telefono sospende le app in background in modo aggressivo, le note che arrivano mentre l'app è sospesa vanno perse finché non si risveglia: l'esenzione batteria di cui sopra è la soluzione, e per questa funzione conta più che per la condivisione manuale.

## Domande frequenti

**Funziona senza internet?**
Sì. Dopo aver scaricato un modello, la trascrizione funziona interamente offline.

**Quali app di messaggistica sono supportate?**
Qualunque app possa condividere un file audio. L'azione di invio attualmente è mirata a un sottoinsieme di app (WhatsApp, Telegram e altre rilevate automaticamente).

**Dove sono le mie trascrizioni?**
Nella scheda Cronologia e, a scelta, come file .txt in una cartella che scegli tu. Nulla è salvato altrove.

**Può trascrivere i vocali automaticamente appena arrivano?**
Sì, per WhatsApp: attiva Impostazioni > Note vocali, concedi l'accesso all'audio e scegli una volta la cartella WhatsApp Voice Notes. Le nuove note vengono trascritte appena arrivano, e la riga può portare il nome del mittente. Il controllo cartella programmato resta la strada generica per la cartella media di qualsiasi app (Android 11+), e Telegram è in roadmap. Sui telefoni con gestori di batteria aggressivi, le note che arrivano mentre l'app è sospesa possono andare perse: l'esenzione batteria rende affidabile la modalità automatica.

**Perché un vocale non ha il nome del mittente?**
Il nome compare solo se la notifica della nota è arrivata con il riconoscimento del mittente attivo e la durata dichiarata corrisponde all'audio. Le note scaricate a mano (auto-download spento) e le note la cui notifica non è mai arrivata restano senza nome, di proposito: un nome sbagliato è peggio di nessun nome.

**Perché ci sono due store (Play e F-Droid)?**
Stessa app, stesse funzioni. F-Droid la compila da sorgente senza componenti proprietari; Play aggiunge la segnalazione automatica dei crash.

**È davvero privata?**
Sì. Il codice sorgente è aperto; puoi verificare che nessun dato lascia il dispositivo. Vedi l'informativa privacy nel repository.

---

Trovato un errore o manca qualcosa? Apri una segnalazione su [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues).
