# External asset licenses and provenance

The files described by `config/assets-manifest.json` are downloaded build inputs. They are
not relicensed by this repository. The manifest pins the exact byte length and SHA-256 digest
used by the build; this document records the applicable upstream license family.

| Asset family | Upstream/source | License or terms |
| --- | --- | --- |
| Android SDK command-line payloads | [Android Developers](https://developer.android.com/studio) and the Code on the Go asset pipeline | Android SDK License Agreement and the licenses shipped inside the SDK |
| Bootstrap and local Maven payloads | [Code on the Go](https://github.com/appdevforall/CodeOnTheGo) asset pipeline and their individual upstream projects | Mixed upstream licenses; preserve the license files bundled in each payload |
| Offline documentation database | [OfflineDocumentationTools](https://github.com/appdevforall/OfflineDocumentationTools) and the indexed upstream documentation projects | Mixed upstream documentation licenses; preserve attribution stored with the database |
| Gradle distribution and generated API archive | [Gradle](https://github.com/gradle/gradle) | Apache-2.0, except for separately identified third-party components bundled by Gradle |
| Code on the Go core tools | [Code on the Go](https://github.com/appdevforall/CodeOnTheGo) | GPL-3.0-only and any notices bundled in the payload |
| JDI support | [oj-libjdwp](https://github.com/appdevforall/oj-libjdwp) / OpenJDK-derived code | GPL-2.0 with the Classpath Exception |
| Kotlin analysis API | [kotlin-android](https://github.com/appdevforall/kotlin-android) / Kotlin | Apache-2.0 |

`LicenseRef-Mixed-Upstream` and `LicenseRef-Android-SDK` deliberately do not claim that a
multi-project archive has one SPDX license. Before adding or replacing such an asset, the
maintainer must inspect the archive's bundled notices and update this table when its contents
or terms change.
