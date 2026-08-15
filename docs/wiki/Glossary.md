# Glossary

## Add-on

An optional Lua-based extension loaded by the original game client. It can change interface and interaction behaviour but does not replace the Android launcher.

## ARM64

The processor type used by modern Android handhelds such as the Retroid Pocket 6.

## Bot

A computer-controlled resident of the local world. Population settings decide how many can be online and how much background activity they perform.

## Box64

A compatibility component that helps an ARM Android device run programs built for an x86-64 processor.

## Classic-DB

The starting classic world database used by the CMaNGOS world server.

## Build 5875

The specific original World of Warcraft 1.12.1 client build supported by Pocket Realm.

## Client

The visible game program used by the player. Pocket Realm requires the player to supply it and works from a private managed copy.

## CMaNGOS

The open-source classic game server project that supplies Pocket Realm's local login and world servers.

## Database

The private local store for accounts, characters, and durable realm state.

## Dead zone

The small neutral area around the centre of an analogue stick. Increasing it can stop unwanted movement or camera drift.

## DXVK

A graphics translation layer that converts the game's Direct3D calls into Vulkan calls that Android graphics drivers can handle.

## Generation

A complete, verified version of managed files such as the client or add-on set. Pocket Realm publishes complete generations so a partial update does not become active.

## LAN

Local area network. In Pocket Realm this means experimental play between devices on the same private network.

## MariaDB

The database program that stores the local accounts, characters, and lasting realm state.

## Managed client

Pocket Realm's verified private copy of the user-selected game client.

## Realm

The server side of the game world. Pocket Realm normally runs it privately on the same Android device as the client.

## Playerbots

The CMaNGOS project that supplies computer-controlled characters. Pocket Realm adds mobile population and load limits around it.

## Runtime supervisor

The coordinator that starts, watches, saves, stops, and recovers the database, realm, and client components.

## Safe mode

A launcher setting that disables project add-ons and the touch overlay for the next launch without changing realm or character data.

## Shader cache

Saved graphics translation work that can make later launches and repeated scenes smoother. Different renderer choices use separate caches.

## Support bundle

A diagnostic export with useful logs and metadata. Known secrets are redacted, but the file should still be reviewed before public sharing.

## Touch overlay

Pocket Realm's Android-owned on-screen movement, action, camera, and keyboard controls shown above the game.

## Vulkan

A modern graphics interface used by Android drivers and DXVK.

## Vortek

The Winlator-derived bridge that lets the guest graphics environment use Android's system Vulkan driver.

## VMap and MMap

Prepared server data for world geometry, line of sight, movement, and pathfinding.

## Wine

A compatibility environment that lets the Windows game client run without installing Windows on the Android device.
