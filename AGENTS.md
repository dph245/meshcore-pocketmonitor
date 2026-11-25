# MeshCore PocketMonitor

## Datei pflegen

Prüfe diese Datei nach jedem Sprint. Ergänze nur überprüfte Erkenntnisse, die künftige Änderungen erleichtern. Aktualisiere nachweislich veraltete Einträge, löse Widersprüche und bewahre weiterhin gültige Anweisungen. Halte die Einträge kurz und vermeide Duplikate und Sprint-Protokolle. Erwähne Änderungen an dieser Datei in der Sprint-Zusammenfassung.

Den Entwicklungsstand, offene Prüfungen und historische Quellen enthält [PROJECT_STATUS.md](PROJECT_STATUS.md).

## Ziel und Architektur

PocketMonitor ist eine native Android-App für MeshCore-Companions. Sie verbindet Funkdiagnose mit Kanal- und Privatnachrichten und macht die lokale Historie offline lesbar. Der Companion führt LoRa-Funk und Firmware-Befehle aus. PocketMonitor verwendet BLE oder MeshCore-TCP als Companion-Transport. Der GPS-Positionspicker lädt OpenStreetMap-Kacheln per HTTPS mit lokalem HTTP-Cache. Die restliche App arbeitet ohne Cloud oder MQTT.

- Das einzige Gradle-Modul ist `:app`, die App-ID und der Namespace sind `de.meshcore.pocketmonitor`. Quellcode liegt unter `app/src/main/java/de/meshcore/pocketmonitor/`.
- `ble/` enthält NUS-Scan, GATT, die gemeinsame Companion-Sitzung, Kontakt- und Kanalkataloge sowie den serialisierten Befehlsverkehr. `tcp/` enthält Socket-Transport und TCP-Framing. `protocol/` enthält Frame- und Funkdecoder, Befehlsformate und Zustandslogik. `rx/` enthält Journal, SQLite-Historie und TSV-Migration. `ui/` enthält Compose-Ansichten und `MonitorViewModel` als Anbindung an Verbindung und Historie.
- `PocketMonitorApplication` stellt die eine prozessweite Verbindung und Historie bereit. `CompanionReceiveService` hält die Sitzung als `connectedDevice`-Foreground-Service im Hintergrund und kann nach einem BLE-Abbruch oder bei eingeschaltetem TCP-Reconnect denselben Companion erneut verbinden. `MainActivity` ist für UI und Berechtigungen zuständig. Der Dienst ist `START_NOT_STICKY`. Ein Prozessende kann den Empfang unterbrechen.
- Gradle-Wrapper 8.13, AGP 8.13.2, Kotlin und Compose-Plugin 2.3.10, JVM-Ziel 17. Android SDK und Build Tools 36, `minSdk` 26, `targetSdk` 36. JDK 21 ist der erprobte lokale Build-Stand. AndroidX Compose BOM 2025.08.01 mit Material 3, Activity Compose 1.10.1, Lifecycle 2.9.2, Kotlin Coroutines/Flow, ProfileInstaller 1.4.0 und JUnit 4.13.2. SQLite läuft über Androids `SQLiteOpenHelper` mit WAL und Fremdschlüsseln. Die Versionen in Gradle sind maßgeblich.

## Zusammenarbeit und Konventionen

- Prüfe vor Änderungen `git status` und relevante Diffs. Erhalte vorhandene Nutzeränderungen. Halte Sprints thematisch eng und führe Commit, Push, Installation oder Funkversuche im jeweils beauftragten Umfang aus. Ein Vergleich oder Audit bleibt lesend, wenn zunächst ein Bericht gewünscht ist.
- Verwende vorhandene Kotlin-Modelle, Decoder und Zustandsautomaten. Behalte Kotlin- und Protokollbezeichner auf Englisch und die bestehenden deutschen UI-Texte bei. Halte neue Wire-Logik unabhängig von Compose und prüfe Grenzwerte, unbekannte Frames und Fehlerpfade mit den vorhandenen Testmustern. Gib bei Firmwarefragen den tatsächlich geprüften Commit oder die Protokollversion an. `26079dea` ist die Referenz mehrerer früherer Sprints.
- Halte die Oberfläche kompakt und zugänglich. Icon-Tabs haben Namen für Langdruck und Screenreader. Statusanzeigen dürfen den Chatbereich und die Scrollposition beim Einblenden nicht verschieben. Die neueste Chat-Seite folgt neuen Nachrichten, ältere Seiten und laufende Scrollgesten behalten ihren Anker. Kanäle und Privat haben getrennte Sortiereinstellungen, ungelesene Unterhaltungen stehen zuerst.
- Der Nutzer hat nach dem R8-/Baseline-Profile-Vergleich die weitere Feature-Entwicklung priorisiert. Bei Änderungen an Listen bestehendes Paging, stabile Element-IDs und Hintergrundaufbereitung bewahren. Synthetische Scrollmessungen mit identischen Fixtures und vergleichbaren Displayeinstellungen ausführen. `docs/scroll-performance.md` und `docs/performance-comparison/` sollen lokal und ignoriert bleiben.
- Berichte für den Sprint getrennt, welche Prüfungen im JVM-Test, in SQLite-Instrumentierung, mit simuliertem BLE, auf dem Moto G77 und über tatsächlichen LoRa-Funk gelaufen sind. Ein Build, eine Installation und ein App-Start sind jeweils eigene Prüfschritte.

## Companion-Transport, Funk und Datensicherheit

- Führe Companion-Kommandos über die gemeinsame serielle Sitzung aus und trenne Transport-Schreibabschluss, Firmware-Antwort und asynchrone Pushes. Berücksichtige BLE-MTU bzw. TCP-Framebudget, Firmwareversion, Sitzung und Companion-Identität. Viele Antworten haben keine Anfrage-ID. Verwende die Sperre und Reconnect-Behandlung für unklare Befehlszustände, damit verspätete Antworten keine neue Anfrage bestätigen. Fehler im Decoder dürfen das RX-Journal nicht beeinträchtigen.
- TCP verwendet `0x3C` zum Companion und `0x3E` zur App, jeweils mit 2-Byte-Länge in Little Endian und maximal 176 Payload-Bytes. Automatische TCP-Wiederverbindung prüft den vollständigen Schlüssel der letzten erfolgreichen Sitzung. Explizites Trennen beendet auch geplante Versuche.
- Multi-ACKs (ab Protokollversion 7) steht in `SELF_INFO` und teilt sich den Schreibbefehl mit Kontakt-, Telemetrie- und Advert-Standortparametern. Lies diese vor einer Änderung frisch und übernimm ihre Werte. Die Pfad-Hash-Länge (ab Version 10) steht in `DEVICE_INFO` und verwendet auf dem Wire die Modi 0–2 für 1–3 Byte.
- Behalte empfangene Funk- und zulässige Notification-Bytes unverändert mit Empfangszeit und Reihenfolge. Dedupliziere Chatnachrichten auf Nachrichtenebene und bewahre einzelne Funkempfänge und Pfade. `ReceptionWriter` schreibt außerhalb des Main-Threads und arbeitet akzeptierte Ereignisse beim normalen Trennen ab. Respektiere bestehende Journalfilter für Antworten mit Kanal-/Scope-Schlüsseln, BLE-PIN oder privatem Geräteschlüssel. Keine Zugangsdaten, privaten Schlüssel, echten Chatdaten oder Keystores in Doku, Logs, Git und neuen Test-Fixtures ablegen. Entschlüsselter Nachrichtentext liegt in privatem App-Speicher. Android-Backup und Gerätetransfer sind ausgeschlossen.
- `CMD_SYNC_NEXT_MESSAGE` entnimmt Einträge aus der gemeinsamen RAM-Warteschlange der Firmware. `0x0A` als Antwort beendet den Abruf regulär. Vor dem nächsten Abruf wird der vorherige Eintrag lokal bestätigt. Ein schon vom Companion entfernter Eintrag kann bei Verbindungs- oder Prozessabbruch trotzdem verloren gehen. Details stehen in [docs/message-sync.md](docs/message-sync.md).
- Identifiziere einen Companion anhand des vollständigen öffentlichen Schlüssels aus `SELF_INFO`. Der Name dient der Anzeige, BLE-Adresse und TCP-Endpunkt dem Transport. Kanalverläufe folgen dem Schlüsselfingerabdruck über Slot- und Namenswechsel. Aktualisiere den aktiven Slotbestand auf Basis vollständiger Abfragen. Sende PM nur bei eindeutigem Sechs-Byte-Präfix und passendem vollständigem Kontaktschlüssel. Stelle unbekannte oder mehrdeutige Hops als Präfixe dar. Vergleiche die Präfixbreiten für normale Pfade (1–3 Bytes) und TRACE (eigenes Wire-Format) getrennt.
- Halte Firmware-Annahme, PM-ACK und lokale Gruppen-Repeats auseinander. Ein Gruppen-Repeat ist eine lokale RX-Beobachtung ohne Absenderauthentifizierung oder Zustellquittung. Ein empfangener Hop-Präfix belegt keine eindeutige physische Station. DISCOVER, TRACE, Adverts und erste Nachrichten-Sendeversuche brauchen eine bewusste Bedienaktion. Setze abgebrochene Sendevorgänge nach Neustart oder Wiederverbindung nicht automatisch fort. Bewahre den bestehenden PM-Retry-Pfad und seine ACK-Zuordnung.
- Bewahre beim Ändern von Group-TX und PM-Flood das Setzen, Zurücksetzen und Wiederherstellen flüchtiger Scope-Overrides. Der gespeicherte Standard-Scope ist eine eigene Companion-Einstellung. Ein manueller PM-Pfad kann den Kontaktpfad auf dem Companion verändern. Bewahre die vorherige persistente Kennzeichnung appseitig gesetzter Pfade und die Verifikation nach dem Schreiben. Ein Flood-Fallback mit Pfad-Reset erfordert die entsprechende Einstellung. Protokollhintergrund steht in [docs/private-send-live-repeaters.md](docs/private-send-live-repeaters.md). Für inzwischen erweiterte PM-Optionen ist der Quellcode maßgeblich.

## Nachrichtenhistorie

- Erhalte Migration und Datenbestand bei Schemaänderungen. `HistoryDatabase` definiert Erstellung und Upgrades, `CompanionHistory` die Transaktionen und Paginierung. Die Frist von 30 Tagen richtet sich nach lokaler Empfangs- beziehungsweise Sendezeit. TSV-Import, Wiederaufnahme, Dubletten und getrennte Companion-Historien müssen funktionsfähig bleiben.
- Der Lesestatus liegt in SQLite (`message.unread`). `CompanionHistory.unreadConversations()` liefert die Markierungen nach Companion und Unterhaltung. Änderungen am Lesestatus müssen bei geänderten Zeilen die History-Revision erhöhen, damit die Oberfläche die Markierungen neu lädt.
- Beim Öffnen einer Unterhaltung erfasst `CompanionHistory.messages()` mit `includeUnreadThrough = true` die höchste ungelesene Nachrichten-ID in `unreadThrough`. `markReadThrough()` erfasst damit auch ältere, noch nicht geladene Seiten und lässt spätere Eingänge ungelesen.
- Die Sammelaktion markiert alle ungelesenen Kanalnachrichten der ausgewählten Companion-Historie in einem SQLite-Update. Sie erfasst auch archivierte Kanäle und unbekannte Slots. Der Filter überspringt Privatnachrichten anhand von `PRIVATE_ID_PREFIX` (`pm:`).
- Beim Löschen eines archivierten Kanals bleiben die RX-Rohframes erhalten. `rx.message_discarded` verhindert, dass eine spätere Nachdecodierung den gelöschten Verlauf aus diesen Frames wiederherstellt. Das Entfernen vom Companion belässt den Verlauf im Archiv.

## Builds und lokale Prüfungen

Setze den SDK-Pfad über die lokale Entwicklungsumgebung oder `local.properties`. Der Wrapper lädt seine Abhängigkeiten beim ersten Build. Mit vorhandenem Cache kann `--offline` genutzt werden.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

Der normale Release nutzt R8 und das eingecheckte `app/src/main/baseline-prof.txt`. `-PscrollBenchmark` baut die synthetische Debug-Fixture mit eigener App-ID `.scrollbenchmark`. `-PprofileRelease` baut den separat installierbaren und lokal debug-signierten Release `.profiling`. Für die synthetische Release-Fixture beide Flags verwenden. `tools/measure-scroll.sh` misst Scrollen über ADB. Reguläre APKs ohne diese Flags bauen.

## Datenbanktests

- SQLite-Historie, Migration und Paging prüft der eigene Android-Instrumentierungsrunner `HistoryInstrumentation`. Nach dem APK-Build beide Pakete auf einem geeigneten Gerät oder Emulator installieren. Auf dem Moto G77 mit bestehender Testinstallation Signatur und Ziel-App vor jedem APK-Wechsel prüfen. Varianten mit eigener App-ID verwenden einen eigenen Instrumentierungspaketnamen.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w de.meshcore.pocketmonitor.test/de.meshcore.pocketmonitor.HistoryInstrumentation
```

Auf `PASS` in der Ausgabe prüfen. Erfolgreiches Bauen des Test-APKs bestätigt die Instrumentierung noch nicht.

## Release auf dem Testgerät

- `:app:assembleRelease` erzeugt `app-release-unsigned.apk`. Für Updates der bestehenden Testinstallation mit dem lokalen Android-Debugschlüssel signieren und das Zertifikat vorab mit der installierten APK abgleichen. Mit `adb install --no-streaming -r` aktualisieren, anschließend APK-Prüfsumme und Start der `MainActivity` auf dem Gerät prüfen.
- Das Testgerät ist das Moto G77. Bei mehreren ADB-Geräten das Ziel explizit auswählen. Bestimme die installierte APK mit `adb shell pm path de.meshcore.pocketmonitor`, hole sie mit `adb pull` und vergleiche mit `apksigner verify --print-certs` den SHA-256-Zertifikatsdigest. Signiere die Build-Ausgabe mit `apksigner sign` über den vorhandenen lokalen Keystore. Bei unterschiedlicher Signatur die Daten durch Beibehalten der vorhandenen Installation schützen. Vergleiche nach dem Update den APK-Dateihash und prüfe `adb shell am start -W -n de.meshcore.pocketmonitor/.MainActivity`. Zugangsdaten und lokale Geräteseriennummern bleiben außerhalb von Git.
