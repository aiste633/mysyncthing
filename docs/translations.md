# Translations

The app's text lives in Android string resources, ready for translation. English is the
source language (declared as en-GB; the text is not rewritten in British spelling). Italian
is the first translation. The files are standard Android XML, so a translation platform
such as Weblate can work on them directly; none is set up yet.

## Files

| File | What |
|---|---|
| `app/src/main/res/values/strings.xml` | The screens, dialogs and messages of the app |
| `core/src/main/res/values/strings.xml` | Sync status, notifications, notification channels and the home-screen widget |
| `fastlane/metadata/android/en-US/` | F-Droid store text (title, descriptions, changelogs) |

A translation goes in `values-<language>/strings.xml` next to each file (for example
`values-it/strings.xml`), with the same names. A string missing there falls back to English.
The store text stays in the `en-US` folder, F-Droid's default; a translation goes in a
folder of its own, such as `fastlane/metadata/android/it/`.

As Weblate components: one per `strings.xml` (format "Android String Resource", base file
`values/strings.xml`, file mask `values-*/strings.xml`), and one for the fastlane folder
(format "Fastlane metadata").

## For translators

- Each string with placeholders has a comment saying what they hold. Keep `%1$s`, `%2$d`
  and their numbers; the order in the sentence may change.
- Plurals (`<plurals>`) need the forms your language uses. Where English leaves out the
  number in "one" ("Every day"), keep `%1$d` if "one" also covers other numbers in your
  language (such as 21).
- Units are SI symbols and are not translated: kB, MB/s, %, d, h, min, s. They follow the
  number after a non-breaking space (` `).
- Names stay as they are: Syncthing, Syncthing-Fork, Tor, Orbot, SOCKS5, HTTP, QUIC,
  AES-256, Wi-Fi, and licence names. Strings marked `translatable="false"` are not offered.
- Follow the terms of upstream Syncthing's translation for the same language (folder types,
  versioning, ignore patterns, introducer, and so on), so the app and the web GUI agree.
  The usage-reporting question uses upstream's wording; take upstream's translation of it.
- Dates, times and numbers are not in the strings: the app writes them as the phone's
  language does (`3 ott 2025, 18:42`, `1,5 MB`), with the hour per the phone's 12/24 h
  setting. Only words around them ("yesterday, %1$s") are translated.
- In the XML, an apostrophe is written `\'` and a double quote `\"`.
- Messages from the Syncthing core itself (errors about files, connections) come in English
  and are shown as they come, except the few the app maps to its own words.

## For developers

- No text for people in Kotlin. Composables use `stringResource` and `pluralStringResource`;
  logic returns a `UiText` (`core/.../UiText.kt`): a resource ID with its arguments, resolved
  where it is shown (`asString()` in Compose, `resolve(context)` elsewhere). Names, paths and
  the core's messages go in `raw(...)`.
- An error to show from deep code: `throw UiTextException(text(R.string.x))`; the caller shows
  `e.messageText()`.
- Each string with placeholders gets a comment for translators. Numbers of things use
  `<plurals>`; counts go in as `num(n)` and the string takes them as `%1$s`.
- Numbers, dates and times are `UiText` too, formatted when resolved: `num(n)` or
  `UiText.Num(v, decimals)` with the language's separators, and `time(value, skeleton)` with
  an ICU skeleton (`dMMMyjm`), where `j` becomes the hour per the phone's 12/24 h setting.
  Dates and numbers follow the phone's region (en-US writes month first). Logs, exports and file names stay ISO 8601;
  units stay SI. A number field shows the language's decimal separator without grouping
  (`fieldNumber`) and accepts both `.` and `,`.
- Log, exception and `toString()` texts stay English in code.
- Unit tests run on the JVM without Android: `XmlStrings` reads the English text from
  `res/values`, so a test asserts `someText(...).en()` against English. `StringsTest` checks
  that the files parse as aapt2 reads them and that placeholders are consistent.
- CI job `strings` runs `scripts/check-strings.py`. It fails on a string literal in the
  main Kotlin sources that reads like words; a literal that must stay (a directory name, a
  protocol word, a core message matched by the app) goes on its allowlist with the reason.
  It also fails when a use of a string resource passes fewer or more arguments than the
  string has placeholders (a missing one crashes at run time), or text where it expects a
  number (`%d`). And it fails when a `UiText` value goes into a string template, a `+` with
  a string or a formatting call without being resolved (it would show the data class).
- `XmlStrings.with(is24Hour = false)` and `.with(locale = Locale.ITALY)` test the 12 h
  clock and another language's dates and numbers on the JVM. Dates come from the JDK's CLDR
  data, which gives the same text as Android 16 (a 12 h time has U+202F before "pm").
