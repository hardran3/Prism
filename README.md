# Prism 🔺

**Prism** is an Android intent handler for the Nostr protocol. It bridges OS-level `ACTION_SEND` and `ACTION_PROCESS_TEXT` intents to the decentralized web.

The application uses a **keyless, stateless architecture**. It delegates all cryptographic signing to **NIP-55** compliant apps (e.g., Amber) and offloads media storage to Blossom servers. Private keys are never stored or accessed by Prism.

---

## Supported Protocols

### NIPs (Nostr Implementation Possibilities)
| NIP | Description | Usage |
| :--- | :--- | :--- |
| **NIP-01** | Basic Protocol | Core event publication and metadata fetching. |
| **NIP-19** | bech32 Entities | Handling `npub`, `nprofile`, and `nevent` identifiers. |
| **NIP-20** | Command Results | **OK** message processing for publication confirmation. |
| **NIP-22** | Created At Limits | Handling relay-enforced timestamp boundaries. |
| **NIP-55** | Android Signer Intent | **Required.** Offloading event signing to external apps. |
| **NIP-65** | Relay Lists | Fetching user's read/write relays for broadcasting. |
| **NIP-68** | Picture Notes | Specification for image-centric events (Kind 20). |
| **NIP-71** | Video Events | Specification for short-form video events (Kind 22). |
| **NIP-96** | HTTP File Storage | Inspecting server capabilities (Blossom compatibility). |
| **NIP-98** | HTTP Auth | Authenticating media uploads via signed events. |

### Event Kinds
| Kind | Name | Description |
| :--- | :--- | :--- |
| `0` | Metadata | Fetched to display the active user's avatar and name. |
| `1` | Short Text Note | Standard text posts and media shares. |
| `20` | Picture Note | **NIP-68.** Image-centric posts with descriptions and titles. |
| `22` | Video Note | **NIP-71.** Short-form portrait video events. |
| `9802` | Highlight | Created when sharing text selections with a source URL. |
| `10063` | Blossom Server List | **NIP-118.** User configuration for Blossom servers. |
| `10002` | Relay List | **NIP-65.** Fetched to determine write relays. |
| `24242` | Blossom Auth | Ephemeral event used to authorize HTTP uploads. |

---

## Features

*   **Intent Processing**: Captures text, URLs, and images shared from any Android app.
*   **Media Optimization**:
    *   **Privacy**: Lossless stripping of EXIF/XMP metadata from JPEGs.
    *   **Compression**: Optional resizing (1024px limit) and re-encoding for bandwidth savings.
*   **Blossom Management**: 
    *   **Parallel Uploads**: Concurrent uploads to multiple servers with bit-perfect consistency.
    *   **Priority Reordering**: Intuitive drag-and-drop server prioritization in settings.
    *   **Nostr Sync**: Sync servers from Nostr (Kind 10063) or publish your local list to your profile.
*   **Link Sanitization**: Automatically removes tracking parameters (`utm_`, `fbclid`, etc.) from shared URLs.

---

## Build Instructions

**Environment:** Android Studio Ladybug | Kotlin 1.9 | JDK 17

```bash
# Clone repository
git clone https://github.com/hardran3/prism.git
cd prism

# Build Debug Variant
./gradlew assembleDebug
```

## License

MIT License
