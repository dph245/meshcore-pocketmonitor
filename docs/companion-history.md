# Lokale Companion-Historie

PocketMonitor öffnet beim Start den zuletzt gesehenen Companion. Getrennt von BLE lässt sich über **Historie** ein anderer gespeicherter Companion wählen. Bei einer neuen Verbindung wechselt die Ansicht zum identifizierten Gerät. **Live-Funk** und **Rohdaten** zeigen jeweils bis zu 500 Zeilen pro Seite, eine Unterhaltung bis zu 200 Nachrichten. **Ältere** und **Neuere** wechseln die Seite. **Fortsetzen** springt in Live-Funk zurück an den Anfang.

## Datenmodell

Die Datenbank `databases/companion-history.db` verwendet Androids `SQLiteOpenHelper` mit Write-Ahead Logging, Fremdschlüsseln und Schema-Version 2. Die App legt die Datenbank im privaten App-Speicher an. Backups und Gerätetransfer sind in der App-Konfiguration ausgeschlossen.

| Tabelle | Inhalt |
| --- | --- |
| `companion` | Vollständiger öffentlicher MeshCore-Schlüssel aus `SELF_INFO`, letzter BLE-Anzeigename, Zeitpunkt der letzten Identifikation und Anzahl der gespeicherten RX-Zeilen. |
| `channel` | Companion, Konversations-ID aus Kanal-Fingerabdruck, unbekanntem Slot oder PM-Identität, zuletzt bekannter Name und optionaler aktueller Slot. |
| `rx` | Companion, Sitzungs-ID, Sequenz, Empfangszeit in Millisekunden, Notification-Code, unveränderte Bytes als BLOB und optionaler Nachrichtenbezug. Die Funk-Metadaten SNR und RSSI stehen in den Originalbytes. |
| `message` | Companion, Kanalidentität, `comparisonId`, Absender, Sender-Zeitstempel, Text und getrennte Zähler für noch gespeicherte Funkempfänge und Abholantworten. |
| `legacy_import` | Zuletzt bestätigte Sequenz je TSV-Datei während des Imports. |

Eine neue BLE-Adresse oder ein neuer Anzeigename ändert die Companion-Zuordnung nicht, solange der öffentliche MeshCore-Schlüssel gleich bleibt. Ein Identitätswechsel erzeugt eine neue Historie. Eine unvollständige oder ungültige `SELF_INFO`-Antwort kann keinem Companion zugeordnet werden. Bis zur Identifikation puffert die App die Frames im Speicher.

Die Kanalidentität folgt dem Schlüssel bei einer Umbenennung oder Slotverschiebung. Bei identischen Namen und verschiedenen Schlüsseln entstehen getrennte Konversationen. Mehrere Slots mit demselben Schlüssel teilen eine Konversation. Die App aktualisiert die aktuelle Slotbelegung erst nach einer vollständigen Abfrage. Entfernte Kanäle bleiben mit vorhandenen Nachrichten als Archiv sichtbar. Absender und Senderzeit stammen aus dem Funkpaket.

Die eindeutige Kombination aus Companion, Kanalidentität und `comparisonId` fasst wiederholte Funkempfänge derselben Nachricht zusammen. Jeder Empfang bleibt eine eigene RX-Zeile. Die Kombination aus Sitzungs-ID und Sequenz schützt vor erneutem Einfügen derselben Zeile. Ein erneuter Decode kann eine RX-Zeile nur einmal mit einer Nachricht verknüpfen. Indizierte Seitenabfragen verwenden die letzte Zeilen-ID beziehungsweise Senderzeit und Nachrichten-ID als Cursor. Die Funkliste behält decodierte Zeilen bei Überlappung, und der Chat hält den sichtbaren Scrollanker bei Aktualisierungen innerhalb der Seite.

## Schlüssel und Aufbewahrung

- Kanal- und Scope-Abfragen sowie Antworten mit BLE-PIN oder privatem Geräteschlüssel werden beim Journaling ausgelassen. Funk-RX bleibt bytegetreu erhalten. SQLite speichert decodierten Kanaltext im Klartext innerhalb des privaten App-Speichers.
- Während einer Verbindung nutzt die App die gelesenen Schlüssel im Arbeitsspeicher. Neu verfügbare Schlüssel können noch unverknüpfte Funk-RX-Zeilen in begrenzten Stapeln nachdecodieren. Bereits decodierte Nachrichten bleiben offline verfügbar. Für bisher unlesbare Channel-Pakete muss der passende Kanalschlüssel nochmals über den Companion verfügbar sein. Private Unterhaltungen verwenden den vom Companion entschlüsselten Text. [Nachrichtenabgleich](message-sync.md)
- Die Frist beträgt 30 × 24 Stunden ab lokaler Empfangszeit. Die App bereinigt beim Start vor der Anzeige und danach einmal pro Minute, solange das ViewModel läuft. Eine Nachricht bleibt bis zum Ablauf ihres letzten gespeicherten Empfangs erhalten. Die Senderuhr beeinflusst nur die Anzeige und Sortierung. Bekannte Companions und aktuelle Slotkonfigurationen bleiben erhalten. Ungenutzte archivierte Kanalmetadaten werden bereinigt.
- Schreibvorgänge und Decodierung laufen außerhalb des Android-Main-Threads. Beim normalen Trennen schreibt der Worker die bereits akzeptierte Warteschlange zu Ende. Ein abruptes Prozessende kann noch nicht bestätigte Stapel verlieren. Die App protokolliert nur von Android zugestellte Notifications. Die DISCOVER-Läufe bleiben sitzungsbezogen im Arbeitsspeicher, ihre empfangenen Funkpakete sind Teil des RX-Journals.

## Migration aus TSV

Beim ersten Start liest die App alte `files/rx/rx-*.tsv`-Dateien stapelweise. Sie leitet die Companion-Identität aus `SELF_INFO` ab, übernimmt noch gültige RX-Zeilen und verwendet vorhandene Kanalantworten während des Imports zur Decodierung. Für Slots dient die zuletzt protokollierte Antwort als beste verfügbare Information. Alte Journale zeichnen keine vollständigen Konfigurations-Snapshots auf. Eine aus der Migration geschätzte Belegung wird bei der nächsten vollständigen Abfrage aktualisiert.

Der Fortschritt wird gemeinsam mit den importierten Zeilen bestätigt, damit ein unterbrochener Import fortgesetzt werden kann. Nach Abschluss entfernt die App die TSV-Quelle, die möglicherweise noch Schlüssel oder die BLE-PIN enthält. Dateien ohne eindeutige vollständige Identität werden beim Übergang verworfen. Abgelaufene RX-Zeilen werden beim Import übersprungen. Die Migration von SQLite-Schema 1 auf 2 ergänzt die Abholzähler und aktualisiert die Trigger, die die Zähler und die Aufbewahrung pflegen.

## Prüfung

- Unit-Tests prüfen Identität, Ausschluss geheimer Antworten, private Kanaldecodierung, Vergleichskennung und den Zeilencache nach Bereinigung und Seitenwechsel.
- Die Android-Instrumentierung prüft SQLite-Schreibpfad, Wiederöffnen, zwei gleichnamige Companions, gleichnamige Kanäle, Umbenennung und Slotwechsel, idempotente Verarbeitung, die exakte Ablaufgrenze, Wiederaufnahme des TSV-Imports sowie RX- und Chat-Seiten mit je rund 50.000 Zeilen.
- Auf dem Moto G77 wurden zwei synthetische Altjournale im isolierten Testpaket importiert. Nach `force-stop` waren der Companion-Wechsel, 50.002 RX-Zeilen mit Scrollen und Seitenwechsel sowie die offline gespeicherte private Nachricht sichtbar. Ein Test mit echter Companion-Firmware und laufendem Funkverkehr steht aus.

Die Release-Scroll-Fixture auf demselben Moto G77 bei 90 Hz verwendete drei Läufe je Ansicht und Build. Für Live-Funk folgten drei weitere Läufe je Build in wechselnder A/B-Reihenfolge. Sie prüft die bestehenden Compose-Listen mit 50.000 synthetischen Einträgen, davon 500 sichtbaren Live-Zeilen im Datenfenster. SQLite-Seitenabrufe und Funkverkehr waren nicht Teil dieser Frame-Messung.

| Ansicht | Median p50 vorher → nachher | Median p95 vorher → nachher |
| --- | ---: | ---: |
| Live-Funk, erster Durchgang | 7 → 8 ms | 11 → 17 ms |
| Live-Funk, wechselnde A/B-Reihenfolge | 7 → 6 ms | 10 → 10 ms |
| Chat | 11 → 11 ms | 16 → 16 ms |

Live-Funk-p95 schwankte im ersten Durchgang zwischen 10 und 15 ms vorher und 10 und 21 ms nachher. In der wechselnden A/B-Reihenfolge lagen alle Läufe zwischen 10 und 11 ms. Der höhere p95 aus dem ersten Durchgang ließ sich damit nicht reproduzieren.

```bash
./gradlew --offline testDebugUnitTest assembleDebug lintDebug assembleRelease
./gradlew --offline assembleDebug assembleDebugAndroidTest -PscrollBenchmark
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w de.meshcore.pocketmonitor.scrollbenchmark.test/de.meshcore.pocketmonitor.HistoryInstrumentation
```
