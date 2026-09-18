<div align="left">

# 🌹 ROSE

### **R**eliable **O**pen **S**ource **E**xplorer

A fast, modern, beautiful, and privacy-first Android file manager built with **Kotlin** and **Jetpack Compose**.

[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?style=flat-square\&logo=android\&logoColor=white)](https://github.com/NarayanChetri/ROSE/releases/latest)
[![Latest Release](https://img.shields.io/github/v/release/NarayanChetri/ROSE?style=flat-square\&color=blue\&logo=github)](https://github.com/NarayanChetri/ROSE/releases/latest)
[![Total Downloads](https://img.shields.io/github/downloads/NarayanChetri/ROSE/total?style=flat-square\&color=brightgreen\&logo=github)](https://github.com/NarayanChetri/ROSE/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)](LICENSE)
[![No Ads](https://img.shields.io/badge/Ads-Zero%20%2F%20Clean-success?style=flat-square)](#-features)
[![Translation status](https://hosted.weblate.org/widgets/rose/-/app/svg-badge.svg)](https://hosted.weblate.org/engage/rose/)

---

### 📥 Download ROSE

<a href="https://github.com/NarayanChetri/ROSE/releases/latest">
  <img src="https://img.shields.io/badge/⚡_DOWNLOAD_LATEST_APK-v1.2.4_(FREE)-00C853?style=for-the-badge&logo=android&logoColor=white&labelColor=1a1f2c" alt="Download Latest APK" height="50">
</a>

  

<a href="https://github.com/NarayanChetri/ROSE/releases">
  <img src="https://img.shields.io/badge/📦_ALL_RELEASES-GitHub-238636?style=for-the-badge&logo=github&logoColor=white&labelColor=24292f" alt="All Releases" height="50">
</a>

</div>

---

## ✨ Features

* 🗜️ **Powerful Archive Support** — Browse and extract ZIP, RAR, 7z, TAR, GZ, ISO, APK, XAPK, APKS, and more.
* 🔐 **Encrypted Archives** — Create and extract password-protected ZIP archives with AES-256.
* 📁 **Complete File Management** — Copy, move, rename, delete, share, and open files with any app.
* 🔎 **Fast Search** — Quickly search files by name across directories.
* ♻️ **Recycle Bin** — Restore deleted files or permanently remove them.
* 🛡️ **Shizuku Support** — Access restricted `Android/data` and `Android/obb` directories without root.
* 💾 **USB OTG & SD Cards** — Browse and manage files across internal, SD card, and USB storage.
* 📄 **Markdown Reader** — Read `.md` files directly inside ROSE.
* ⚡ **Background Operations** — Run large copy, move, compress, and extract operations with live progress.
* 🎨 **Material 3 UI** — Modern interface with dynamic colors, dark mode, and smooth animations.
* 🔒 **Privacy First** — No ads, no tracking, no analytics, and fully open source.
* 🔄 **Media Rescan** — Refresh MediaStore and scan WhatsApp media when needed.
* 🚫 **Folder Exclusion** — Exclude selected folders from media scanning.

---

## 📸 Screenshots

<p align="center">
<img width="1448" height="1086" alt="ROSE Screenshot" src="https://github.com/user-attachments/assets/da89f0d8-dc08-432a-99bf-c0f69f81340e" />
</p>

---

## 📋 Requirements & Permissions

### Minimum Requirements

* **Android:** 8.0 (Oreo) or higher
* **Storage:** All Files Access on Android 11+
* **Shizuku:** Optional, required only for accessing restricted `Android/data` and `Android/obb` directories

### Permissions Explained

| Permission                                       | Why ROSE Needs It                                               |
| :----------------------------------------------- | :-------------------------------------------------------------- |
| **All Files Access (`MANAGE_EXTERNAL_STORAGE`)** | Required to read, write, copy, move, and organize files.        |
| **Shizuku**                                      | Provides access to restricted system directories without root.  |
| **Foreground Service**                           | Keeps large file operations running reliably in the background. |

---

## 🛠 Tech Stack

* **Language:** [Kotlin](https://kotlinlang.org/)
* **UI:** [Jetpack Compose](https://developer.android.com/jetpack/compose) + [Material 3](https://m3.material.io/)
* **Archive Engine:** Libarchive (`me.zhanghai.android.libarchive`)
* **System Access:** [Shizuku API](https://shizuku.rikka.app/)
* **Image Loading:** [Coil](https://coil-kt.github.io/coil/)
* **Async:** Kotlin Coroutines & Flow

---

## 🚀 Installation

1. Download the latest `.apk` from **[GitHub Releases](https://github.com/NarayanChetri/ROSE/releases/latest)**.
2. Open the downloaded APK on your Android device.
3. Follow the on-screen instructions to install ROSE.

### Play Protect Warning

Since ROSE is distributed directly through GitHub rather than Google Play, Android may show an **"Unrecognized app"** or **"Unknown publisher"** warning.

If this happens:

1. Tap **More details** or **Advanced**.
2. Tap **Install anyway**.

---

## 🤝 Contributing

Contributions are welcome! You can help by fixing bugs, improving performance, adding features, or improving documentation.

1. **Fork** the repository.
2. Create a feature branch:

   ```bash
   git checkout -b feature/amazing-feature
   ```
3. Commit your changes:

   ```bash
   git commit -m "feat: add amazing feature"
   ```
4. Push your branch:

   ```bash
   git push origin feature/amazing-feature
   ```
5. Open a **Pull Request**.

### 🌐 Translations

Help translate ROSE into your native language through Weblate:

[![Translation status](https://hosted.weblate.org/widgets/rose/-/app/multi-auto.svg)](https://hosted.weblate.org/engage/rose/)

👉 **[Translate ROSE on Hosted Weblate](https://hosted.weblate.org/engage/rose/)**

For offline or Git-based translation instructions, see the **[Translation Guide](TRANSLATING.md)**.

---

## 📄 License

ROSE is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for more information.

---

## ⭐ Support the Project

If you find ROSE useful, consider giving the repository a **⭐ Star**. It helps others discover the project and supports continued development.

<p align="center">
  Built with ❤️ using Kotlin, Jetpack Compose, and open-source love.
</p>
