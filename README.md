<img src="docs/logo/logo.png" alt="Passing By logo" width="96" align="right">

# Passing By

*Stories of the places you pass by.* Passing By is an audio guide for walking through town. Phone in your pocket, headphones on, music playing –
just walk. Whenever you pass something that has a Wikipedia article, the app tells you where it is and what it
is about: “On your right is St Paul's Cathedral. It is an Anglican cathedral in London …”. Your music pauses
for a moment and continues by itself afterwards.

The voice runs entirely on the phone (Piper, Kokoro, Supertonic via sherpa-onnx). The texts come from Wikipedia,
loaded on the way or downloaded for whole cities in advance. Stories are put together without a language model:
with rules, templates and what the articles say.

Passing By (formerly “Simple Guide”) is a fork of [Simple Narrator](https://github.com/regard2462/SimpleNarrator) and reuses its speech
output and text preparation (numbers, dates, abbreviations). Everything else is deliberately left out – no map,
no navigation, no account.

[Deutsche Fassung](README.de.md)

## Features

| Area | Function |
|---|---|
| Tour | Runs as a foreground service with location and keeps going with the screen off. The walking direction comes from movement (not the compass – the phone is in your pocket) and becomes “right”, “left”, “ahead”, “behind”. |
| Interrupting | Before each story the app takes transient audio focus: music apps pause (or duck, configurable) and resume afterwards. A soft chime announces the story. |
| Stopping | **Pause on the headphones or the phone** ends the current story. Only this place is skipped; the tour goes on. “Replay” in the notification or the app brings it back, “Tell me more” continues. Restarting your music yourself also ends the story. |
| What is told | Places within a radius (base radius configurable; big and famous places like squares, parks and churches from further away). The most significant one wins. What is behind you is over; heard places are not repeated (configurable). Kinds of places can be switched off; companies, clubs, stops and artworks inside buildings are off by default. |
| Where am I | Street name before the next story (“You are now on Gerberau.”), optionally on every new street. If the street has its own article, it is told as soon as you walk on it. “What's here?” names the street and the most significant places around with their direction. |
| Map | Small vector map for orientation (MapLibre with OpenFreeMap, modelled on [Vela Maps](https://github.com/PimpinPumpkin/Vela)): places as dots with names, your position with walking direction, the circle in which stories are told. Follows you until you pan it; tapping a dot shows the place with “Read aloud” and “Read the text”. Offline for downloaded cities, can be switched off. |
| Reading along | The text of the current story is shown in the app; a history keeps all stories for reading (also without an installed voice). “Read the text” on any place shows what would be told, including all parts of “Tell me more”, without playing it. |
| Length | Short (one or two sentences), medium (the introduction, about half a minute) or detailed (plus highlights from history and architecture). “Tell me more” delivers further parts of the article. |
| Offline | Under “Cities”, search a city or district and download it: all articles with coordinates, short descriptions, introductions, optionally the full articles, the map and all street names from OpenStreetMap. The download runs in the background, can be paused and resumes where it stopped. |
| Online | Where no city is downloaded, the app loads the surroundings on the way (radius search 1.5 km) and keeps everything. |
| Languages | German and English, for Wikipedia and the stories as well as the interface. |

## From a Wikipedia article to a story

No ChatGPT, only rules (all in `app/src/main/java/app/simpleguide/text/`):

1. **Clean** (`WikiText`): parentheses (pronunciation, life dates, alternative names), references, lists, table
   remains and sentences made almost only of numbers are removed.
2. **Select** (`Summarizer`): the introduction of an article already is a summary; it is read up to the chosen
   length, preferably to the end of a paragraph. For “detailed” and “Tell me more”, the remaining sections supply
   the sentences with the most material for listeners: years, superlatives (“oldest”, “landmark”), construction
   and destruction, legends. Sentences that refer back (“It …”) stay with their predecessor; lists of
   measurements are skipped.
3. **Rephrase** (`SubjectParser`, `Narration`): from the first sentence the subject is determined and replaced by
   a pronoun (“It is an Anglican cathedral …”); in German also article, gender, accusative and dative. Templates
   vary so a long walk does not sound monotonous.
4. **Classify** (`Classifier`): the Wikidata short description (“church building in Freiburg”, “association for
   the preservation of the minster”) tells what a place is; the head noun before the first preposition decides.
5. **Speak** (`speech/Normalizer` from Simple Narrator): numbers, years, centuries, dates and abbreviations are
   written out, then the voice reads.

## Getting started

1. Install the APK (arm64, Android 8 or newer).
2. Download the suggested voice in the app (Kokoro for English, Thorsten for German) or another one in Settings.
3. For offline use: search and download the city under “Cities” (Wi-Fi recommended).
4. “Start tour”, pocket the phone, walk.

## Building

- Android Studio (Ladybug or newer) or the command line with JDK 17. The Gradle wrapper is included.
- `./gradlew :app:assembleDebug` builds `app.simpleguide.debug` for arm64 and x86_64 (runs in the emulator).
- `./gradlew :app:assembleRelease` builds the release APK for arm64 with R8. It is signed when the project
  folder contains a `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); both are
  excluded from git.
- `./gradlew :app:testDebugUnitTest` runs the tests. Text preparation is checked against real articles
  (`app/src/test/resources/wiki/`); the test report contains all generated stories for reading.

## Network and privacy

The app only talks to these services, and only when “Load articles online” is on or you download a city: the
Wikipedia API (articles), Nominatim (city search, street name on the way, at most every 20 seconds) and Overpass
(street names for city downloads) and OpenFreeMap (map tiles while the map is shown or a city is downloaded). What is sent is the approximate location, nothing else – no account, no
analytics. Voices come from GitHub (sherpa-onnx releases). Speech is synthesised on the device.

## Licences

- App: GPL-3.0 (like Simple Narrator).
- Texts: Wikipedia, CC BY-SA 4.0 – in the app every place links to its article.
- Streets and place search: © OpenStreetMap contributors, ODbL.
- Map: MapLibre Native (BSD-2), OpenFreeMap, © OpenMapTiles, data © OpenStreetMap contributors; structure and offline regions follow Vela Maps (GPL-3.0).
- Voices: sherpa-onnx (Apache 2.0), Piper voices (per voice), Kokoro (Apache 2.0), Supertonic (OpenRAIL-M).
