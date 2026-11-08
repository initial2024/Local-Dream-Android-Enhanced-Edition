# Build, fixed signing and model migration

## Signing boundary

An Android package update requires the same application ID and signing lineage. The upstream author's private signing key is unavailable. We deliberately use `io.github.initial2024.localdream.enhanced` to install alongside `io.github.xororz.localdream`.

**Never put a JKS, passwords, SDK archive, model weights or other secrets into the public repository.** Maintain one offline backup of the enhanced keystore: if lost, the same signature cannot be recreated.

The app uses upstream Gradle signing properties:

```text
RELEASE_STORE_FILE=/absolute/path/to/release-keystore.jks
RELEASE_STORE_PASSWORD=<from private keystore record>
RELEASE_KEY_ALIAS=localdream-enhanced
RELEASE_KEY_PASSWORD=<from private keystore record>
```

For GitHub Actions, the owner must set protected secrets `ANDROID_SIGNING_KEY_B64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. A CI release job must decode the keystore into the private runner workspace, verify certificate SHA-256 against the archived fingerprint, sign only the `basicRelease` artifact and verify via `apksigner verify --print-certs`. An arbitrary fresh CI debug keystore is **not** an acceptable stable release signature.

## Native prerequisites and CI stop conditions

Upstream relies on git submodules under `app/src/main/cpp/3rdparty` and Qualcomm AI Runtime SDK 2.51.0.260929. Respect the license for each dependency. A Gradle build is not verifiably ready until the required native toolchain is accessible, compileSdk 37 is provisioned and an arm64-v8a APK is assembled and signed. Never publish a release from a failed or skipped native build.

## Existing v2.8.1 models

- Leave upstream v2.8.1 installed; no automatic data clearing, package replacement or signature spoofing.
- Where the existing app stored models under public `Download/LocalDream`, allow read/reuse after explicit Android storage permission and a manifest/format check.
- Data under private `/data/user/0/io.github.xororz.localdream/` cannot be read by this differently signed app through ordinary Android sandbox APIs.
- If existing assets are private and cannot be exported, the user accepted re-downloading compatible model weights.
- Do not download or load Qualcomm-specific Hexagon binaries in an attempt to accelerate Dimensity 9400+. Select only supported CPU/GPU paths, respecting model architecture and memory.
