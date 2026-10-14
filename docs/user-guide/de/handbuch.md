# Anti-Vocale Benutzerhandbuch

Anti-Vocale transkribiert Sprachnachrichten auf deinem Android-Gerät, komplett offline. Audio verlässt dein Telefon nie: Die Transkription läuft lokal mit offenen KI-Modellen, ohne Konto, ohne Cloud-Dienst, ohne Telemetrie.

Handbuch aktualisiert für Version 1.15.

## Inhalt

1. [Erste Schritte](#erste-schritte)
2. [Modell wählen](#modell-wählen)
3. [Transkribieren: der Alltag](#transkribieren-der-alltag)
4. [WhatsApp-Sprachnachrichten, automatisch](#whatsapp-sprachnachrichten-automatisch)
5. [Ergebnisse: Labels, Formate und das schwebende Fenster](#ergebnisse-labels-formate-und-das-schwebende-fenster)
6. [Lange Audios, Warteschlange und erneute Versuche](#lange-audios-warteschlange-und-erneute-versuche)
7. [Community-Modelle und Importe](#community-modelle-und-importe)
8. [Tasker und Automatisierung](#tasker-und-automatisierung)
9. [App-spezifische Einstellungen](#app-spezifische-einstellungen)
10. [Datenschutz](#datenschutz)
11. [Fehlerbehebung](#fehlerbehebung)
12. [FAQ](#faq)

Die Abschnitte 8a, 8b und 8c behandeln die Automatisierung: die geplante Ordnerüberwachung, das Remote-Offload und den Open-Transcribe-Anbieter.

## Erste Schritte

1. Installiere Anti-Vocale aus deinem Store (Google Play oder F-Droid) oder über eine Release-APK von GitHub.
2. Öffne die App einmal. Auf dem Tab **Modelle** siehst du die integrierten Modelle, die zum Herunterladen bereitstehen.
3. Lade ein Modell herunter. Für die meisten ist **Parakeet TDT (stock int8, 640 MB)** die empfohlene erste Wahl: schnell, klein, und es deckt 25 europäische Sprachen ab.
4. Zum Transkribieren teile eine Sprachnachricht aus einem beliebigen Messenger (WhatsApp, Telegram, Signal und andere) an Anti-Vocale. Während der Verarbeitung erscheint eine Benachrichtigung, danach eine zweite mit dem Text.
5. Tippe auf die Ergebnis-Benachrichtigung, um den Text zu kopieren, zu teilen oder in den Chat zurückzusenden, aus dem er stammt.

Weitere Konfiguration ist nicht nötig. Alles unten ist optional.

## Modell wählen

Modelle unterscheiden sich in Größe, Geschwindigkeit, Sprachabdeckung und Genauigkeit. Der Tab Modelle zeigt die wichtigsten Fakten auf jeder Karte an, bevor du herunterlädst. Schnelle Orientierung:

| Modell | Größe | Sprachen | Hinweise |
|---|---|---|---|
| Parakeet TDT stock int8 | 640 MB | 25 europäische | Schnell und leicht; die Standard-Empfehlung |
| Parakeet TDT SmoothQuant | 862 MB | 25 europäische | Genauer, aber schwerer; braucht mehr RAM |
| Whisper Turbo | 988 MB | 101 | Bestes Gleichgewicht in der Whisper-Familie |
| Whisper Medium | 903 MB | 101 | Langsamer als Turbo, bei den meisten Audios nicht besser |
| Whisper Small | 358 MB | 101 | Leichtestes Whisper; ordentliche Qualität |
| Whisper Distil Italian | 938 MB | Nur Italienisch | Beste Italienisch-Genauigkeit unter den integrierten Modellen |
| Qwen3-ASR | 938 MB | Mehrsprachig | Alternative Architektur |
| Nemotron streaming | 640 MB | Mehrsprachig | Zeigt den Text schon beim Sprechen (Streaming) |
| GigaAM v3 | 326 MB | Russisch | Russisch-Spezialist |

Faustregeln:
- Wenn du überwiegend eine Sprache transkribierst, ist ein Spezialmodell (Distil Italian, GigaAM) einem Generalisten derselben Größe überlegen.
- Wenn dein Telefon 4 GB RAM oder weniger hat, wähle Modelle unter 500 MB.
- Gemma-Modelle (auf dem Tab Modell separat aufgeführt) sind größere Sprachmodelle, die ebenfalls transkribieren können. Sie eignen sich für Experimente, sind aber schwerer und langsamer als die speziellen ASR-Modelle.

## Transkribieren: der Alltag

- Teile eine Sprachnachricht an Anti-Vocale. Die Verarbeitung startet sofort, auch bei ausgeschaltetem Bildschirm.
- Die Ergebnis-Benachrichtigung bietet: **Kopieren**, **Teilen** und, wenn die Quell-App unterstützt wird, **An [App] senden**, womit der Text direkt in den Chat eingefügt wird, aus dem die Sprachnachricht kam.
- Mit aktiviertem Automatischem Kopieren (Einstellungen) liegt der Text bereits in der Zwischenablage, wenn die Benachrichtigung ankommt; die Benachrichtigung weist darauf hin.
- Jede Transkription landet im Tab **Verlauf**, mit verwendetem Modell, Dauer und Bearbeitungszeit. Halte einen Eintrag lange gedrückt, um erneut zu versuchen, zu kopieren, zu löschen oder ein schlechtes Ergebnis per E-Mail zu melden; **Erneut transkribieren** führt dasselbe Audio durch ein anderes Modell deiner Wahl.
- Mit einem **Whisper- oder SenseVoice-**Modell trägt jede aufgeklappte Transkript einen Sprach-Chip: die Flagge der vom Modell erkannten Sprache, mit einem Tipp zum Prüfen oder Festlegen, wenn die Schätzung falsch liegt (kleine Modelle raten manchmal daneben). Andere Modellfamilien melden keine erkannte Sprache, deshalb bleibt der Chip dort aus; der Schalter in den Einstellungen sagt das und bleibt deaktiviert.
- Mit Automatischem Speichern im Ordner (Einstellungen) wird jede Transkription zusätzlich als .txt-Datei in einen von dir gewählten Ordner geschrieben.

## WhatsApp-Sprachnachrichten, automatisch

Anti-Vocale kann neue WhatsApp-Sprachnachrichten in dem Moment transkribieren, in dem sie ankommen, ganz ohne einen einzigen Tipp. Der Text landet im Tab Verlauf und als Ergebnis-Benachrichtigung; blockiert Android einen Hintergrundstart (zum Beispiel bei ausgeschaltetem Bildschirm), startet ein Tipp auf eine Benachrichtigung die Transkription.

Die Einrichtung dauert eine Minute, unter **Einstellungen > Sprachnachrichten**:

1. Aktiviere **Sprachnachrichten transkribieren** und erteile den Audio-Zugriff, wenn Android danach fragt. Hast du zweimal abgelehnt, zeigt Android keinen Dialog mehr; der stattdessen erscheinende rote Hinweis öffnet die Systemseite, auf der die Berechtigung noch erteilt werden kann.
2. Wähle den Ordner WhatsApp Voice Notes einmal über den geführten Picker aus. Ein falscher Ordner wird auf der Stelle abgelehnt; über dieselbe Karte kannst du ihn jederzeit ändern oder entfernen.
3. Optional: Aktiviere zusätzlich die **Absendererkennung** und erteile den Benachrichtigungszugriff. Die Verlauf-Zeile trägt dann den Namen des Absenders.

Zwei ehrliche Regeln gelten für die Labels. Ein Name erscheint nur, wenn die Benachrichtigung der Nachricht ihn bestätigt, das heißt, die Benachrichtigung kam an, während die Funktion an war, und ihre gemeldete Länge passt zum Audio: Namen werden nie geraten. Und Nachrichten, die du manuell herunterlädst (bei ausgeschaltetem automatischem Download), bleiben ohne Label, weil ihre Benachrichtigung zu alt ist, um ihr zu trauen. Die Warteliste der letzten Benachrichtigungen liegt nur im Speicher und verschwindet, sobald die Funktion aus ist.

Der Datenschutz folgt demselben Vertrag wie überall sonst: Solange die Erkennung an ist, wird nur die Ordnerliste der WhatsApp-Sprachnachrichten gelesen; das Audio einer Nachricht wird nur gelesen, um es zu transkribieren, vollständig auf diesem Gerät; solange die Funktion aus ist, wird gar nichts gelesen.

Ein Vorbehalt, klar gesagt: Auf Telefonen mit aggressiven Akku-Managern können Nachrichten verpasst werden, die ankommen, während das System die App ausgesetzt hat, bis sie aufwacht. Verlässt du dich auf den automatischen Modus, macht die Ausnahme von der Akku-Optimierung ihn zuverlässig. Vorerst nur WhatsApp; Telegram steht auf der Roadmap.

## Ergebnisse: Labels, Formate und das schwebende Fenster

- **Sprecher-Labels**: einmal in den Einstellungen aktiviert, erhalten Transkripte von Unterhaltungen eine Sprecherzuordnung Satz für Satz, im Ergebnis und in jedem Export als SPEAKER-Beiträge dargestellt.
- **Mehr teilen als Text**: aus einer Ergebnis-Zeile heraus als reiner Text, mit Zeitmarken, als SRT oder als VTT teilen. Die Zeitformate brauchen eine Transkription, die Cues mitbringt. „Teilen als …“ ist ein Opt-in in den Einstellungen.
- **Picture-in-Picture**: Während einer Transkription kann ein schwebendes Fenster zeigen, wie der Text live entsteht, sodass du andere Apps weiter nutzen kannst.
- **Erneut transkribieren**: Jeden Eintrag direkt aus seiner Zeile mit einem anderen Modell erneut laufen lassen und vergleichen.

## Lange Audios, Warteschlange und erneute Versuche

- Jede Audiolänge funktioniert mit jedem Modell: Längere Aufnahmen werden automatisch geteilt und wieder zusammengefügt. (Ältere Versionen hatten bei Parakeet ein Limit von 6:40 Minuten; das gibt es nicht mehr.)
- Teile mehrere Nachrichten hintereinander: Sie landen in der Warteschlange. Jeden wartenden Eintrag kannst du einzeln über seine Benachrichtigung abbrechen, während eine andere Transkription läuft.
- Eine fehlgeschlagene Transkription lässt sich mit einem Tipp im Tab Verlauf erneut versuchen.

## Community-Modelle und Importe

Der integrierte Katalog deckt nicht jede Sprache ab. Anti-Vocale bringt einen Community-Katalog zusätzlicher Modelle mit, die du mit zwei Tipps importierst: Tab Modell, Erweitert, ONNX Sherpa, Aus Katalog importieren, nach deiner Sprache filtern, Modell antippen, bestätigen. Der Katalog wächst laufend und umfasst heute über 30 geprüfte Einträge: Arabisch (dialektal, vollständig und leicht), Deutsch (Streaming und ein Whisper-Fine-Tune), Spanisch (Streaming und leichtes Moonshine), Schweizerdeutsch, Hebräisch, Persisch, Russisch, Vietnamesisch, Koreanisch, Ukrainisch, sechs indische Sprachen (Bengalisch, Gujarati, Hindi, Marathi, Tamil, Telugu), Canary Flash für Englisch und Französisch, SenseVoice, Dolphin, Orukeet (ein Parakeet-Fine-Tune mit 24 Sprachen inklusive Italienisch) und ein multilinguales Whisper Tiny mit wenig RAM. Fehlt deine Sprache, öffne ein Issue: So kommen neue Einträge in den Katalog.

Fortgeschrittene Nutzer können zusätzlich:
- ein Modell über die URL eines Hugging-Face-Repositorys oder über einen Link auf einen Katalog-Eintrag importieren (der erweiterte Zweig im selben Dialog);
- einen Satz Modelldateien aus einem Ordner auf dem Telefon importieren;
- ein heruntergeladenes Modell als **Offline-Bundle** exportieren und auf einem anderen Gerät importieren, sodass ein einmal heruntergeladenes Modell ohne erneutes Herunterladen mitwandert;
- die App auf einen anderen Katalog-Index zeigen lassen (die Aktion „ändern“ neben der Katalogquelle), den jeder pflegen kann, zum Beispiel deine Community.

Das Importformat und die Dateianforderungen sind in [externen Modellen](../../external-models.md) dokumentiert.

## Tasker und Automatisierung

Anti-Vocale nimmt einen Broadcast entgegen, den Tasker (oder jede Automatisierungs-App) senden kann, um eine Datei ganz ohne Bedienung der Oberfläche zu transkribieren:

```
Action: com.antivocale.app.PROCESS_REQUEST
Extras: request_type=audio, file_path=/path/to/audio, task_id=your-id
Optional: backend_id=<model id> to pick the model for that request
```

Das Ergebnis kommt als Antwort-Broadcast zurück. Die komplette Anleitung mit Beispielen steht im [Tasker-Guide](../../TASKER_GUIDE.md).

## Geplante Ordnerüberwachung

Anti-Vocale kann Ordner deiner Wahl überwachen und neue Audiodateien automatisch transkribieren: Einstellungen, Geplante Ordnerüberwachung, Ordner überwachen. Die Scans laufen in dem Takt, den das System erlaubt; aggressive Akkusparmodi können sie verzögern, und das manuelle Jetzt scannen funktioniert immer. Jede neue Audiodatei wird mit dem aktuell gewählten Modell transkribiert und landet wie jeder andere Lauf im Tab Verlauf. Ab Android 11 kann die Ordnerauswahl auch gemeinsame Speicherorte öffnen, etwa den Medienordner einer Messenger-App; so lassen sich die Sprachnachrichten eines Chats automatisch transkribieren, ohne jede einzelne zu teilen.

## Remote-Offload (experimentell)

Betreibst du einen eigenen Transkriptionsserver, kann Anti-Vocale lange oder schwere Transkriptionen an ihn auslagern: Einstellungen, Remote-Offload (experimentell). Jeder OpenAI-kompatible Server, der die API /v1/audio/transcriptions spricht, funktioniert (OmniVoice, der whisper.cpp-Server und andere). Der Hinweis in der App sagt es klar: Ist die Funktion aktiv, wird das Audio der ausgelagerten Transkriptionen nur an die Serveradresse gesendet, die du eingibst, wo auch immer dieser Server steht (dein Heimnetzwerk oder irgendwo im Internet); alles andere wird auf dem Gerät transkribiert, und die Funktion ist standardmäßig aus.

## Open Transcribe (experimentell)

Apps, die den offenen Open-Transcribe-Vertrag unterstützen, wie Forkgram (ein Telegram-Client), können Anti-Vocale als Sprachnachrichten-Transkribierer direkt im Client nutzen: Aktiviere **Einstellungen > Automatisierung > Open Transcribe API** (standardmäßig aus) und wähle Anti-Vocale anschließend in den Einstellungen für Offline-Transkription des Clients. Sprachnachrichten werden mit den eigenen Modellen der App transkribiert, und das Audio verlässt das Gerät nie. Schaltest du die Funktion aus, verschwindet die App wieder aus den Auswahllisten der Clients.

## App-spezifische Einstellungen

Für jede App, aus der du teilst (WhatsApp, Telegram, ...), stellst du getrennt ein: ob die Aktion Schnell-Zurückteilen erscheint, ob automatisch kopiert wird und welcher Benachrichtigungston erklingt. Tab Einstellungen, App-spezifische Einstellungen.

## Datenschutz

- Die Transkription läuft zu 100 % auf dem Gerät. Kein Audio, kein Text und keine Metadaten verlassen jemals dein Telefon.
- Die App hat keine Internet-Berechtigung für die Transkription; das Netzwerk wird nur genutzt, wenn du ausdrücklich ein Modell herunterlädst.
- Der Verlauf bleibt auf deinem Gerät und gehört dir: Leere ihn jederzeit im Tab Verlauf.
- Der Play-Build enthält die Absturzberichte von Crashlytics (einsehbar und abschaltbar in den Android-Einstellungen); der F-Droid-Build hat keine.

## Fehlerbehebung

**Die Transkription wird nie fertig / die Benachrichtigung verschwindet.**
Einige Handy-Marken (Vivo, OPPO, manche Xiaomi und Samsung) setzen Hintergrund-Apps aggressiv aus. Öffne Anti-Vocale einmal und erlaube, wenn angeboten, den Hintergrundbetrieb; oder suche die App in den Akku-Einstellungen und stelle sie auf „Uneingeschränkt“. Die App erkennt diese Situation und erklärt sie in einer Benachrichtigung, wenn sie auftritt.

**„Nicht genug Speicher“ oder Abstürze mit großen Modellen.**
Modelle geben ihre Größe auf der Karte an. Auf Telefonen mit 4 GB RAM oder weniger nutze Modelle unter 500 MB. Schlägt eine Transkription mit einer Meldung zu wenig Speicher fehl, versuche eine kürzere Datei, ein kleineres Modell, oder schließe andere Apps.

**Die Transkriptionsqualität ist schlecht.**
Versuche ein Spezialmodell für deine Sprache (siehe Tabelle oben). Halte den schlechten Eintrag im Verlauf lange gedrückt und nutze Melden, um uns die Details zu senden (Modell, Dauer, Bearbeitungszeit; den Transkript-Auszug nur, wenn du ihn einschließen willst).

**NNAPI führt zu Abstürzen.**
Hast du in den Einstellungen den NNAPI-Anbieter aktiviert und die App stürzt jetzt ab, kehrt sie beim nächsten Start automatisch zur CPU zurück. NNAPI hängt stark vom Chipsatz des Telefons ab; bei wiederholten Abstürzen lass es bei der CPU.

**Automatische Sprachnachrichten kommen nicht mehr an.**
Zwei übliche Ursachen. Wurde der WhatsApp-Ordner gelöscht und neu angelegt (eine Neuinstallation, ein Aufräum-Tool), kann die gespeicherte Ordner-Berechtigung sterben: Öffne Einstellungen > Sprachnachrichten und wähle den Ordner über die Karte erneut aus. Setzt dein Telefon Hintergrund-Apps aggressiv aus, werden Nachrichten, die während der Aussetzung ankommen, verpasst, bis die App aufwacht: Die Akku-Ausnahme von oben ist die Lösung, und für diese Funktion zählt sie mehr als für das manuelle Teilen.

## FAQ

**Funktioniert es ohne Internet?**
Ja. Nach dem Herunterladen eines Modells funktioniert die Transkription vollständig offline.

**Welche Messenger werden unterstützt?**
Jede App, die eine Audiodatei teilen kann. Die Aktion zum Zurücksenden zielt derzeit auf eine Auswahl von Apps (WhatsApp, Telegram und weitere, automatisch erkannt).

**Wo sind meine Transkripte?**
Im Tab Verlauf und optional als .txt-Dateien in einem Ordner deiner Wahl. Nirgendwo sonst wird etwas gespeichert.

**Werden Sprachnachrichten automatisch beim Eingang transkribiert?**
Ja, für WhatsApp: Aktiviere Einstellungen > Sprachnachrichten, erteile den Audio-Zugriff und wähle den Ordner WhatsApp Voice Notes einmal aus. Neue Nachrichten werden transkribiert, sobald sie ankommen, und die Zeile kann den Namen des Absenders tragen. Die Geplante Ordnerüberwachung bleibt der allgemeine Weg für den Medienordner jeder App (Android 11+), und Telegram steht auf der Roadmap. Auf Telefonen mit aggressiven Akku-Managern können Nachrichten, die ankommen, während die App ausgesetzt ist, verpasst werden: Die Akku-Ausnahme macht den automatischen Modus zuverlässig.

**Warum trägt eine Sprachnachricht keinen Absendernamen?**
Der Name erscheint nur, wenn die Benachrichtigung der Nachricht einging, während die Absendererkennung an war, und ihre gemeldete Länge zum Audio passt. Manuell heruntergeladene Nachrichten (automatischer Download aus) und Nachrichten, deren Benachrichtigung nie ankam, bekommen bewusst keinen Namen: Ein falscher Name ist schlimmer als kein Name.

**Warum gibt es zwei Stores (Play und F-Droid)?**
Dieselbe App, dieselben Funktionen. F-Droid baut sie aus dem Quellcode ohne proprietäre Komponenten; Play ergänzt automatische Absturzberichte.

**Ist es wirklich privat?**
Ja. Der Quellcode ist offen; du kannst nachprüfen, dass keine Daten das Gerät verlassen. Siehe die Datenschutzerklärung im Repository.

---

Fehler gefunden oder etwas fehlt? Öffne ein Issue auf [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues).
