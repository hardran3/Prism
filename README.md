# Prism 🔺

**Prism** is an Android intent handler for the Nostr protocol. It bridges OS-level `ACTION_SEND` and `ACTION_PROCESS_TEXT` intents to the decentralized web.

The application uses a **keyless, stateless architecture**. It delegates all cryptographic signing to **NIP-55** compliant apps (e.g., Amber, Gossip) and offloads media storage to Blossom servers. Private keys are never stored or accessed by Prism.

---

## Supported Protocols

### NIPs (Nostr Implementation Possibilities)
| NIP | Description | Usage |
| :--- | :--- | :--- |
| **NIP-01** | Basic Protocol | Core event publication and metadata fetching. |
| **NIP-19** | bech32 Entities | Handling `npub`, `nprofile`, and `nevent` identifiers. |
| **NIP-55** | Android Signer Intent | **Required.** Offloading event signing to external applications. |
| **NIP-65** | Relay Lists | Fetching user's read/write relays for event broadcasting. |
| **NIP-96** | HTTP File Storage | Inspecting server capabilities (Blossom compatibility). |
| **NIP-98** | HTTP Auth | Authenticating media uploads via signed events. |

### Event Kinds
| Kind | Name | Description |
| :--- | :--- | :--- |
| `0` | Metadata | Fetched to display the active user's avatar and name. |
| `1` | Short Text Note | Standard text posts and media shares. |
| `9802` | Highlight | Created when sharing text selections with a source URL. |
| `10002` | Relay List | Fetched to determine where to publish events. |
| `24242` | Blossom Auth | Ephemeral event used to authorize HTTP uploads. |

---

## Features

*   **Intent Processing**: Captures text, URLs, and images shared from any Android app.
*   **Media Optimization**:
    *   **Privacy**: Lossless stripping of EXIF/XMP metadata from JPEGs.
    *   **Compression**: Optional resizing (1024px limit) and re-encoding for bandwidth savings.
*   **Blossom Uploads**: Parallel uploads to multiple configured Blossom servers; commits the first successful URL.
*   **Link Sanitization**: Automatically removes tracking parameters (`utm_`, `fbclid`, etc.) from shared URLs.

---

## Build Instructions

**Environment:** Android Studio Ladybug | Kotlin 1.9 | JDK 17

```bash
# Clone repository
git clone https://github.com/ryans/prism.git
cd prism

# Build Debug Variant
./gradlew assembleDebug
```

## License

MIT License
