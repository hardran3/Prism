# Prism 🔺

**Prism** is a lightweight Android app that lets you highlight text in any app and share it directly to the Nostr network as a **NIP-84 Highlight** or a standard **Kind 1 Note**.

It works seamlessly with external **NIP-55 Signers** (like [Amber](https://github.com/greenart7c3/Amber)) — your private keys never touch Prism.

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| **NIP-84 Highlights** | Posts `kind: 9802` events with `r` tags for source URLs. |
| **Kind 1 Notes** | Standard text notes with optional URL appended. |
| **NIP-55 Signer** | Delegates all signing to external apps (zero key storage). |
| **Smart URL Extraction** | Automatically grabs URLs from Chrome's "Quote card" share. |
| **Privacy Filter** | Strips tracking params (`utm_`, `fbclid`, `si`, etc.). |
| **NIP-65 Relay Discovery** | Fetches your `kind: 10002` relay list to publish where you're known. |
| **Quick Settings Tile** | Start a new note directly from Android's Quick Settings. |
| **Smart Mode Toggle** | Switching modes moves the URL intelligently between fields. |
| **Auto-Close on Success** | Publishes, shows a toast, and gets out of your way. |

---

## 📲 How to Use

1.  **Install** Prism and a **NIP-55 Signer** (e.g., Amber).
2.  **Highlight text** in any app (browser, reader, etc.).
3.  Tap **Share** → Select **"Share to Nostr"** (Prism).
4.  **Log In** (first time): Tap the **Person** icon to connect your signer.
5.  **Toggle Mode** (optional):
    *   🔆 **Lightbulb** = Highlight (Kind 9802)
    *   ✏️ **Edit** = Note (Kind 1)
6.  Tap the **Send** button to sign and publish.

### Quick Settings Tile
Add the **"New Nostr Note"** tile to your Quick Settings panel for instant access.

---

## 🛠️ Supported NIPs

| NIP | Description |
|-----|-------------|
| [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Basic protocol flow, event structure. |
| [NIP-55](https://github.com/nostr-protocol/nips/blob/master/55.md) | Android Signer Application (external signing via Intents). |
| [NIP-65](https://github.com/nostr-protocol/nips/blob/master/65.md) | Relay List Metadata (`kind: 10002`). |
| [NIP-84](https://github.com/nostr-protocol/nips/blob/master/84.md) | Highlights (`kind: 9802`). |

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
