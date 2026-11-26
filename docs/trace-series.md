# TRACE-Presets und Messreihen

Unter **DISCOVER → TRACE** bleibt **TRACE senden** für den bisherigen Einzelversuch verfügbar. Über **Strecken-Presets** lassen sich der aktuelle Entwurf speichern und vorhandene Presets laden, aktualisieren, umbenennen, duplizieren und löschen. Ein Hop kann im Editor verschoben oder entfernt werden. **Rückweg spiegeln** verwendet den Wendepunkt einmal. Bei einem explizit eingegebenen Hin- und Rückweg kann der letzte Hinweg-Hop als Wendepunkt angegeben werden. Ohne Wendepunkt bleibt die Richtung der Hops unbekannt. Der abschließende Empfang am Companion hat einen eigenen SNR-Wert.

**Messreihe** bietet 1, 2, 3, 5 oder 10 Versuche. Mindestabstand und Antwort-Timeout stehen voreingestellt auf 30 Sekunden und können jeweils zwischen 1 und 3.600 Sekunden eingestellt werden. Die **Messhistorie** enthält die Fortschritts- und Abbruchanzeige sowie die Ergebnisse früherer Reihen des ausgewählten Companions. Die Erfolgsquote bezieht sich auf die gespeicherten Versuche, einschließlich unterbrochener Versuche. Minimum, Median und Maximum werden aus beantworteten Versuchen pro Pfadposition und für den Schluss-Empfang berechnet. Ein Hash, der in beiden Richtungen vorkommt, behält getrennte Werte pro Position.

## Firmware und Zuordnung

Geprüfter Firmwarestand ist MeshCore `26079dea9b483f41d75b711205be9b690d15afcc`. Referenzen sind `examples/companion_radio/MyMesh.cpp` (TRACE-Push ab Zeile 855, direkter Timeout ab 891, Geräte- und Funkparameter ab 1056, TRACE-Befehl ab 1815) und `src/Dispatcher.cpp` (Sendewarteschlange und CAD ab Zeile 275).

- `CMD_SEND_TRACE_PATH` (36) nutzt den vorhandenen seriellen Companion-Befehlsweg. Der Schreibabschluss gilt nur für BLE bzw. TCP. `RESP_CODE_SENT` (6) bestätigt die Firmware-Annahme und liefert eine Schätzung für das Antwortfenster. Die Firmware prüft danach ihr Airtime-Budget und den Kanal. Für den tatsächlichen TX-Zeitpunkt gibt es hier keinen korrelierbaren Zeitstempel. Eine passende Funkantwort belegt den erfolgreichen Umlauf. Die angezeigte Antwortzeit beginnt beim App-Sendeauftrag und enthält etwaige Warteschlangenzeit.
- Für eine Funkantwort müssen Sitzung, Tag, Auth-Code, Flags und die vollständige geordnete Hashliste übereinstimmen. Der Auth-Code ist ein Korrelationsfeld. Ein Hash-Präfix bleibt bei Namenskollisionen mehrdeutig. Späte Antworten bleiben als Rohframes im Journal und ändern kein abgeschlossenes Serienergebnis. Beim bestehenden Einzel-TRACE kann eine späte eigene Antwort weiterhin angezeigt werden.
- Der Mindestabstand gilt zwischen App-Sendeaufträgen. Der Antwort-Timeout der Reihe beginnt bei der verarbeiteten Companion-Annahme. Nach einem Timeout wartet PocketMonitor zusätzlich bis zum Ende des geschätzten Firmware-Fensters, sofern es noch läuft. Die App nutzt dafür die Firmware-Schätzung plus 2 s mit einer Untergrenze von 5 s und einer Obergrenze von 10 min. Eine belegte Companion-Befehlsstrecke verschiebt den nächsten Versuch. Die Schätzung liefert keine Garantie für den tatsächlichen Funkzeitpunkt. Sendeablehnung und unklarer Befehlsstatus beenden die Reihe. Nach Abbruch kann ein bereits angenommener Auftrag noch auf dem Companion ausgeführt werden.
- Zu Beginn liest PocketMonitor `SELF_INFO` frisch für Frequenz, Bandbreite, SF, CR und eingestellte TX-Leistung. Die Firmwareversion und Companion-Protokollversion stammen aus `DEVICE_INFO` derselben Sitzung. Der geprüfte Wire-Stand liefert keinen eigenen Radio-IC. Das Herstellerfeld bezeichnet das Board. Fehlende Werte erscheinen als **unbekannt**. Für SF7 und SF8 wird jeweils eine eigene Reihe gestartet, nachdem der Companion auf den gewünschten SF eingestellt wurde.

## Speicherung

SQLite-Schema 11 speichert Presets, Messreihen und Versuche. Die vollständige Companion-Identität ist der Geltungsbereich. Eine Reihe speichert eine Kopie des Pfads, der Optionen und der Funkparameter. Der Versuch speichert den eigenen Tag, Auth-Code, Zeitpunkt, Status, Firmware-Annahme, Antwortzeit und die SNR-Folge. Änderungen oder das Löschen eines Presets ändern frühere Reihen nicht. App-Sicherungen enthalten die drei TRACE-Tabellen. Sicherungen mit Schema 10 bleiben einlesbar.

Die Prozesssitzung steuert die automatische Reihe unabhängig vom geöffneten Tab. Trennung und Prozessende unterbrechen sie. Beim nächsten Verbinden bzw. Start werden keine ausstehenden Aufträge nachgesendet. Laufende Zeilen aus einer beendeten App-Sitzung erscheinen nach dem Öffnen der Datenbank als abgebrochen.

## Prüfungen

```sh
./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
adb shell am instrument -w de.meshcore.pocketmonitor.test/de.meshcore.pocketmonitor.HistoryInstrumentation
```

Die Standardinstrumentierung enthält SQLite-Prüfungen für Preset-Operationen, Companion-Isolation, geordnete Hin- und Rückwege, Schema-Migration, App-Backup und Erholung nach simuliertem Prozessende. Der TCP-Loopback prüft den Sendeabstand, die Funkparameterabfrage, Antwort vor Sendebestätigung, Timeout, verspätete Antwort beim nächsten Versuch, Abbruch mit ausstehender Bestätigung, Sendeablehnung und Verbindungsverlust.

Die zusätzliche GATT-Prüfung verwendet einen eingeschalteten BlueZ-Adapter mit Peripheral-Unterstützung sowie Python mit `dbus` und `gi`. Auf dem Android-Gerät müssen Bluetooth und die Scan-/Verbindungsberechtigungen für das Testpaket verfügbar sein. Den Testdienst auf dem Linux-Rechner starten und den Runner mit der Zusatzoption aufrufen.

```sh
/usr/bin/python3 tools/trace-ble-fixture.py
adb shell am instrument -w -e traceBle true de.meshcore.pocketmonitor.test/de.meshcore.pocketmonitor.HistoryInstrumentation
```

Der simulierte Companion beantwortet den ersten TRACE, bestätigt den zweiten und trennt dann die GATT-Verbindung. Der Test liest die beiden gespeicherten Versuche nach der automatischen Wiederverbindung erneut.

### Ergebnis vom 26. November 2026

- JVM-Tests bestanden in Debug und Release mit jeweils 118 Tests. Android Lint meldete je Variante 0 Fehler, 5 Warnungen und 1 Hinweis.
- SQLite-Instrumentierung und TCP-Loopback liefen auf dem Moto G77. Der GATT-Lauf mit BlueZ bestätigte den Abbruch beim zweiten Versuch, den Erhalt der Ergebnisse und die Wiederverbindung ohne weiteren TRACE-Sendeauftrag.
- Eine echte LoRa-Streckenmessung mit SF7 und SF8 steht aus. Der GATT-Test simuliert die Companion-Antworten.
- Die R8-Release-APK mit Baseline Profile wurde mit dem vorhandenen lokalen Entwicklungsschlüssel signiert und auf dem Moto G77 installiert. Der APK-Hash stimmte überein. Kaltstart, TRACE-Ansicht und Messhistorie wurden auf dem Gerät geprüft. SHA-256 `9c766c19eae555c0fab2f8810f1189c8f92a44ee375140f12fe382c2e88d2df5`.
