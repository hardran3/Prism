# Prism 🔺

**Prism** is a high-performance Android client for the Nostr protocol, engineered to bridge the gap between OS-level sharing intents and the decentralized web.

It operates on a **keyless, stateless architecture**, delegating cryptographic operations to **NIP-55** compliant signers (e.g., Amber) and offloading media storage to **Blossom (NIP-98)** servers. This design ensures that private keys never enter the application runtime, maximizing security context isolation.

---

## 🏗️ Architecture

Prism functions as an **Intent Processor**:
1.  **Ingestion:** Intercepts `ACTION_SEND` and `ACTION_PROCESS_TEXT` intents from the Android OS.
2.  **Normalization:** Sanitizes inputs (strips `utm_` parameters via regex) and determines the optimal Event Kind.
3.  **Media Processing:** 
    *   **Privacy:** Performs byte-level, lossless EXIF stripping for JPEGs to remove metadata without re-encoding artifacts.
    *   **Optimization:** optionally resizes and compresses high-bandwidth assets (1024px / 80% Quality).
4.  **Distribution:** Uploads media to multiple Blossom servers in parallel for redundancy.
5.  **Signing & Broadcasting:** Constructed Event JSON is passed to a NIP-55 Signer, then broadcast via an ephemeral connection to the user's NIP-65 Relay List.

---

## 🔒 Privacy Engineering

*   **Zero-Knowledge Architecture:** The app has no internal database for private keys. Session state is ephemeral or token-based (pubkey only).
*   **Metadata Scrubbing:** The `ImageProcessor` enforces privacy by stripping EXIF/XMP data from all `image/jpeg` inputs before hashing.
*   **Link Sanitization:** Outbound links are scrubbed of common tracking query parameters (`fbclid`, `gclid`, `utm_source`, etc.) before being committed to an event.

---

## ⚙️ Workflow & Configuration

### Blossom Integration
Prism implements a **Multi-Server Upload Strategy**:
*   **Parallelization:** Artifacts are uploaded to all configured servers simultaneously.
*   **Resiliency:** The first successful URL is committed to the event, but all uploads continue to ensure redundancy.
*   **Auth Standard:** Uses NIP-98 `Authorization: Nostr <base64(event)>` headers.

### Configuration
*   **Default To Kind 1**: Start all posts (media, highlights) as Kind 1 Notes. You can manually switch kinds. (Default: Off). Aggressive Compression (Resize + Re-encode).
*   **Kind Overrides:** Option to force `kind: 1` for all payloads for legacy client compatibility.

---

## 🛠️ Build Instructions

**Environment:** Android Studio Ladybug | Kotlin 1.9 | JDK 17

```bash
# Clone repository
git clone https://github.com/ryans/prism.git
cd prism

# Build Debug Variant
./gradlew assembleDebug
```

## 📄 License

MIT License
