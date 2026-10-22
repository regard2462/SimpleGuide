<img src="docs/logo/logo.png" alt="Simple-Guide-Logo" width="96" align="right">

# Simple Guide

Simple Guide ist ein Audioguide für Spaziergänge durch die Stadt. Handy in die Tasche, Kopfhörer auf, Musik an
und loslaufen: Kommst du an etwas vorbei, das einen Wikipedia-Artikel hat, sagt dir die App, wo es ist und was es
damit auf sich hat – „Wenn du nach rechts schaust, siehst du den Bertoldsbrunnen. Er ist ein Denkmal in der
Altstadt von Freiburg im Breisgau …“. Deine Musik pausiert dafür kurz und läuft danach von selbst weiter.

Die Stimme läuft komplett auf dem Handy (Piper, Kokoro, Supertonic über sherpa-onnx). Die Texte kommen aus der
Wikipedia, unterwegs nachgeladen oder für ganze Städte vorab heruntergeladen. Formuliert wird ohne Sprachmodell:
mit Regeln, Vorlagen und dem, was in den Artikeln steht.

Simple Guide ist ein Fork von [Simple Narrator](https://github.com/regard2462/SimpleNarrator) und übernimmt
dessen Sprachausgabe und Textaufbereitung (Zahlen, Daten, Abkürzungen). Alles andere ist bewusst weggelassen –
keine Karte, keine Navigation, kein Konto.

[English version](README.md)

## Was die App kann

| Bereich | Funktion |
|---|---|
| Tour | Startet als Vordergrunddienst mit Standort und läuft bei ausgeschaltetem Bildschirm weiter. Die Gehrichtung kommt aus der Bewegung (nicht aus dem Kompass, das Handy steckt ja in der Tasche); daraus wird „rechts“, „links“, „vor dir“, „hinter dir“. |
| Unterbrechen | Vor jeder Erzählung holt sich die App vorübergehend den Audio-Fokus: Musik-Apps pausieren (oder werden leiser, einstellbar) und spielen danach weiter. Ein leiser Hinweiston kündigt die Erzählung an. |
| Abbrechen | **Pause am Kopfhörer oder am Handy** beendet die laufende Erzählung. Übersprungen wird nur dieser Ort; die Tour geht weiter. „Nochmal“ in der Benachrichtigung oder in der App holt ihn zurück, „Mehr erzählen“ liest weiter. Startet man die Musik selbst wieder, hört die Erzählung ebenfalls auf. |
| Was erzählt wird | Orte im Umkreis (Grundradius einstellbar, große und berühmte Orte wie Plätze, Parks und Kirchen schon von weiter weg). Bei mehreren gewinnt der bedeutendste. Was hinter einem liegt, ist vorbei; Gehörtes kommt nicht wieder (einstellbar). Arten von Orten lassen sich abwählen; Firmen, Vereine, Haltestellen und Kunstwerke im Inneren sind von Haus aus aus. |
| Wo bin ich | Straßenname vor der nächsten Erzählung („Du bist jetzt in der Gerberau.“), auf Wunsch bei jeder neuen Straße. Hat die Straße einen eigenen Artikel, wird er erzählt, sobald man auf ihr geht. „Was ist hier?“ sagt die Straße und die bedeutendsten Orte ringsum mit Richtung. |
| Karte | Kleine Vektorkarte zur Orientierung (MapLibre mit OpenFreeMap, Aufbau nach [Vela Maps](https://github.com/PimpinPumpkin/Vela)): die Orte als Punkte mit Namen, der eigene Standort mit Gehrichtung, der Kreis, in dem erzählt wird. Folgt dem Standort, bis man sie verschiebt; ein Tipp auf einen Punkt zeigt den Ort mit „Vorlesen“ und „Text lesen“. Für heruntergeladene Städte auch offline, abschaltbar. |
| Mitlesen | Der Text der laufenden Ansage steht in der App; ein Verlauf zeigt alle Ansagen zum Nachlesen (auch ohne installierte Stimme). „Text lesen“ bei jedem Ort zeigt, was erzählt würde, samt allen Teilen von „Mehr erzählen“, ohne es abzuspielen. |
| Länge | Kurz (ein, zwei Sätze), Mittel (die Einleitung, etwa eine halbe Minute) oder Ausführlich (dazu die Höhepunkte aus Geschichte und Architektur). „Mehr erzählen“ liefert weitere Häppchen aus dem Artikel. |
| Offline | Unter „Städte“ eine Stadt oder einen Stadtteil suchen und herunterladen: alle Artikel mit Koordinaten, Kurzbeschreibungen, Einleitungen, auf Wunsch die vollen Artikel, die Karte und alle Straßennamen aus OpenStreetMap. Der Download läuft im Hintergrund weiter, lässt sich anhalten und setzt dort fort, wo er war. |
| Online | Wo keine Stadt heruntergeladen ist, lädt die App die Umgebung unterwegs (Umkreissuche 1,5 km) und behält alles. |
| Sprachen | Deutsch und Englisch, sowohl die Wikipedia und die Ansagen als auch die Oberfläche. |

## Wie aus einem Wikipedia-Artikel eine Ansage wird

Ohne ChatGPT, nur mit Regeln (alles in `app/src/main/java/app/simpleguide/text/`):

1. **Bereinigen** (`WikiText`): Klammern (Aussprache, Lebensdaten, Zweitnamen), Verweise, Listen, Tabellenreste
   und Sätze aus fast nur Zahlen fallen weg.
2. **Auswählen** (`Summarizer`): Die Einleitung eines Artikels ist schon eine Zusammenfassung; sie wird bis zur
   gewünschten Länge gelesen, am liebsten bis zu einem Absatzende. Für „Ausführlich“ und „Mehr erzählen“ kommen
   aus den übrigen Abschnitten die Sätze mit dem meisten Stoff für Zuhörer: Jahreszahlen, Superlative
   („älteste“, „Wahrzeichen“), Bau- und Zerstörungsgeschichte, Legenden. Sätze mit Rückverweis („Dabei …“)
   bleiben mit ihrem Vorgänger zusammen; Maßangaben-Listen werden übersprungen.
3. **Umformulieren** (`SubjectParser`, `Narration`): Aus dem ersten Satz („Der Bertoldsbrunnen ist ein Denkmal …“)
   werden Artikel, Geschlecht und Name bestimmt. Daraus entstehen der Akkusativ („siehst du *den*
   Bertoldsbrunnen“), der Dativ („du stehst *auf dem* Münsterplatz“) und das Pronomen, mit dem der erste Satz
   weitergeht („*Er* ist ein Denkmal …“). Die Vorlagen wechseln, damit ein langer Spaziergang nicht eintönig wird.
4. **Einordnen** (`Classifier`): Die Kurzbeschreibung aus Wikidata („Kirchengebäude in Freiburg“, „Verein zur
   Erhaltung des Münsters“) sagt, was ein Ort ist. Entscheidend ist das Kopfwort vor der ersten Präposition.
5. **Sprechen** (`speech/Normalizer` aus Simple Narrator): Zahlen, Jahreszahlen, Jahrhunderte, Daten und
   Abkürzungen werden ausgeschrieben, dann liest die Stimme.

## Loslegen

1. APK installieren (arm64, Android 8 oder neuer).
2. In der App die vorgeschlagene Stimme laden (Thorsten für Deutsch, 19 MB) oder unter Einstellungen eine andere.
3. Für offline: unter „Städte“ die Stadt suchen und herunterladen (am besten im WLAN).
4. „Tour starten“, Handy einstecken, loslaufen.

## Bauen

- Android Studio (Ladybug oder neuer) oder Kommandozeile mit JDK 17. Der Gradle-Wrapper ist dabei.
- `./gradlew :app:assembleDebug` baut `app.simpleguide.debug` für arm64 und x86_64 (läuft im Emulator).
- `./gradlew :app:assembleRelease` baut die Release-APK für arm64 mit R8. Signiert wird sie, wenn im
  Projektordner eine `keystore.properties` liegt (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`);
  beides ist von git ausgeschlossen.
- `./gradlew :app:testDebugUnitTest` führt die Tests aus. Die Textaufbereitung wird an echten Artikeln
  geprüft (`app/src/test/resources/wiki/`); der Testbericht enthält alle erzeugten Ansagen zum Nachlesen.

## Netz und Datenschutz

Die App spricht nur mit diesen Diensten, und nur wenn „Artikel online nachladen“ an ist oder du eine Stadt lädst:
der Wikipedia-API (Artikel), Nominatim (Stadtsuche, Straßenname unterwegs, höchstens alle 20 Sekunden) und
Overpass (Straßennamen beim Stadt-Download) und OpenFreeMap (Kartenkacheln, solange die Karte angezeigt oder eine Stadt geladen wird). Übertragen wird dabei der ungefähre Standort, sonst nichts – kein
Konto, keine Analyse. Die Stimmen kommen von GitHub (sherpa-onnx-Releases). Gesprochen wird auf dem Gerät.

## Lizenzen

- App: GPL-3.0 (wie Simple Narrator).
- Texte: Wikipedia, CC BY-SA 4.0 – in der App bei jedem Ort mit Link zum Artikel.
- Straßen und Ortssuche: © OpenStreetMap-Mitwirkende, ODbL.
- Karte: MapLibre Native (BSD-2), OpenFreeMap, © OpenMapTiles, Daten © OpenStreetMap-Mitwirkende; Aufbau und Offline-Regionen nach Vela Maps (GPL-3.0).
- Stimmen: sherpa-onnx (Apache 2.0), Piper-Stimmen (je nach Stimme), Kokoro (Apache 2.0), Supertonic (OpenRAIL-M).
