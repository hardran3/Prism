# Prism 🔺

**Prism** is a powerful Android app for the Nostr network that lets you share **Highlights**, **Notes**, and **Media** seamlessly.

It focuses on **creating content** (Bridge to Nostr) while offloading signing to **NIP-55** apps (like Amber) and storage to **Blossom Servers**. Your private keys never touch Prism.

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| **NIP-84 Highlights** | Share text selections as `kind: 9802` events with source URLs. |
| **Media Sharing** | Upload images/videos to **Blossom Servers**. |
| **Multi-Server Upload** | Uploads to multiple servers in parallel for redundancy. |
| **Media Optimization** | Automatically compresses images (JPEG, 85%) and strips EXIF metadata. |
| **Draft System** | Never lose your work. Auto-saves drafts if you're interrupted. |
| **NIP-55 Signer** | Zero private key storage. Delegates signing to external apps. |
| **Smart URL Handling** | Intelligently manages URLs between Note body and Highlight sources. |
| **Privacy Filter** | Strips tracking params (`utm_`, `fbclid`, etc.) from shared URLs. |
| **NIP-65 Discovery** | Fetches your relay list to publish where you're known. |
| **Quick Settings Tile** | Start a new note instantly from Android Quick Settings. |

---

## 📲 How to Use

1.  **Install** Prism and a **NIP-55 Signer** (e.g., Amber).
2.  **Share Content**:
    *   **Text:** Highlight text in any app → Share → Prism.
    *   **Media:** Share an image/video from Gallery → Prism.
    *   **New Note:** Tap the Quick Tile or App Icon.
3.  **Log In** (first time): Tap the **Person** icon to connect your signer.
4.  **Customize**:
    *   **Mode:** Toggle between Highlight (💡), Note (✏️), or Media (📷).
    *   **Attach Media:** Tap the photo icon to upload directly.
5.  **Publish**: Tap **Send** to sign and broadcast.

---

## ⚙️ Advanced Settings

Access via the **Settings (⚙️)** icon:

*   **Blossom Servers**: Manage media servers (Defaults: Primal, Blossom.band).
*   **Optimize Media**: Toggle image compression/exif-stripping (Default: On).
*   **Always Use Kind 1**: Force all posts (media, highlights) to be Kind 1 Notes (Default: Off).
*   **Blastr**: Enable Blastr relay gateway (Default: Off).

---

## 🛠️ Supported NIPs

| NIP | Description |
|-----|-------------|
| [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Basic protocol flow. |
| [NIP-55](https://github.com/nostr-protocol/nips/blob/master/55.md) | External Signer Application. |
| [NIP-65](https://github.com/nostr-protocol/nips/blob/master/65.md) | Relay List Metadata. |
| [NIP-84](https://github.com/nostr-protocol/nips/blob/master/84.md) | Highlights. |
| **Blossom** | HTTP Media Server Protocol. |

---

## 🏗️ Building

**Requirements:** Android Studio, Kotlin, JDK 17+

```bash
# Clone the repo
git clone https://github.com/user/prism.git
cd prism

# Build debug APK
./gradlew assembleDebug
```

Or open in Android Studio and click **Run**.

---

## 📄 License

MIT
