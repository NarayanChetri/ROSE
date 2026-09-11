# 🌍 Translating ROSE

Thank you for your interest in translating **ROSE**! By contributing translations, you help make ROSE accessible to users all over the world in their native languages.

All base English strings are located in:
👉 [`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml)

---

## 📖 How to Contribute via GitHub

### 1. Fork & Clone
Fork the [ROSE repository](https://github.com/NarayanChetri/ROSE) and clone it locally:
```bash
git clone https://github.com/<your-username>/ROSE.git
cd ROSE
```

### 2. Create the Locale Directory
Android uses standard ISO language and optional region codes under `app/src/main/res/`.

Create a directory named `values-<locale>` inside `app/src/main/res/`:
```bash
# Example for Spanish:
mkdir app/src/main/res/values-es

# Example for Hindi:
mkdir app/src/main/res/values-hi

# Example for Brazilian Portuguese:
mkdir app/src/main/res/values-pt-rBR
```

#### Common Locale Directory Codes
| Language | Directory Name |
| :--- | :--- |
| **Arabic** | `app/src/main/res/values-ar/` |
| **Chinese (Simplified)** | `app/src/main/res/values-zh-rCN/` |
| **Chinese (Traditional)** | `app/src/main/res/values-zh-rTW/` |
| **French** | `app/src/main/res/values-fr/` |
| **German** | `app/src/main/res/values-de/` |
| **Hindi** | `app/src/main/res/values-hi/` |
| **Indonesian** | `app/src/main/res/values-in/` |
| **Italian** | `app/src/main/res/values-it/` |
| **Japanese** | `app/src/main/res/values-ja/` |
| **Korean** | `app/src/main/res/values-ko/` |
| **Polish** | `app/src/main/res/values-pl/` |
| **Portuguese (Brazil)** | `app/src/main/res/values-pt-rBR/` |
| **Portuguese (Portugal)** | `app/src/main/res/values-pt-rPT/` |
| **Russian** | `app/src/main/res/values-ru/` |
| **Spanish** | `app/src/main/res/values-es/` |
| **Turkish** | `app/src/main/res/values-tr/` |
| **Ukrainian** | `app/src/main/res/values-uk/` |
| **Vietnamese** | `app/src/main/res/values-vi/` |

> *Note: For region-specific variants, Android requires `-r` before the country code (e.g. `zh-rCN`, `pt-rBR`).*

### 3. Copy `strings.xml`
Copy the base English `strings.xml` into your new directory:
```bash
cp app/src/main/res/values/strings.xml app/src/main/res/values-<locale>/strings.xml
```

### 4. Translate the Strings
Translate the text **inside** the XML tags:
```xml
<!-- Base English -->
<string name="action_delete">Delete</string>

<!-- Spanish translation -->
<string name="action_delete">Eliminar</string>
```

---

## ⚠️ Crucial Translation Rules

To ensure your translation compiles cleanly without crashes, please follow these rules:

### 1. ❌ DO NOT Change `name="..."` Attributes
The `name` attribute is used directly by the Kotlin code. If you modify it, the app will fail to compile.
```xml
<!-- ✅ CORRECT -->
<string name="action_refresh">Actualizar</string>

<!-- ❌ WRONG (Breaks the build) -->
<string name="action_actualizar">Actualizar</string>
```

### 2. 🔤 Preserve Format Specifiers (`%1$s`, `%1$d`, `%%`)
Strings with dynamic values contain placeholders like `%1$s` (for text) or `%1$d` (for numbers):
- **Never translate or omit these placeholders.**
- You may rearrange their order if your language's grammar requires it (e.g. `%2$s` before `%1$s`).
- In Android XML, a literal percent sign is written as `%%`.

```xml
<!-- Base English -->
<string name="storage_free_of_total">%1$s free of %2$s</string>

<!-- ✅ CORRECT (Spanish) -->
<string name="storage_free_of_total">%1$s libres de %2$s</string>

<!-- ❌ WRONG (Missing format specifier) -->
<string name="storage_free_of_total">libres del total</string>
```

### 3. 🛡️ Escape Special Characters
Android XML requires certain characters to be escaped with a backslash (`\`):
- **Apostrophe (`'`)**: Write `\'`
  ```xml
  <!-- ✅ CORRECT -->
  <string name="about_app_description">C\'est l\'explorateur...</string>

  <!-- ❌ WRONG -->
  <string name="about_app_description">C'est l'explorateur...</string>
  ```
- **Double quote (`"`)**: Write `\"`
- **At sign (`@`) and Question mark (`?`)** at the beginning of a string: Write `\@` or `\?`.
- **Newlines**: Preserve `\n` for line breaks.

### 4. 🏷️ Keep Brand and Technical Terms Intact
Do not translate:
- **ROSE** (app name)
- **Shizuku**
- **Android**, **Google Drive**
- Technical terms: `MD5`, `SHA-256`, `APK`, `USB`, `SD card`

---

## 🧪 Testing Your Translation

If you have Android Studio or the Gradle CLI installed, test your changes locally:

```bash
# Check if your XML builds without syntax errors:
./gradlew compileDebugKotlin

# Or build the debug APK:
./gradlew assembleDebug
```

If you see an error like `Mismatched formatting specifier` or `Apostrophe not escaped`, check your XML file at the line mentioned in the error output.

---

## 🚀 Submitting Your Contribution

1. Commit your changes to a new branch:
   ```bash
   git checkout -b translation-<locale>
   git add app/src/main/res/values-<locale>/strings.xml
   git commit -m "i18n: add <language> translation"
   ```
2. Push the branch to your fork:
   ```bash
   git push origin translation-<locale>
   ```
3. Open a **Pull Request** on the main [ROSE repository](https://github.com/NarayanChetri/ROSE).

---

## ❤️ Credits

All translation contributors will be credited in release notes. Thank you for making ROSE better for everyone!

