<div align="left">

# 🌹 ROSE

### **R**eliable **O**pen **S**ource **E**xplorer

A fast, modern, beautiful, and privacy-first Android file manager built with **Kotlin** and **Jetpack Compose** — fully vibe-coded, but professionally engineered.

[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?style=flat-square\&logo=android\&logoColor=white)](https://github.com/NarayanChetri/ROSE/releases/latest)
[![Latest Release](https://img.shields.io/github/v/release/NarayanChetri/ROSE?style=flat-square\&color=blue\&logo=github)](https://github.com/NarayanChetri/ROSE/releases/latest)
[![Total Downloads](https://img.shields.io/github/downloads/NarayanChetri/ROSE/total?style=flat-square\&color=brightgreen\&logo=github)](https://github.com/NarayanChetri/ROSE/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)](LICENSE)
[![No Ads](https://img.shields.io/badge/Ads-Zero%20%2F%20Clean-success?style=flat-square)](#-features)
[![Vibe Coded](https://img.shields.io/badge/Vibe%20Coded-Professionally%20Engineered-7C4DFF?style=flat-square)](#)
[![Translation status](https://hosted.weblate.org/widgets/rose/-/app/svg-badge.svg)](https://hosted.weblate.org/engage/rose/)

---

### 📥 Download ROSE

<a href="https://github.com/NarayanChetri/ROSE/releases/latest">
  <img src="https://img.shields.io/badge/⚡_DOWNLOAD_LATEST_APK-v2.1_(FREE)-00C853?style=for-the-badge&logo=android&logoColor=white&labelColor=1a1f2c" alt="Download Latest APK" height="50">
</a>

  

<a href="https://github.com/NarayanChetri/ROSE/releases">
  <img src="https://img.shields.io/badge/📦_ALL_RELEASES-GitHub-238636?style=for-the-badge&logo=github&logoColor=white&labelColor=24292f" alt="All Releases" height="50">
</a>

</div>


## 📸 Screenshots

<p align="center">
<img width="1448" height="1086" alt="ROSE Screenshot" src="https://github.com/user-attachments/assets/da89f0d8-dc08-432a-99bf-c0f69f81340e" />
</p>

---

---

## ✨ Features

* ⚡ **Root Support** — Full superuser (Root) browsing and file operations across system partitions via libsu.
* 🌐 **Wireless File Sharing (FTP Server)** — Transfer files easily between your device and PC over Wi-Fi without cables.
* ⭐ **Favorites** — Star your most important files and folders for instant, one-tap access.
* ☁️ **Google Drive & Cloud Folders** — Add cloud storage folders from Google Drive directly, with fingerprint lock support.
* 🗜️ **Powerful Archive Engine** — Browse, create, and extract ZIP, RAR, 7z, TAR, GZ, ISO, APK, and password-protected AES-256 archives.
* 🎨 **Enhanced Modern Look** — Refined Material 3 interface with fluid spring animations, clean layouts, and dynamic theming.
* 🛡️ **Shizuku Integration** — Access restricted `Android/data` and `Android/obb` directories without root.
* ♻️ **Recycle Bin** — Restore deleted files or erase them permanently with confidence.
* 💾 **USB OTG & SD Cards** — Comprehensive support for SD cards, USB flash drives, and external storage.
* 📄 **Document & Code Viewer** — Preview and read `.md`, `.txt`, `.xml`, `.yml`, `.yaml`, `.json`, `.html`, `.css`, `.js`, `.ts`, `.sh`, `.py`, `.csv`, and `.log` files directly inside the app.
* ⚡ **Background Operations** — Run large file copy, move, compress, and extract jobs reliably with progress notifications.
* 🔒 **Privacy First & Zero Ads** — No ads, no trackers, no telemetry, and 100% open source.

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
* **Root Engine:** [libsu](https://github.com/topjohnwu/libsu)
* **FTP Server:** [Apache Mina FtpServer](https://mina.apache.org/ftpserver-project/)
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
  Built with ❤️ using Kotlin, Jetpack Compose, and open-source love — fully vibe coded, professionally crafted.
</p>
