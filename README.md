# Prism 🔺

**Prism** is a lightweight, privacy-focused Android intent handler for the Nostr protocol. It bridges OS-level `ACTION_SEND` and `ACTION_PROCESS_TEXT` intents to the decentralized web, allowing you to share content to Nostr from any app on your device.

Prism uses a **keyless, stateless architecture**. It delegates all cryptographic signing to **NIP-55** compliant apps (like Amber) and offloads media storage to Blossom servers. Your private keys are never stored or accessed by Prism.

---

## Features

- **Global Intent Processing**: Seamlessly share text, URLs, and media from any Android application.
- **Privacy First**: Automatically strips EXIF/XMP metadata from images before upload to protect your location and device data.
- **Advanced Media Management**:
    - **Multi-Server Uploads**: Concurrent, bit-perfect uploads to multiple Blossom servers.
    - **Optimization**: Optional high-quality compression and resizing for bandwidth efficiency.
    - **Server Synchronization**: Pull your Blossom server list (Kind 10063) from relays or publish your local configuration to Nostr.
- **Intelligent Link Sanitization**: Automatically removes tracking parameters (like `utm_` and `fbclid`) from shared URLs.
- **Smart Scheduling**: Schedule notes for future publication with persistent status notifications and automated re-queuing on device reboot.
- **Local Relay Support**: Integration with local relays like Citrine for edge-case sovereignty.
- **Haptic Feedback**: Subtle tactile responses for a polished, modern user experience.

---

## Build Instructions

Prism is built using **Kotlin**, **Jetpack Compose**, and **Room**.

### Environment Requirements
- **Android Studio** Ladybug or newer
- **JDK** 17+
- **Minimum SDK**: API 26 (Android 8.0)

### How to Compile

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/hardran3/Prism.git
    cd Prism
    ```

2.  **Build the project:**
    - To build the debug APK:
      ```bash
      ./gradlew assembleDebug
      ```
    - To run all lint checks and tests:
      ```bash
      ./gradlew check
      ```

3.  **Installation:**
    The generated APK will be located at `app/build/outputs/apk/debug/app-debug.apk`. You can install it directly onto your device via ADB:
    ```bash
    adb install app/build/outputs/apk/debug/app-debug.apk
    ```

---

## License

MIT License
