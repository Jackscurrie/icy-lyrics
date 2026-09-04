# Android date-format reference and iOS adapter

The original library screen calls `java.text.DateFormat.getDateTimeInstance(MEDIUM, SHORT)` at frozen `IcyLyricsApp.kt:2120`. Before this adapter, iOS called Foundation's medium/short styles. Those styles do not produce the same text: the captured iOS en-US library timestamp is `Sep 3, 2026 at 5:00\u202fAM`; the actual Android string is `Sep 3, 2026 5:00 AM`.

## Measured reference

`evidence/android36-date-formats.zip` preserves the raw Android output, instrumentation log, APK/source hashes, and exact probe sources. The adjacent JSON records its archive SHA-256. The opt-in probe passed on API 36 / ICU 76.1 / CLDR 46 in 4.262 seconds. It inspected all 881 locales returned by Android's Java API and compared 144 strings from the original no-locale production overload against the explicitly selected locale formatter. Process locale/timezone defaults were restored; no UI, OS locale, or original production files were changed.

The samples cover 18 locales, America/Los_Angeles and UTC, September 3, winter midnight/noon, and the spring DST day. The March instant is 2026-03-08T22:00Z, **not** the exact DST transition (the archived probe comment is imprecise). These are observed strings for epoch `1788436800000` in America/Los_Angeles:

| Locale | Android string | Significant detail |
| --- | --- | --- |
| en-US | `Sep 3, 2026 5:00 AM` | ASCII spaces, `AM` |
| en-CA | `Sep 3, 2026 5:00\u202fa.m.` | Narrow no-break space and `a.m.` are required |
| en-GB | `3 Sept 2026 05:00` | `Sept`, day first, 24-hour time |
| fr-CA | `3 sept. 2026 05 h 00` | Literal `h` between hour and minute |
| fr-FR | `3 sept. 2026 05:00` | Abbreviated month punctuation |
| de-DE | `03.09.2026 05:00` | Numeric month and padded day |
| es-ES | `3 sept 2026 5:00` | Unpadded 24-hour field |
| pt-BR | `3 de set. de 2026 05:00` | Two literal `de` fields |
| ru-RU | `3 сент. 2026\u202fг. 05:00` | Narrow no-break space before year suffix |
| ar-EG | `٠٣\u200f/٠٩\u200f/٢٠٢٦ ٥:٠٠ ص` | Arabic digits and explicit right-to-left marks |
| fa-IR | `۳ سپتامبر ۲۰۲۶ ۵:۰۰` | Persian digits; Gregorian year/month |
| hi-IN | `3 सित॰ 2026 5:00 am` | Latin digits and lower-case day period |
| bn-BD | `৩ সেপ, ২০২৬ ৫:০০ AM` | Bengali digits with Latin day period |
| th-TH | `3 ก.ย. 2026 05:00` | Gregorian year, not Buddhist year |
| ja-JP | `2026/09/03 5:00` | Numeric date and unpadded hour |
| zh-CN | `2026年9月3日 05:00` | Literal year/month/day characters |
| zh-TW | `2026年9月3日 上午5:00` | Day period immediately before hour |
| ko-KR | `2026. 9. 3. 오전 5:00` | Local day period and date punctuation |

Escapes above expose exact Unicode characters; raw JSON contains the actual text and every code point.

Across all 881 available locale profiles:

- Every combined pattern equals the separately reported medium date pattern, one ASCII space, then the short time pattern. There are 105 distinct combined patterns.
- Every Java formatter reports the Gregorian calendar.
- The only unquoted pattern fields are `y`, `M`, `d`, `H`, `h`, `m`, and `a`. No era, weekday, stand-alone-month, or flexible day-period fields occur in this inventory.
- Nine zero-digit sets occur: Latin, Arabic-Indic, extended Arabic-Indic, Devanagari, Bengali, Ol Chiki, Tibetan, Myanmar, and N'Ko. Native locale defaults cannot safely be assumed to choose the same digits.

## Narrow adapter

The Android formatter remains unchanged. `IosAndroidDateFormatter` uses the captured Android profile data only for iOS `formatDateTime`. Foundation provides the instant's Gregorian year/month/day/hour/minute in the selected timezone; `AndroidDateProfiles` supplies the captured pattern, month/day-period strings, quoted literals, padding, and digits. This avoids dependence on Foundation's date/time join words, newer locale symbol data, calendar preferences, or 12/24-hour preference rewriting.

The renderer needs seven measured field types and Java's quoted-literal escaping. `h` maps zero hours to 12; `a` indexes the measured AM/PM array; `MMM` uses the measured abbreviated Gregorian month; numeric fields use the profile's zero digit. Preserve all literals and Unicode spacing exactly. Unsupported pattern fields must fail the profile-generation check, not silently use Apple formatting.

An alternative using an explicit `NSDateFormatter.dateFormat`, `shortMonthSymbols`, AM/PM symbols, Gregorian calendar, and locale may be smaller, but it still needs proof that numbering systems and user hour-cycle settings cannot alter the supplied pattern. Merely concatenating two Foundation style-format strings fixes the join word but does not fix `AM` versus `a.m.`, `Sep` versus `Sept`, digits, or Gregorian versus Buddhist/Persian defaults. Blindly removing `at` or narrow spaces is incorrect for measured locales.

Locale selection uses exact measured language/script/region tags, normalized from Foundation identifiers. Available Android Chinese identifiers include `zh-Hans-CN`/`zh-Hant-TW`, while the no-locale sample requests `zh-CN`/`zh-TW`; the two aliases are explicitly mapped and verified by the measured samples. Unknown regions fall back to the measured language profile; unknown languages fall back to `en`. Those fallbacks remain parity limitations until measured. The 881-profile inventory does not claim coverage of arbitrary Unicode locale extensions, custom calendar/numbering overrides, all Android releases, pre-Gregorian dates, or historical timezone database differences.

`generate_android_date_profiles.py` derives the compact 75,370-byte production asset and the 144-case test fixture from the hash-verified archive, with explicit UTF-8/LF output on both build hosts. The complete ICU 76.1 license accompanies the bundled data. JVM tests compare all 144 measured expected strings using independently obtained timezone fields and check region/alias handling and rejection of unknown pattern fields. The native test invokes the production adapter for the same 144 cases and writes every actual/expected string to `native-date-format/report.json` before asserting equality. Native execution and a new library screenshot remain pending. The renderer does not consult a user hour-cycle preference; those preferences cannot rewrite its supplied pattern. Text equality remains distinct from font shaping/pixel parity. Both deterministic and UIKit paths call the same production adapter; deterministic captures retain the actual formatted-date metadata.

## Primary API references

- [Android SimpleDateFormat patterns and quoting](https://developer.android.com/reference/java/text/SimpleDateFormat)
- [Foundation dateFormat](https://developer.apple.com/documentation/foundation/dateformatter/dateformat)
- [Foundation shortMonthSymbols](https://developer.apple.com/documentation/foundation/dateformatter/shortmonthsymbols)
- [Apple QA1480: locale, calendar, and user preference effects on formatters](https://developer.apple.com/library/archive/qa/qa1480/_index.html)

The adapter is implemented; native runtime and screenshot verification remain required before claiming cross-platform parity.

Local validation: all three targeted JVM tests passed (144 exact measured strings, locale selection, and invalid-pattern rejection). The complete iOS simulator main/test KLIB compiled successfully with the date adapter and native test. Android's explicit JSON compile dependency resolves to the same `kotlinx-serialization-json:1.9.0` already selected through the existing core modules; no rendering dependency version changed.
