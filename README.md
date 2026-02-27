# Prism

Prism is an Android application designed to facilitate sharing content to the Nostr protocol. It operates primarily as a system share target, allowing users to send text, links, and media to Nostr from other apps.

## Features

* **System Share Target**: Registers as an option in the Android share sheet for text, images, and video.
* **Note Scheduling**: Support for scheduling posts to be published at a specific future time.
* **Blossom Support**: Native integration with the Blossom HTTP blob storage protocol for media uploads.
* **Media Management**: Tools for image compression and video thumbnail generation.
* **History Synchronization**: Ability to sync past notes from relays, including a "Deep Scan" feature to discover relays used by your followers.
* **Drafts and Queuing**: Local storage for unsent drafts and an offline queue that automatically retries when connectivity is restored.
* **NIP-55 Signing**: Support for background event signing via external signers like Amber.
* **Link Previews**: Automatic generation of previews for web links and Nostr entities (notes, profiles).
* **Multi-Account Support**: Manage and switch between multiple Nostr identities.
