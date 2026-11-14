# Kartičky

An Android flashcards app for learning Slovak vocabulary. Everything stays on the phone: no account, no internet permission, no cloud sync.

## Features

- **Import from Flashcards Maker.** Opens its `CardsBackup.zip` backups and keeps each card's learning level. Importing the same backup again doesn't create duplicates.
- **Spaced repetition.** Eight levels with intervals of 1, 2, 4, 8, 16, 32 and 64 days, and four grades: Forgot, Hard, Good, Easy. A forgotten card drops two levels and comes back in the same session.
- **Study today.** One button runs all due reviews first, then new words up to a daily limit (10 by default), so new words arrive in portions instead of all at once.
- **Nine game modes:** flashcards, quiz, typing, listening, dictation, pairs, true/false, build the word, and noun gender (ten / tá / to).
- **Built-in keyboard for Typing and Dictation** (Slovak with all diacritics, Ukrainian, Russian, English), so the system keyboard's autocorrect and word suggestions can't give the answer away. The system keyboard is still one tap away. A missing diacritic or a single typo counts as "almost".
- **Pronunciation** through the system text-to-speech engine. It works offline once a Slovak voice is installed.
- **Statistics:** daily streak, answers over 30 days, accuracy, a review forecast for the week, and the words you forget most often.
- **Daily reminder,** sent only when there is something to review or the daily goal isn't reached yet.
- **Card management:** topics (tags) with bulk tagging, hard-word tracking, and warnings when you add a word or translation that already exists.
- **Backups:** export and import as JSON, with the full answer history, so streaks and statistics survive moving to a new phone. A weekly auto-backup also goes to `Downloads/Karticky` (Android 10+).
- **Interface** in English, Ukrainian, Russian and Slovak, plus light, dark and system themes. Both can be changed in the app without a restart.

## Requirements

- Android 8.0 (API 26) or newer.
- Building needs JDK 17 or newer and the Android SDK with platform 36.

## Build and install

```bash
# create local.properties with the path to your Android SDK
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key, so it can be installed directly. Use a proper signing key before publishing anywhere.

## Getting started

1. Export a backup from Flashcards Maker (`CardsBackup.zip`) and copy it to the phone.
2. In Kartičky, open **⋮ → Import backup** and choose the file.
3. Open the deck and press **Study today**.

Optional settings (⚙): language, theme, new words per day, the daily goal for the streak, the reminder time, and auto-backup.

## Backups

| Format | Import | Export | What's inside |
|---|---|---|---|
| Flashcards Maker `.zip` | ✓ | — | decks and cards with their levels and review dates |
| Kartičky `.json` | ✓ | ✓ | decks, cards, progress, topics, genders and the full answer history |

Imports merge by ID: existing cards get the imported text, and the higher of the two progress values is kept.

App settings are not part of the backup.

## Project structure

```
app/src/main/java/com/vlatal/flashcards/
├── MainActivity.kt        navigation, theme and system bars
├── Reminders.kt           daily reminder (alarm, notification, boot receiver)
├── data/
│   ├── Models.kt          cards, decks, grades, spaced-repetition scheduling (Srs)
│   ├── Repository.kt      SQLite storage, answer log, statistics, merge import
│   ├── BackupIO.kt        Flashcards Maker import, JSON import and export
│   ├── AutoBackup.kt      weekly backup to Downloads via MediaStore
│   ├── Duplicates.kt      duplicate and similar-word detection
│   ├── TextUtil.kt        answer checking and the text-to-speech wrapper
│   ├── Settings.kt        app settings
│   └── Locales.kt         in-app language
└── ui/                    Jetpack Compose screens and game modes
```

Translations live in `app/src/main/res/values*/strings.xml` (default English, plus `-uk`, `-ru` and `-sk`).

## Tests

Unit tests (JVM) cover answer checking, scheduling and duplicate detection:

```bash
./gradlew testDebugUnitTest
```

The instrumented test `BackupRoundTripTest` runs on a device. It checks that export followed by import keeps the streak, statistics and card progress. It uses its own database files, so the app's data is left alone.

> **Warning:** don't run it with `./gradlew connectedAndroidTest`. That task uninstalls the app afterwards, which deletes all of its data. Install and run it manually instead:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.vlatal.flashcards.test/androidx.test.runner.AndroidJUnitRunner
```

## Tech stack

Kotlin 2.2, Jetpack Compose with Material 3, Android SQLite (no ORM), AGP 8.13 and Gradle 9.3.
