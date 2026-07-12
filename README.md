# Rose

**R**eliable **O**pen **S**ource **E**xplorer — a modern, no-compromise file manager for Android.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen)

A file manager that doesn't make you choose between good design and real functionality — built to be both genuinely useful and genuinely pleasant to use.

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

Built by [Narayan Chetri](https://github.com/NarayanChetri).# Rose

**R**eliable **O**pen **S**ource **E**xplorer — a modern, no-compromise file manager for Android.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen)

A file manager that doesn't make you choose between good design and real functionality — built to be both genuinely useful and genuinely pleasant to use.

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

<p align="center">
 <img width="1272" height="2516" alt="IMG_20260712_202142" src="https://github.com/user-attachments/assets/5ab35466-b8d9-47ea-9315-c35be716ca77" />
<img width="1272" height="2432" alt="IMG_20260712_202131" src="https://github.com/user-attachments/assets/f363e73b-6666-431e-ad69-3e6023a198f7" />
<img width="1272" height="2476" alt="IMG_20260712_202122" src="https://github.com/user-attachments/assets/82ccd8b9-5547-468e-951d-bcf6ee9f0469" />
<img width="1256" height="2468" alt="IMG_20260712_202111" src="https://github.com/user-attachments/assets/dfdc928a-8516-429f-a7f5-ce19ec19f1bf" />
<img width="1272" height="2482" alt="IMG_20260712_202101" src="https://github.com/user-attachments/assets/90be0540-deda-4925-aa44-36d40720e4f7" />
<img width="1272" height="2335" alt="Screenshot_2026-07-12-20-22-09-57_363e1672d8efa930ebbf90bb4f40c25c" src="https://github.com/user-attachments/assets/0ac1e858-6fe2-409b-8d05-b047876d57c1" />

</p>

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
