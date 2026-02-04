# Prism

Prism is a lightweight Android "Bridge" app that allows you to highlight text in any app (like a browser), "Share" it, and post it as a **NIP-84 Highlight** or **Kind 1 Note** to the Nostr network.

It integrates seamlessly with external **NIP-55 Signers** (like Amber) to keep your private keys safe and secure.

## Features

- **NIP-84 Highlights**: Posts `kind: 9802` events with the `content` as the highlight and `r` tags for the source URL.
- **NIP-55 Signer Support**: Delegates signing to external apps, such as Amber.
- **Smart URL Extraction**: Automatically extracts Source URLs from Chrome's "Quote card" share format.
- **Privacy First**: Includes a built-in filter to strip tracking parameters (`utm_`, `fbclid`, `si`, etc.) from shared URLs.
- **Persistent Login**: Remembers your NIP-55 signer and profile selection over sessions.
- **Relay Discovery**: Fetches your generic relay list (Kind 10002) to ensure your notes are seen where it matters.

## How to Use

1.  **Install Prism** and a **NIP-55 Signer** (e.g., Amber).
2.  **Select Text** in a browser (e.g., Chrome).
3.  Tap **Share** (avoid the floating menu "Prism" if you want the URL, use the system Share sheet).
4.  Select **Prism** from the list.
5.  **Log In**: Tap the Person icon to authenticate with your Signer.
6.  **Toggle Mode**:
    - Tap the **Edit** icon for a standard Text Note (Kind 1).
    - Tap the **Lightbulb** icon for a Highlight (Kind 9802).
7.  **Post**: Verify the extracted URL and text, then tap **Post**.

## Building

This project is built with standard Android tools:
- Kotlin
- Jetpack Compose (Material 3)
- Gradle

Open the project in Android Studio and run `app`.

## License

MIT
