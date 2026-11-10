# MeshCore PocketMonitor

Native Android-App in Kotlin und Jetpack Compose. Die App verbindet sich per BLE mit MeshCore-Companions, protokolliert deren Notifications lokal und zeigt empfangene Funkpakete mit Hop-Namen aus der Kontaktliste in einer kompakten Live-Ansicht. Android 8.0 oder neuer, Build mit JDK 17+, Android SDK 36 und dem enthaltenen Gradle-Wrapper.

## Starten

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. **Geräte suchen** startet einen bis zu zwölf Sekunden langen Scan nach connectable BLE-Geräten, die den Nordic UART Service ankündigen. Unter Android 12+ benötigt die App die Berechtigung für Geräte in der Nähe. Unter Android 8–11 erfordert der BLE-Scan präzisen Standort und einen aktivierten Systemstandort.
2. Gerät im Auswahlmenü wählen und **Verbinden** antippen. Wenn Android eine Kopplung verlangt, den PIN des Companions im Systemdialog eingeben.
3. Nach dem Verbinden lädt die App die Kontakte. **Kontakte** zeigt Namen, Gerätetypen, die ersten drei Bytes der Public Keys und die Anzahl je vorhandenem Typ. **Aktualisieren** liest den gesamten Bestand erneut.
4. **Live-Funk** zeigt die neuesten Funkpakete zuerst. Antippen öffnet die Paketdetails mit Hop-Präfixen, vollständigen Namen, Auflösungsstatus und vollständigem Hexdump. **Rohdaten** zeigt auch Companion-Antworten und unbekannte Events. **Pause** oder Ziehen an der Liste friert den sichtbaren Ausschnitt ein. **Fortsetzen** springt zu den neuesten Einträgen. **Trennen** schließt die GATT-Verbindung und lässt das Journal auslaufen.

BLEBeacons diente als Vorlage für Gradle, Activity, ViewModel, Berechtigungsabfrage, Scan-Callbacks und lokale Dateiaufzeichnung. GATT und Companion-Protokoll sind hier neu implementiert. Die App hat keine Internetberechtigung und nutzt weder Cloud noch MQTT. App-Daten sind von Backup und Gerätetransfer ausgeschlossen.

## BLE-Transport

| Funktion | Umsetzung |
| --- | --- |
| GATT | Service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`, Write mit Bestätigung an `6E400002-…`, Notifications von `6E400003-…` via CCCD `0x2902`. |
| MTU | Fordert 512 an und zeigt die ausgehandelte MTU an. Ab 179 passen die 176 Bytes der referenzierten Firmware in eine ATT-Notification. Bei einer kleineren ausgehandelten MTU warnt die App vor möglichen Einschränkungen. |
| App Start | `01 00 00 00 00 00 00 00` gefolgt von UTF-8 `PocketMonitor`. Erwartet `SELF_INFO` (`0x05`). |
| Device Query | `16 03` nach bestätigtem App Start. Erwartet `DEVICE_INFO` (`0x0D`). |
| Kontaktliste | `04` nach abgeschlossener Initialisierung und bei manueller Aktualisierung. Ab BLE-MTU 151 passt ein Kontaktframe vollständig in eine Notification. |
| Passives RX | Speichert sämtliche TX-Notifications bytegetreu, auch Antworten, unbekannte Codes und Duplikate. `0x88` speist die Live-Funk-Ansicht. |

Die App schreibt den GATT-Deskriptor zum Abonnieren und sendet über BLE die beiden Initialisierungskommandos und die lesende Kontaktabfrage. Sie löst keine LoRa-Sendungen aus. Die Firmware kann weiterhin gemäß ihrer eigenen Konfiguration arbeiten.

Die Protokolldokumentation beschreibt den BLE-Transport und listet `0x88` als `PACKET_LOG_DATA`. Die Feldgrenzen stammen aus `logRxRaw`. Die Firmware sendet den Push bei aktiver Verbindung und nur, wenn er in die maximale Framegröße passt. [Companion-Dokumentation](https://docs.meshcore.io/companion_protocol/), [Companion-Firmware](https://github.com/meshcore-dev/MeshCore/blob/main/examples/companion_radio/MyMesh.cpp), [Firmware-Transportheader](https://github.com/meshcore-dev/MeshCore/blob/main/src/helpers/BaseSerialInterface.h)

## Kontaktliste und Hop-Auflösung

`CMD_GET_CONTACTS` (`0x04`) wird ohne optionalen `since`-Zeitstempel gesendet. Die Referenzfirmware antwortet mit `CONTACTS_START` (`0x02`, 32-Bit-Gesamtzahl), je einem `CONTACT`-Frame (`0x03`) und `END_OF_CONTACTS` (`0x04`, jüngstes Änderungsdatum). Jeder 148-Byte-Kontaktframe enthält den 32-Byte-Public-Key, Typ, Flags, kodierte Ausgangspfadlänge (`0xFF` bei unbekanntem Pfad), 64 Bytes Ausgangspfad, 32 Bytes für den nullterminierten Anzeigenamen, Advert-Zeit, vorzeichenbehaftete Koordinaten in Millionstel Grad und Änderungszeit. Die 32-Bit-Felder sind Little Endian. Gerätetypen werden vollständig übernommen, einschließlich unbekannter Typcodes. [Companion-Firmware](https://github.com/meshcore-dev/MeshCore/blob/main/examples/companion_radio/MyMesh.cpp), [ContactInfo](https://github.com/meshcore-dev/MeshCore/blob/main/src/helpers/ContactInfo.h), [Gerätetypen](https://github.com/meshcore-dev/MeshCore/blob/main/src/helpers/AdvertDataHelpers.h)

Die App sammelt die Liste auf dem GATT-Thread und übernimmt sie nach Schreibbestätigung, gültigem Endframe und passender Anzahl unterschiedlicher Schlüssel. Asynchrone Pushes wie Funk-RX oder neue Adverts laufen parallel ins Journal. Der Cache zeigt den letzten vollständigen Abruf der laufenden Sitzung. Änderungen auf dem Companion werden beim nächsten Abruf sichtbar. Ein fehlerhafter Abruf lässt den letzten vollständigen Bestand verfügbar. Nach zehn Sekunden ohne Fortschritt oder bei fehlgeschlagener GATT-Schreibbestätigung ist eine neue Verbindung für die nächste Abfrage nötig. Das Protokoll hat keine Anfrage-ID, mit der verspätete Antworten einer neuen Abfrage zugeordnet werden könnten. Passiver RX läuft dabei weiter.

Der Abruf umfasst die gespeicherten Kontakte, die der Firmware-Iterator ausgibt. Die Referenz überspringt reservierte temporäre Anonymkontakte und gibt Einträge mit `lastmod = 0` selbst ohne `since`-Argument nicht aus. Änderungen während des Iterierens können zu einer abweichenden Anzahl führen. In diesem Fall meldet die App die Liste als unvollständig. Beim Trennen wird der Cache verworfen. Eine pausierte Anzeige aus einer früheren Verbindung bleibt an ihr ursprüngliches Journal gebunden. [Firmware-Iterator](https://github.com/meshcore-dev/MeshCore/blob/main/src/helpers/BaseChatMesh.cpp), [Companion-Firmware](https://github.com/meshcore-dev/MeshCore/blob/main/examples/companion_radio/MyMesh.cpp)

Der Resolver vergleicht die tatsächlichen Hop-Bytes mit den jeweils ersten Bytes der Public Keys von geladenen Repeatern. Normale Funkpfade verwenden 1, 2 oder 3 Bytes pro Hop. Der Vierbyte-Modus ist dort reserviert. TRACE verwendet für die geplanten Hops 1, 2, 4 oder 8 Bytes gemäß den Flags und führt die gemessenen SNR-Werte separat. Ein Treffer ist nur innerhalb des geladenen Repeater-Bestands eindeutig. Unbekannte Hops bleiben in Hex sichtbar, Kollisionen erscheinen als Präfix mit Trefferzahl und in den Details mit den Kandidaten. RSSI, SNR und Positionsdaten fließen nicht in die Zuordnung ein. [Packet.cpp](https://github.com/meshcore-dev/MeshCore/blob/main/src/Packet.cpp), [TRACE-Verarbeitung](https://github.com/meshcore-dev/MeshCore/blob/main/src/Mesh.cpp), [Schlüsselabgleich](https://github.com/meshcore-dev/MeshCore/blob/main/src/Identity.h)

Der Präfixindex entsteht einmal pro vollständigem Abruf. Sichtbare Paketzeilen und offene Details lösen ihre vorhandenen Pfade mit dem aktuellen Index auf, auch bei pausierter Liste. Die Live-Ansicht kürzt lange Namen und bricht lange Pfade über mehrere Zeilen um. Pfadbytes, Paketdecoder, Journalformat und bestehender Anzeigetakt bleiben erhalten.

## Paketdecoder

| Bereich | Dekodierte Felder |
| --- | --- |
| Companion-Push `0x88` | Byte 0 Eventcode, Byte 1 SNR als vorzeichenbehaftetes Byte geteilt durch 4, Byte 2 RSSI als vorzeichenbehaftetes Byte in dBm. Ab Byte 3 beginnt das Funkpaket. Das Event enthält weder eine eigene Längenangabe noch einen Zeitstempel. Die App zählt die gelieferten Funkbytes und erfasst den Zeitpunkt des Android-GATT-Callbacks. |
| MeshCore v1 | Headerbyte mit Version, Typ und Routingmodus, optionale zwei 16-Bit-Transportcodes, Pfadlängenbyte, Hop-Präfixe und Payload-Länge. Normale Pfade tragen Präfixe mit 1, 2 oder 3 Bytes. |
| ADVERT | Öffentlicher Schlüssel, Advert-Zeit, Signaturbytes und optionale Appdaten mit Name und Position. Die App verifiziert die Signatur nicht. |
| Verschlüsselte Pakete | Sichtbare Absender- und Empfängerpräfixe, Senderschlüssel bei ANON_REQ oder Kanal-Hash bei GRP_TXT und GRP_DATA. MAC und Länge des Ciphertexts sind sichtbar. |
| ACK und Multipart-ACK | Vierbyte-Kennung und bei der Sechsbyte-Variante Versuchs- und Zufallsbyte. Bei MULTIPART auch Untertyp und Zahl der verbleibenden Teile. |
| TRACE | Tag, Auth-Code und Flags, geplanter Pfad mit eigenen Präfixbreiten und bereits gemessene SNR-Werte aus dem Header-Pfadbereich. |
| CONTROL / DISCOVER | Untertyp und Flags. Bei DISCOVER_REQ außerdem Tag, Typfilter, Präfixmodus und optionales `since`. Bei DISCOVER_RESP Tag, Knotentyp, SNR der Anfrage am antwortenden Knoten sowie 8-Byte-Präfix oder vollständiger 32-Byte-Schlüssel. |
| Vergleichskennung | Erste 8 Bytes von SHA-256 über Pakettyp und Payload wie in `Packet::calculatePacketHash`. Bei TRACE fließt zusätzlich das Pfadlängenfeld als 16-Bit-LE-Wert ein. Die Kennung dient dem Vergleich und die App zeigt Wiederholungen weiterhin einzeln an. |

Der Decoder folgt dem offiziellen Paket- und Payload-Format. Die Implementierung von TRACE, verlängerten ACKs und Vergleichshash stützt sich zusätzlich auf den Firmwarecode. [Paketformat](https://docs.meshcore.io/packet_format/), [Payload-Referenz](https://docs.meshcore.io/payloads/), [Mesh.cpp](https://github.com/meshcore-dev/MeshCore/blob/main/src/Mesh.cpp), [BaseChatMesh.cpp](https://github.com/meshcore-dev/MeshCore/blob/main/src/helpers/BaseChatMesh.cpp), [Packet.cpp](https://github.com/meshcore-dev/MeshCore/blob/main/src/Packet.cpp)

Bei Flood-Paketen zeigt die App die bereits aufgezeichneten Hops. Bei Direct-Paketen zeigt sie den noch vorhandenen Pfad. Kanal-Hash, Transportcodes und verschlüsselte Inhalte bleiben auf die dekodierbaren Felder beschränkt. Bei unbekannten Versionen, Typen und beschädigten Frames bleiben Rohdaten und die sicher erkennbaren Felder zugänglich. Da `0x88` kein Längenfeld enthält, lässt sich eine Kürzung nur erkennen, wenn dadurch eine bekannte Struktur verletzt wird.

### Vorbereitung für Sprint 3

DISCOVER-Anfragen und -Antworten liegen als typisierte RX-Daten vor. Eine spätere Suche kann anhand des 32-Bit-Tags und eines Empfangsfensters Antworten einer Anfrage zuordnen. Das Modell trennt bereits das vom Antwortknoten gemeldete SNR der Anfrage vom SNR und RSSI der lokal empfangenen Antwort. Die Firmware bietet dafür `CMD_SEND_CONTROL_DATA` (`0x37`) und zusätzlich den verarbeiteten Push `0x8E`. Sprint 2 nutzt den Funk-RX-Push `0x88`. [Companion-Firmware](https://github.com/meshcore-dev/MeshCore/blob/main/examples/companion_radio/MyMesh.cpp)

## RX-Journal

Jeder Verbindungsversuch erzeugt `files/rx/rx-<zeit>-<uuid>.tsv` im privaten App-Speicher. Jede Zeile enthält Sequenznummer, Unix-Zeit in Millisekunden und die vollständigen Notification-Bytes als Hex. Das Journal kann bei der Debug-APK über `adb shell run-as de.meshcore.pocketmonitor ls files/rx` eingesehen werden. Unveränderte Antworten können die BLE-PIN und Positionsdaten des Companions enthalten.

Der GATT-Thread kopiert jeden Frame in eine FIFO-Warteschlange. Ein separater IO-Worker schreibt die Datei. Die Anzeige liest höchstens fünfmal pro Sekunde die letzten 500 geschriebenen Funk-Notifications beziehungsweise die letzten 500 Notifications aller Typen. Neue Funkzeilen werden im Hintergrund dekodiert. Decoderfehler bleiben auf die Anzeige beschränkt. Beim Pausieren bleibt der angezeigte Ausschnitt stehen, während BLE und Journal weiterlaufen. Bei vollem Puffer oder Schreibfehler beendet die App den Empfang mit einer sichtbaren Fehlermeldung. Beim normalen Trennen werden die bereits eingereihten Frames geschrieben. Journaldateien bleiben lokal liegen, bis die App-Daten gelöscht werden.

Die App erfasst nur von Android zugestellte Notifications. Ausfälle vor dem Callback, etwa durch Firmware-Warteschlangen oder BLE-Abbrüche, lassen sich mangels Ende-zu-Ende-Sequenznummern im Funk-RX-Push nicht rekonstruieren. Die App betreibt keinen Vordergrunddienst. Eine bestehende Verbindung bleibt beim Verlassen der Activity nur erhalten, solange Android den Prozess laufen lässt.

## Verifikation

- Sprint 2.2 wurde offline mit JDK 21, Android SDK 36 und Gradle 8.13 geprüft. `assembleDebug`, `testDebugUnitTest` (25 Tests) und `lintDebug` waren erfolgreich. Lint meldet keine Probleme.
- Neun neue Unit-Tests prüfen unter anderem eine 300 Einträge lange Kontaktliste, leere und fehlerhafte Listen, Gerätetypen und Metadaten, eingestreute Pushes, Antworten vor der Schreibbestätigung, reguläre und TRACE-Präfixbreiten, Kollisionen, Aktualisierungen und Verbindungswechsel. Die rückwirkende Auflösung verwendet denselben bereits dekodierten Paketpfad. Die BLE-Prüfungen für Sprint 2.2 verwenden synthetische Frames.
- Die Tests prüfen Companion-Feldgrenzen, vorzeichenbehaftete Signalwerte, verschlüsselte und unbekannte Typen, Pfadvarianten, DISCOVER, beschädigte Frames und die bytegetreue Erhaltung der Daten. Der Journaltest prüft die Reihenfolge und alle Bytes von 5.000 synthetischen Notifications.
- Drei echte Funkpakete (GRP_TXT, erweitertes ACK und TRACE) stammen aus einem lokalen MeshCore-OnAir-RX-Archiv. Die Fixtures enthalten nur die originalen Funkbytes ohne Observer-Metadaten. Die Tests für das Companion-Format verwenden synthetische Notifications. Die verfügbaren Companion-Aufzeichnungen aus Sprint 1 stammen vom BlueZ-Testdienst.
- Im vorigen Sprint wurden auf dem Moto G77 mit lokalem BlueZ-GATT-Testdienst und MTU 498 die Live-Liste, Paketdetails, Rohdatenansicht, Wischgeste und Pause/Fortsetzen geprüft. Während der Pause gingen 560 weitere Notifications ein. Nach dem Fortsetzen zeigte die Liste die jüngsten 500 Funkereignisse. Das Journal enthielt 581 von 581 Notifications bytegleich in Sendereihenfolge. Der Hardwaretest verwendete BLE-Wiedergabe mit synthetischen Events und archivierten Funkbytes.
- Bei aktiver Nachführung wurden 560 weitere Notifications mit rund 42 pro Sekunde eingespielt. Die Ansicht erreichte den neuesten Eintrag. Androids `gfxinfo` markierte 63 von 252 gerenderten Frames als verspätet. Die Darstellung kann bei dieser Last sichtbar ruckeln.
