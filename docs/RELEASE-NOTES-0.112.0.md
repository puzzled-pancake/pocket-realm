# Pocket Realm 0.112.0

This release rolls up the work since 0.103.0-alpha. Android uses `versionCode` 11.

## Multiplayer

- Host over the internet from the Multiplayer screen. Publish a game to the public browser or share an invite code for an unlisted game. The join wizard can create an account when the host allows sign-ups.
- The host can set a password, limit new accounts, and manage players and bans. LAN and direct connections are also available.
- Public discovery uses Nostr relays, and internet connections use iroh. Relays can see the IP address of clients that connect to them. Direct connections can expose an address to the other peer.

## Game files and local realms

- Import Vanilla 1.12.1, The Burning Crusade 2.4.3, or Wrath of the Lich King 3.3.5a. The app keeps each version’s data separate and offers matching add-ons. AndroidPort now has layouts for all three clients.
- Android can keep newly imported clients, staged archives, and prepared data on a portable SD card. The realm database stays on internal storage. Switch back to the original volume to see clients you imported there.
- Android can import a client for joining multiplayer without preparing data for a local realm. Failed imports now preserve their rejection reason in the import screen.
- Android’s **Bots & world** settings include bot presets and XP rates. The experimental LLM bot-chat feature from 0.103.0-alpha has been retired.

## Android compatibility

- The DXVK startup check recognizes newer Turnip output for Adreno 8xx. AndroidPort checks whether a frame is live before reading its anchor, addressing a reported crash when entering the world.
- Home scrolls on short landscape screens. The arm64 build removes retired native libraries and rebuilds the PRoot helper and secp256k1 JNI library with 16 KB ELF alignment.
- The Oryon floating-point trap workaround is available as a [Box64 source patch](../native/box64/patches/box64-v0.4.0-no-fp-traps.patch). The APK uses the existing Box64 binary.

## Install and upgrade

- Android users on arm64 can update through **Settings → App updates** or install the release APK over the previous official build. The app uses the same signing certificate as 0.103.0-alpha.
- Packaged local servers use Vanilla. TBC and WotLK are available for import and preparation, and a Windows source checkout can run their local servers with the corresponding native runtimes staged.
- Bring a legally obtained copy of the matching game client and data. Pocket Realm does not include them.
