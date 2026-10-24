# Third-party notices

This project uses third-party software, including:

| Component | Project / license information |
| --- | --- |
| Telegram TDLib | https://github.com/tdlib/td — Boost Software License 1.0 |
| TDLib Android community packaging 0.1.1 | io.github.tdlib-android:core; Maven POM declares Boost Software License 1.0; provenance limitations are recorded in docs/SECURITY.md |
| AndroidX / Jetpack Compose | https://android.googlesource.com/platform/frameworks/support/ — Apache License 2.0 |
| Kotlin / kotlinx.coroutines / kotlinx.serialization | https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines and https://github.com/Kotlin/kotlinx.serialization — Apache License 2.0 |
| Tink Android 1.18.0 | https://github.com/tink-crypto/tink-java — Apache License 2.0 |
| OkHttp 4.12.0 | https://github.com/square/okhttp — Apache License 2.0 |
| Google ML Kit text recognition 16.0.1 | https://developers.google.com/ml-kit/terms — Google ML Kit SDK/model terms and SDK privacy conditions apply; OCR runs on device |

AndroidX includes Compose/Material, WorkManager, Media3, Biometric, DocumentFile, Fragment and ExifInterface. The optional sharing service uses Node.js built-ins and has no npm dependencies; Node.js has its own runtime license inventory.

Native TDLib builds also incorporate dependencies such as OpenSSL; their exact versions, license texts and distribution obligations must be inventoried against the selected binary before public production distribution. This list is a development inventory, not a completed legal audit or an assertion that the community binary is reproducible.
