# MeshCore PocketMonitor

## Datei pflegen

Prüfe diese Datei nach jedem Sprint. Ergänze nur überprüfte Erkenntnisse, die künftige Änderungen erleichtern. Aktualisiere nachweislich veraltete Einträge, löse Widersprüche und bewahre weiterhin gültige Anweisungen. Halte die Einträge kurz und vermeide Duplikate und Sprint-Protokolle. Erwähne Änderungen an dieser Datei in der Sprint-Zusammenfassung.

## Nachrichtenhistorie

- Der Lesestatus liegt in SQLite (`message.unread`). `CompanionHistory.unreadConversations()` liefert die Markierungen nach Companion und Unterhaltung. Änderungen am Lesestatus müssen bei geänderten Zeilen die History-Revision erhöhen, damit die Oberfläche die Markierungen neu lädt.
- Beim Öffnen einer Unterhaltung erfasst `CompanionHistory.messages()` mit `includeUnreadThrough = true` die höchste ungelesene Nachrichten-ID in `unreadThrough`. `markReadThrough()` erfasst damit auch ältere, noch nicht geladene Seiten und lässt spätere Eingänge ungelesen.
- Die Sammelaktion markiert alle ungelesenen Kanalnachrichten der ausgewählten Companion-Historie in einem SQLite-Update. Sie erfasst auch archivierte Kanäle und unbekannte Slots. Der Filter überspringt Privatnachrichten anhand von `PRIVATE_ID_PREFIX` (`pm:`).

## Datenbanktests

- SQLite-Historie, Migration und Paging prüft der eigene Android-Instrumentierungsrunner `HistoryInstrumentation`. `:app:assembleDebugAndroidTest` baut das Test-APK. Zum Ausführen nach Installation beider Debug-APKs `adb shell am instrument -w de.meshcore.pocketmonitor.test/de.meshcore.pocketmonitor.HistoryInstrumentation` nutzen und auf `PASS` in der Ausgabe prüfen.

## Release auf dem Testgerät

- `:app:assembleRelease` erzeugt `app-release-unsigned.apk`. Für Updates der bestehenden Testinstallation mit dem lokalen Android-Debugschlüssel signieren und das Zertifikat vorab mit der installierten APK abgleichen. Mit `adb install --no-streaming -r` aktualisieren, anschließend APK-Prüfsumme und Start der `MainActivity` auf dem Gerät prüfen.
