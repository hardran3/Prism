# Prism

<p align="center">
  <strong>A lightweight, media-focused Nostr client for Android.</strong>
</p>

<p align="center">
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/language-Kotlin-purple.svg" alt="Kotlin"></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg" alt="Compose"></a>
</p>

Prism is designed to be the ultimate **Share Target** for the Nostr ecosystem. Whether you are browsing the web, scrolling through a gallery, or editing text, Prism allows you to share content to Nostr seamlessly. It features a robust scheduling engine, rich media previews, and a clean, multi-user interface.

## ✨ Key Features

### 🚀 Seamless Sharing
*   **Share from Anywhere**: Prism registers as a system share target for Text, URLs, Images, and Video.
*   **Smart Parsing**: Automatically cleans tracking parameters from URLs.
*   **Rich Previews**: 
    *   **OpenGraph**: Beautiful link preview cards for websites.
    *   **Nostr Native**: Renders referenced notes (`note1`, `nevent1`) and profiles directly in the composer.
    *   **Smart Hiding**: URLs that generate a preview are automatically hidden from the text body to keep your notes clean.

### 📅 Robust Scheduling
Prism features a **Hybrid Trigger System** designed to bypass modern Android battery optimizations:
*   **Precision**: Uses `AlarmManager` for exact-time wake-ups.
*   **Reliability**: Executes via expedited `WorkManager` tasks to ensure delivery even on Mobile Data or in Doze mode.
*   **Offline Intelligence**: Automatically queues notes if the internet is lost and retries with exponential backoff.
*   **History**: View precise delivery times (e.g., "Sent at 10:00 (+2s)") to verify performance.

### 📸 Blossom Media Support
*   **Blossom Native**: Dedicated support for the Blossom HTTP blob storage protocol.
*   **Media Gallery**: Visual thumbnail row for images, GIFs, and videos.
*   **Video Support**: Generates video frame thumbnails and indicates playback.
*   **Compression**: Configurable image compression to save bandwidth.
*   **Direct Links**: Embedded media URLs in text are automatically detected, visualized, and counted.

### 🔐 Signing (NIP-55)
*   **Background Signing**: Works with external signers (like Amber) to sign events in the background without screen flashing or app switching.
*   **Multi-User**: Seamless account switching.

### 🎨 Polished UI/UX
*   **Haptic Feedback**: Tactile response for interactions.
*   **Highlights**: Visual vertical indicators for NIP-84 Highlight posts.
*   **Custom Sounds**: Satisfying custom alert sound upon successful publishing.
*   **Notifications**: Grouped notifications with avatars and deep-linking to your favorite Nostr client.

## 🚀 Getting Started

### Prerequisites
*   An Android device running Android 8.0 (Oreo) or higher.
*   A NIP-55 compatible signer app (e.g., **Amber**) installed and logged in.

### Installation
1.  Download the latest APK from the [Releases](https://github.com/hardran3/Prism/releases) page.
2.  Install the app on your device.
3.  Open Prism and complete the **Onboarding Flow**:
    *   Connect your Signer.
    *   Select your Blossom Media Servers.
    *   Grant permissions for **Alarms**, **Battery Optimization**, and **Notifications** to enable the scheduler.

### How to Use
1.  **Share**: Open any app (Gallery, Browser, etc.), click "Share," and select **Prism**.
2.  **Compose**: Add text, tag users (using `@name`), or attach more media.
3.  **Schedule**: Tap the 🕒 icon to pick a time, or just hit **Send** to publish immediately.
4.  **Manage**: Open Prism directly to view Drafts, edit Pending posts, or review History.

## 🤝 Contributing
Contributions are welcome! Please feel free to submit a Pull Request.

---
*Built with ❤️ for the Nostr protocol.*
