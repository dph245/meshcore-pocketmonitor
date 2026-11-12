# Scroll-Performance

## Renderpfad

Live-Funk und Chat nutzen Compose `LazyColumn`. Compose baut nur sichtbare und vorab geladene Zeilen auf. Die Schlüssel basieren auf der Journal-Sequenz und gelten jeweils innerhalb des Journals beziehungsweise der Konversation. Beim Scrollen ohne Funkverkehr fallen vor allem Subcomposition, Textlayout und der Aufbau der Accessibility-Semantik für neu sichtbare Zeilen an. Die Live-Liste ist schon auf 500 Funkzeilen begrenzt. Das RX-Journal selbst und der Chat-Verlauf behalten ihre bisherige Reichweite.

- Live-Funk formatiert Zeit, Signalwerte, Paketkurztexte und Hop-Namen beim Aufbau des Hintergrund-Snapshots. Bei neuen Paketen bleiben die Decodes und Anzeigemodelle der überlappenden Zeilen erhalten. Bei reinen Roh-RX-Updates kann die Funkliste dieselbe Instanz weiterverwenden. Die Kopie aus dem Journalfenster entsteht auf `Dispatchers.Default`. Das 200-ms-Intervall bündelt mehrere Ankünfte.
- Funkzeilen bekommen einen `contentType` entsprechend ihren optionalen Textblöcken. Eine Textmessung erfasst beide Signalwerte, und der Zeilenhintergrund zeichnet die Trennlinie. Die beiden Listen verwenden `BasicText` für ihre Zeilentexte. Chatblasen zeichnen ihren Hintergrund direkt. Jede Zeile liefert einen zusammenhängenden Text für Accessibility. Die Live-Zeilen bleiben anklickbar.
- Der Chat liest wie bisher per Cursor vom Journal und decodiert nur den neuen Bereich. Er sortiert den neuen Stapel nach Zeitstempel und Sequenz. Bei regulärer Ankunft hängt er ihn an, bei verspäteten Paketen führt er ihn linear mit der vorhandenen Liste zusammen. Das Erstellen einer neuen Liste kostet bei einem geänderten Kanal weiterhin O(n) Referenzkopien. Empfang ohne Änderungen im Kanalverlauf schiebt nur den Cursor vor.
- Während einer Chat-Geste oder eines Flings sammelt die Hintergrundverarbeitung weiter Nachrichten. Anschließend übernimmt die Liste den neuesten Stand und hält die zuvor sichtbare Nachricht mit ihrem Pixelversatz, auch bei größeren Einfügungen. Die Scrollposition wird pro Konversation beim Verlassen der Ansicht gespeichert. Live-Funk pausiert beim Wegscrollen und setzt das Folgen auf Wunsch über „Fortsetzen“ fort.

## Gerätemessung

Moto G77, Android 16, physischer Displaymodus 90 Hz. Je drei Läufe pro Build und Kombination, in wechselnder A/B-Reihenfolge. Die Tabelle zeigt den Median der von Android gemeldeten Werte je Lauf.

| Ansicht | Synthetischer Bestand | p50 vorher → nachher | p95 vorher → nachher | Janky Frames vorher → nachher |
| --- | ---: | ---: | ---: | ---: |
| Live-Funk | 100 Pakete, 100 Live-Zeilen | 89 → 77 ms | 200 → 150 ms | 30,1 → 26,6 % |
| Live-Funk | 50.000 Pakete, 500 Live-Zeilen | 113 → 48 ms | 200 → 150 ms | 40,8 → 18,8 % |
| Chat | 100 Nachrichten | 73 → 57 ms | 150 → 117 ms | 29,6 → 22,5 % |
| Chat | 50.000 Nachrichten | 61 → 61 ms | 150 → 105 ms | 20,1 → 23,8 % |

Das p95 sank in allen vier Fällen. Der Anteil als ruckelig klassifizierter Frames beim großen Chat schwankte vorher zwischen 12,4 und 27,8 %, nachher zwischen 10,2 und 23,9 %. Sein Median stieg im A/B-Lauf. Die Ergebnisse zeigen keine konsistente Verschlechterung allein durch die Bestandsgröße. Auch der optimierte Build verfehlte noch Frame-Deadlines.

Je ein zusätzlicher `atrace` mit vier Wischgesten pro Build und Ansicht bei großem Bestand zeigt die CPU-Zeit des Main-Threads innerhalb der Trace-Abschnitte. Sie wurde aus den `sched_switch`-Intervallen ermittelt. Die Abschnitte können ineinander liegen.

| Abschnitt, Median | Live-Funk vorher → nachher | Chat vorher → nachher |
| --- | ---: | ---: |
| `Choreographer#doFrame` | 26,04 → 17,47 ms | 21,05 → 12,37 ms |
| `Compose:recompose` | 5,28 → 4,06 ms | 5,49 → 2,90 ms |
| `compose:lazy:prefetch:compose` | 9,73 → 8,57 ms | 8,26 → 5,75 ms |
| Aufbau der sichtbaren Semantik-Knoten | 6,10 → 1,40 ms | 3,89 → 1,42 ms |
| `AndroidOwner:measureAndLayout` | 2,96 → 4,70 ms | 4,58 → 3,84 ms |

Die Zeitspitzen lagen überwiegend auf dem Main-Thread. Im Vorher-Build dauerte ein `doFrame`-Abschnitt in den Traces bis zu 131 ms im Live-Funk und 116 ms im Chat. Der GPU-p95 lag im A/B-Lauf je nach Kombination im Median bei 5 bis 9 ms. Auf dem Gerät war ein Accessibility-Dienst aktiv. Der stärkste Rückgang eines einzelnen Trace-Abschnitts zeigte sich beim Aufbau der sichtbaren Semantik-Knoten.

Der Gerätetest hielt bei 50.000 Chat-Nachrichten eine Scroll-Mutation aktiv, fügte 250 Nachrichten hinzu und prüfte nach der Übernahme dieselbe sichtbare Nachrichten-ID und denselben Pixelversatz. Die UI-Hierarchie zeigte vollständige Zeilentexte und weiterhin klickbare Funkzeilen. Ein Dauertest mit realem BLE-Empfang war nicht Teil der Messung. Die weniger häufigen UI-Aktualisierungen bei Empfang sind durch die Implementierung und den synthetischen Burst-Test belegt. Frame-Zeiten unter realer Funklast sind offen.

## Messung wiederholen

Auf einem entsperrten Android-Gerät im Hochformat mit gleichbleibender Display-Einstellung ausführen. Die Fixture verwendet die produktiven Listen und eine eigene App-ID. Sie erzeugt deterministische Zeilen im Speicher. BLE, Journal-I/O und der erste Aufbau liegen außerhalb der Scrollmessung. Das reguläre APK entsteht durch einen weiteren Build ohne die Option.

```bash
./gradlew --offline :app:assembleDebug -PscrollBenchmark
cp app/build/outputs/apk/debug/app-debug.apk /tmp/pocket-scroll-candidate.apk
tools/measure-scroll.sh /tmp/pocket-scroll-candidate.apk /tmp/pocket-scroll-results 3
./gradlew --offline testDebugUnitTest assembleDebug lintDebug
```

Das Skript kompiliert die Fixture auf dem Gerät mit ARTs `speed`-Modus, startet jede Kombination neu, wärmt sie mit zwei Wischgesten auf und misst acht Wischgesten mit `dumpsys gfxinfo … framestats`. Pro Lauf entstehen Rohdaten und ein Display-Dump. Für einen A/B-Vergleich beide APKs jeweils mit einem Lauf in wechselnder Reihenfolge messen. Die verwendete Vorher-APK entstand aus dem Arbeitsstand mit Kanalansicht vor den Optimierungen, ergänzt um die synthetische Activity und den Aufruf der bestehenden Listen aus dieser Activity.

Für einen Main-Thread-Trace die gewünschte Kombination starten und nach dem Aufwärmen rund um die Wischgesten `atrace` verwenden.

```bash
pkg=de.meshcore.pocketmonitor.scrollbenchmark
adb shell am start -W -n "$pkg/de.meshcore.pocketmonitor.ScrollBenchmarkActivity" --ez chat true --ei rows 50000
adb shell atrace --async_start -b 32768 -a "$pkg" gfx view input sched freq
# Auf dem Gerät wischen, dann den Trace sichern.
adb shell atrace --async_stop gfx view input sched freq > /tmp/pocket-scroll.atrace
```

Der Gerätetest für den Scrollanker hält eine Scroll-Mutation aktiv und liefert dabei 250 Nachrichten. Er prüft, dass die sichtbare Liste den Stapel erst anschließend übernimmt und die Nachricht mit gleichem Pixelversatz stehen bleibt.

```bash
pkg=de.meshcore.pocketmonitor.scrollbenchmark
adb shell am force-stop "$pkg"
adb shell am start -W -n "$pkg/de.meshcore.pocketmonitor.ScrollBenchmarkActivity" \
    --ez chat true --ei rows 50000 --ez verifyAnchor true
adb logcat -d --pid "$(adb shell pidof "$pkg" | tr -d '\r')" -s PocketScrollBenchmark:I AndroidRuntime:E
```
