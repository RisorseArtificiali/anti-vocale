# Anti-Vocale Benutzerhandbuch

Anti-Vocale transkribiert Sprachnachrichten auf deinem Android-Gerät, komplett offline. Audio verlässt dein Telefon nie: Die Transkription läuft lokal mit offenen KI-Modellen, ohne Konto, ohne Cloud-Dienst, ohne Telemetrie.

Handbuch aktualisiert für Version 1.11.

## Inhalt

1. [Erste Schritte](#erste-schritte)
2. [Modell wählen](#modell-wählen)
3. [Transkribieren: der Alltag](#transkribieren-der-alltag)
4. [Lange Audios, Warteschlange und erneute Versuche](#lange-audios-warteschlange-und-erneute-versuche)
5. [Community-Modelle und Importe](#community-modelle-und-importe)
6. [Tasker und Automatisierung](#tasker-und-automatisierung)
7. [App-spezifische Einstellungen](#app-spezifische-einstellungen)
8. [Datenschutz](#datenschutz)
9. [Fehlerbehebung](#fehlerbehebung)
10. [FAQ](#faq)

## Erste Schritte

1. Installiere Anti-Vocale aus deinem Store (Google Play oder F-Droid) oder über eine Release-APK von GitHub.
2. Öffne die App einmal. Auf dem Tab **Modelle** siehst du die integrierten Modelle, die zum Herunterladen bereitstehen.
3. Lade ein Modell herunter. Für die meisten ist **Parakeet TDT (stock int8, 464 MB)** die empfohlene erste Wahl: schnell, klein, und es deckt 25 europäische Sprachen ab.
4. Zum Transkribieren teile eine Sprachnachricht aus einem beliebigen Messenger (WhatsApp, Telegram, Signal und andere) an Anti-Vocale. Während der Verarbeitung erscheint eine Benachrichtigung, danach eine zweite mit dem Text.
5. Tippe auf die Ergebnis-Benachrichtigung, um den Text zu kopieren, zu teilen oder in den Chat zurückzusenden, aus dem er stammt.

Weitere Konfiguration ist nicht nötig. Alles unten ist optional.

## Modell wählen

Modelle unterscheiden sich in Größe, Geschwindigkeit, Sprachabdeckung und Genauigkeit. Der Tab Modelle zeigt die wichtigsten Fakten auf jeder Karte an, bevor du herunterlädst. Schnelle Orientierung:

| Modell | Größe | Sprachen | Hinweise |
|---|---|---|---|
| Parakeet TDT stock int8 | 464 MB | 25 europäische | Schnell und leicht; die Standard-Empfehlung |
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
- Jede Transkription landet im Tab **Verlauf**, mit verwendetem Modell, Dauer und Bearbeitungszeit. Halte einen Eintrag lange gedrückt, um erneut zu versuchen, zu kopieren, zu löschen oder ein schlechtes Ergebnis per E-Mail zu melden.
- Mit Automatischem Speichern im Ordner (Einstellungen) wird jede Transkription zusätzlich als .txt-Datei in einen von dir gewählten Ordner geschrieben.

## Lange Audios, Warteschlange und erneute Versuche

- Jede Audiolänge funktioniert mit jedem Modell: Längere Aufnahmen werden automatisch geteilt und wieder zusammengefügt. (Ältere Versionen hatten bei Parakeet ein Limit von 6:40 Minuten; das gibt es nicht mehr.)
- Teile mehrere Nachrichten hintereinander: Sie landen in der Warteschlange. Jeden wartenden Eintrag kannst du einzeln über seine Benachrichtigung abbrechen, während eine andere Transkription läuft.
- Eine fehlgeschlagene Transkription lässt sich mit einem Tipp im Tab Verlauf erneut versuchen.

## Community-Modelle und Importe

Der integrierte Katalog deckt nicht jede Sprache ab. Anti-Vocale bringt einen Community-Katalog zusätzlicher Modelle mit, die du mit zwei Tipps importierst: Tab Modell, Erweitert, ONNX Sherpa, Aus Katalog importieren, nach deiner Sprache filtern, Modell antippen, bestätigen. Community-Modelle gibt es aktuell für Arabisch (Dialekte), Russisch, Spanisch, Deutsch (Streaming) und Schweizerdeutsch.

Fortgeschrittene Nutzer können zusätzlich:
- ein Modell über die URL eines Hugging-Face-Repositorys oder über einen Link auf einen Katalog-Eintrag importieren (der erweiterte Zweig im selben Dialog);
- einen Satz Modelldateien aus einem Ordner auf dem Telefon importieren;
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

## FAQ

**Funktioniert es ohne Internet?**
Ja. Nach dem Herunterladen eines Modells funktioniert die Transkription vollständig offline.

**Welche Messenger werden unterstützt?**
Jede App, die eine Audiodatei teilen kann. Die Aktion zum Zurücksenden zielt derzeit auf eine Auswahl von Apps (WhatsApp, Telegram und weitere, automatisch erkannt).

**Wo sind meine Transkripte?**
Im Tab Verlauf und optional als .txt-Dateien in einem Ordner deiner Wahl. Nirgendwo sonst wird etwas gespeichert.

**Werden Sprachnachrichten automatisch beim Eingang transkribiert?**
Noch nicht. Es steht auf der Roadmap; heute braucht das Teilen nur einen Tipp.

**Warum gibt es zwei Stores (Play und F-Droid)?**
Dieselbe App, dieselben Funktionen. F-Droid baut sie aus dem Quellcode ohne proprietäre Komponenten; Play ergänzt automatische Absturzberichte.

**Ist es wirklich privat?**
Ja. Der Quellcode ist offen; du kannst nachprüfen, dass keine Daten das Gerät verlassen. Siehe die Datenschutzerklärung im Repository.

---

Fehler gefunden oder etwas fehlt? Öffne ein Issue auf [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues).
