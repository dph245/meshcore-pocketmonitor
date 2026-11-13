# Nachrichtenabgleich und private Unterhaltungen

**Nachrichten** enthält die Ansichten **Kanäle** und **Privat**. Beide verwenden die lokale Historie des ausgewählten Companions, dieselbe Chat-Ansicht und indizierte Seiten mit bis zu 200 Nachrichten. Die Chatzeile zeigt Absender, Senderzeit und Text. Bei Kanälen kann sie sowohl die Zahl der protokollierten Funkempfänge als auch die Abholung aus der Warteschlange ausweisen.

## Schnittstelle der Firmware

Die Implementierung folgt der [Companion-Protokolldokumentation](https://github.com/meshcore-dev/MeshCore/blob/main/docs/companion_protocol.md) und der geprüften [Referenzfirmware 26079dea](https://github.com/dph245/MeshCore/blob/26079dea9b483f41d75b711205be9b690d15afcc/examples/companion_radio/MyMesh.cpp). `CMD_SYNC_NEXT_MESSAGE` (`0x0A`) holt jeweils das älteste Element der gemeinsamen Warteschlange. Die Antwort ist Channel-Text (`0x08`/`0x11`), Kontakttext (`0x07`/`0x10`), ein Channel-Datagramm (`0x1B`) oder `NO_MORE_MESSAGES` (`0x0A`). Ein `MSG_WAITING`-Push (`0x83`) enthält nur den Hinweis auf einen neuen Eintrag. V3-Antworten enthalten zusätzlich SNR und zwei reservierte Bytes. Bereits vor der Protokollaushandlung gespeicherte Frames können das alte Format haben.

- `getFromOfflineQueue` entfernt das Element vor `writeFrame`. Der Abruf ist damit zugleich das Abholen. Die API bietet weder einen Lesestatus pro App noch eine nachträgliche Empfangsbestätigung, einen Cursor oder eine Wiederholung bereits entfernter Einträge. Eine zweite App am selben Companion teilt sich die Warteschlange.
- Die Warteschlange liegt im RAM. Der geprüfte Standardwert beträgt 16 Einträge und kann beim Firmware-Build geändert werden. Bei vollem Puffer ersetzt die Firmware den ältesten Channel-Eintrag. Enthält der volle Puffer ausschließlich Kontakt-Einträge, verwirft sie den neuen Eintrag. Ein Neustart des Companions leert den Puffer.
- Funk-ACKs für private Nachrichten erzeugt die Firmware beim Funkempfang. Der Abholbefehl löst selbst keine LoRa-Nachricht aus. Der Sender-Zeitstempel stammt von der Senderuhr.

PocketMonitor startet nach der Initialisierung, den Kontakt- und Kanalabfragen und der ersten Telemetrieabfrage. Eine ausbleibende Abholantwort nach bestätigtem BLE-Schreiben lässt spätere Telemetrieabfragen weiterlaufen. Es wiederholt den Abruf nach einem Wartehinweis. Es hält immer nur einen Abruf offen und wartet auf GATT-Schreibbestätigung sowie den SQLite-Commit der empfangenen Nachricht, bevor es den nächsten startet. Bereits an den Writer übergebene Frames werden beim Trennen zu Ende geschrieben. Nach einer Unterbrechung setzt eine erneute Verbindung den Abruf der verbleibenden Einträge fort. Eine zwischen dem Entfernen auf dem Companion und dem lokalen Commit verlorene Nachricht lässt sich über diese API nicht zurückholen. Unter MTU 179 lässt die App die Warteschlange unangetastet, weil 176-Byte-Antworten sonst je nach BLE-Implementierung verloren gehen oder abgeschnitten werden können.

## Identität, Dubletten und Diagnose

- Die Firmware authentifiziert und entschlüsselt empfangene PM mit dem gemeinsamen Geheimnis eines passenden Kontakts. Die BLE-Antwort enthält Klartext und sechs Bytes des Kontakt-Public-Keys. Bei einem eindeutigen Treffer in der Kontaktliste wird der vollständige Public Key zur Konversations-ID. Unbekannte oder mehrdeutige Präfixe bleiben als eigene Präfix-Unterhaltung sichtbar. Bei signiertem Room-Text ist die Kontaktidentität die des Rooms. Die vier Bytes des ursprünglichen Absenderpräfixes werden separat angezeigt. Verschlüsselte Funkframes ohne passende entschlüsselte Antwort bleiben in der Funkdiagnose.
- Channel-Antworten nennen nur den Slot. PocketMonitor ordnet ihn anhand der in dieser Verbindung erfolgreich gelesenen Kanalinfos dem Schlüsselfingerabdruck zu. Fehlt der Slot, zeigt die App eine separate Unterhaltung mit der Slotnummer. Die Firmware liefert keinen Schlüsselfingerabdruck zum Zeitpunkt des Empfangs. Bei Änderungen zwischen Empfang und Abruf kann deshalb die ursprüngliche Zuordnung fehlen.
- Funk-Dubletten behalten die vorhandene Paket-Vergleichskennung. Abholantworten liefern keinen Funk-Pakethash. Channel-Nachrichten werden zwischen den Quellen über Kanalidentität, Sender, Senderzeit und Text abgeglichen. Identische Nachrichten desselben Absenders in derselben Sekunde lassen sich aus einer Abholantwort allein nicht unterscheiden. PM-Dubletten werden pro Kontakt anhand von Senderzeit, Text und gegebenenfalls Room-Absenderpräfix zusammengeführt.
- SQLite speichert die empfangenen BLE-Frames bytegetreu neben den verknüpften Chat-Einträgen. CLI-Frames, Datagramme und beschädigter Text bleiben im Rohjournal. Abholung und Funkempfang haben getrennte Zähler. Beide Quellen unterliegen der bestehenden Aufbewahrung von 30 Tagen ab lokaler Zustellung. Decodierter Inhalt ist nach einem App-Neustart offline im privaten App-Speicher verfügbar.

## Prüfung auf dem Moto G77

Der synthetische BlueZ-Companion lieferte bei MTU 498 Channel-Text sowie PM bekannter und unbekannter Kontakte. Der Testdienst trennte BLE mitten im Abruf. Nach Prozessneustart war die schon gespeicherte PM offline sichtbar. Nach der Wiederverbindung wurde der restliche Puffer bis zur Leerantwort gelesen. Ein Wartehinweis löste einen weiteren Abruf aus. Ein zweiter Companion-Schlüssel am gleichen BLE-Gerät öffnete eine getrennte Historie.

55 Unit-Tests sowie Android Lint für Debug und Release liefen erfolgreich. Die SQLite-Instrumentierung auf dem Moto G77 prüfte unter anderem beide Reihenfolgen von Funkempfang und Abholung, Dubletten nach Wiederverbindung, getrennte Companion-Historien, unbekannte und mehrdeutige PM-Absender, die 30-Tage-Grenze sowie RX- und Chat-Paginierung.

Der Scrollvergleich verwendete zwei R8-Release-Builds, 90 Hz, 50.000 synthetische Einträge und je drei Läufe in wechselnder Reihenfolge. In Live-Funk werden 500 Zeilen dargestellt. Die Messung umfasst die Compose-Listen mit acht Wischgesten je Lauf. SQLite-Seitenwechsel und gleichzeitiger Funkempfang waren außerhalb der Messung.

| Ansicht | Median p50 vor Sprint → Sprint | Median p95 vor Sprint → Sprint |
| --- | ---: | ---: |
| Live-Funk | 7 → 7 ms | 13 → 11 ms |
| Chat | 10 → 10 ms | 14 → 12 ms |

Beim Einfügen von 250 Nachrichten während einer Scroll-Mutation blieben sichtbare Nachrichten-ID und Pixelversatz im Release-Test erhalten.
