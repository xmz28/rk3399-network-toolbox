# Third-party notices

This application bundles iPerf3 3.21 Android binaries for `arm64-v8a`,
`armeabi-v7a`, `x86`, and `x86_64`.

- iPerf3 project: https://github.com/esnet/iperf
- Android binary builds: https://github.com/davidBar-On/android-iperf3
- Binary download path: `gh-pages/libs/<abi>/iperf3.21`
- iPerf3 license: BSD 3-Clause
- Android binary build repository license: MIT

The bundled binaries are unmodified and are packaged under the filename
`libiperf3.so` so Android extracts them into the app's executable native library
directory.

## Included license texts

- [iPerf3 3.21 complete LICENSE](app/src/main/assets/third_party/iperf-3.21-LICENSE.txt), including upstream component notices: retrieved from the `3.21` tag of esnet/iperf.
- [Android build project LICENSE](app/src/main/assets/third_party/android-iperf3-LICENSE.txt): retrieved from davidBar-On/android-iperf3.

Both texts are included in APK assets/third_party as well as this source repository. The project MIT license does not replace these third-party terms.

## Binary integrity

On 2026-10-12, all four local files were SHA-256 matched against the upstream `gh-pages/libs/<abi>/iperf3.21` files. These hashes identify the bundled artifacts, not a guarantee of runtime compatibility on every device.

| ABI | SHA-256 |
| --- | --- |
| arm64-v8a | 22dc88df3c13bce1e4f0167700d1d3c684ccf642143e9bf480947cd7fb97b060 |
| armeabi-v7a | 4dbc073fa059105e18d61b033dc3c3a7e191553409a4586e4dc47e1dfb9acdce |
| x86 | 0624b6e46dab7934196f6b77b8708972578c316123c5b9c0a15271074b82f19b |
| x86_64 | 3999e812ae20b3dccded5cf1cec5d62476bcf1b1d7cd1f3133848635309edcee |

## Build and client dependencies

Additional dependencies are resolved by Gradle and retain their own licenses:

| Component | Purpose | Upstream |
| --- | --- | --- |
| AndroidX / Compose | Android UI and lifecycle | https://developer.android.com/jetpack/androidx |
| Kotlin | Language and Compose compiler | https://github.com/JetBrains/kotlin |
| Gradle / Android Gradle Plugin | Build tooling | https://github.com/gradle/gradle and https://developer.android.com/build |
| Foojay Toolchain Resolver | JVM toolchain resolution | https://github.com/gradle/foojay-toolchains |
| Apache Commons Net | FTP client | https://commons.apache.org/proper/commons-net/ |
| SSHJ | SFTP client | https://github.com/hierynomus/sshj |
| SMBJ | SMB2/SMB3 client | https://github.com/hierynomus/smbj |
| Bouncy Castle | Cryptography dependency | https://www.bouncycastle.org/ |
| JUnit / AndroidX Test | Tests | https://github.com/junit-team/junit4 and https://github.com/android/android-test |

Versions are specified in app/build.gradle.kts, gradle/libs.versions.toml and wrapper settings. Gradle Wrapper files preserve their existing copyright headers. This repository does not contain third-party Android APKs or RK3399 firmware images.
