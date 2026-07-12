# Rose

**R**eliable **O**pen **S**ource **E**xplorer — a modern, no-compromise file manager for Android.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen)

Rose is built on a simple premise: a file manager shouldn't force you to choose between **functionality** and **design**. Most open-source alternatives nail one and neglect the other. Rose aims to nail both.

---

## Why Rose?

Open-source file managers on Android have a reputation for working well but looking dated, or looking sleek but being poorly maintained. Rose exists to close that gap.

| | Rose | Other FOSS Explorers |
|---|---|---|
| Modern UI (Material 3 / Compose) | ✅ | ❌ Often stuck on legacy Views/XML |
| Active PR review & merging | ✅ | ❌ Many repos are effectively unmaintained |
| Recent Files | ✅ | ❌ Frequently missing |
| Recycle Bin (safe delete) | ✅ | ❌ Frequently missing |
| `Android/data` & `Android/obb` access | ✅ Direct-access-first, SAF fallback | ⚠️ Usually requires Shizuku or root |
| Architecture | MVVM + Hilt + Room | Varies, often outdated |

> Rose isn't trying to out-feature every explorer on day one — it's trying to be the one that's actually pleasant to use *and* actively developed.

---

## Features

- **Clean, modern interface** — built entirely in Jetpack Compose, following Material 3 design principles.
- **Recent Files** — quickly jump back to what you were just working on.
- **Recycle Bin** — deleted files aren't gone forever; recover them before they're permanently purged.
- **Restricted directory access** — reads and writes to `Android/data` and `Android/obb` using a direct-access-first strategy, automatically falling back to the Storage Access Framework (SAF) depending on ROM behavior, with **no Shizuku or root dependency**.
- **Fast, responsive browsing** — optimized file operations to keep large directories snappy.
- **Standard file operations** — copy, move, rename, delete, compress/extract, and more.

More features are actively being added — see [Roadmap](#roadmap).

---

## Tech Stack

- **Language:** [Kotlin](https://kotlinlang.org/)
- **UI:** [Jetpack Compose](https://developer.android.com/jetpack/compose)
- **Architecture:** MVVM
- **Dependency Injection:** [Hilt](https://dagger.dev/hilt/)
- **Local Database:** [Room](https://developer.android.com/training/data-storage/room)
- **Storage Access:** `DocumentFile` / `ContentResolver` for scoped storage compliance, with a direct I/O path for restricted directories where the platform allows it

---

## Screenshots

<!-- Add screenshots here -->
| Home | File Browser | Recycle Bin |
|---|---|---|
| _coming soon_ | _coming soon_ | _coming soon_ |

---

## Installation

> Rose is currently in active development. Pre-built releases will be published on the [Releases](../../releases) page once available.

### Build from source

```bash
git clone https://github.com/NarayanChetri/rose.git
cd rose
./gradlew assembleDebug
```

The debug APK will be generated at `app/build/outputs/apk/debug/`.

**Requirements:**
- Android Studio (latest stable)
- JDK 17+
- Android SDK 24+ (minSdk), targeting the latest stable Android version

---

## Contributing

Contributions are genuinely welcome — this project exists partly *because* other FOSS explorers don't accept them.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit your changes with clear, descriptive messages
4. Open a pull request describing what you changed and why

If you're fixing a bug or adding a feature, feel free to open an issue first to discuss the approach — especially for anything touching storage access, which can behave differently across ROMs.

---

## Roadmap

- [ ] Cloud storage integration
- [ ] Advanced search and filtering
- [ ] Customizable themes
- [ ] Network/SMB share support
- [ ] Archive management improvements
- [ ] Tablet / foldable optimized layouts

---

## License

Rose is licensed under the [GPL-3.0 License](LICENSE).

---

## Package

`dev.narayan.rose`

Built by [Narayan Chetri](https://github.com/NarayanChetri).
