# GTNH Voice Chat

An optional proximity voice mod targeting **GT New Horizons / Minecraft 1.7.10 / Forge 10.13.4.1614**.

Install the same jar on the server and on clients who want voice. Players **without this mod can still join and play**; they cannot speak or hear voice. A client with the mod can also join a server without it. This does not remove GTNH's other mod requirements. The server never broadcasts voice packets to clients without an opted-in voice session.

## Status

Initial implementation. The full Forge build, formatting checks, and automated tests [pass in GitHub Actions](https://github.com/Elon-TuskOFF/gtnh-voice-chat/actions/runs/42243870440). Core audio/encryption/UDP tests also pass locally on Java 8 and 25. Download the `gtnh-voice-chat` artifact from a successful Actions run for the built jars. **In-game compatibility, microphone/speaker behavior, and external UDP connectivity remain unverified**; complete [the in-game checklist](docs/testing.md) before deploying.

## Server setup

1. Build the mod and put its normal jar from `build/libs/` in the GTNH server's `mods/` directory. Do not install the sources or dev jar.
2. Start the server once to generate `config/gtnhvoice.cfg`.
3. Set `server.port` to your available **UDP** port (default `24454`). Restart after config changes.
4. Open/forward that one UDP port in your firewall, router, and hosting panel. All voice clients share it; no extra TCP or UDP voice port is needed. Minecraft continues using its existing game port.
5. If NAT exposes a different external UDP port, set `server.advertisedPort` to that external port. Leave it `0` otherwise. UDP must be reachable on the **same IP address used for the Minecraft connection**. Separate voice hosts/TCP proxies need additional configuration outside this initial implementation.

The config uses Forge's standard categories (for example `I:port=24454` inside `server { ... }`). Other server settings:

| Setting | Default | Purpose |
| --- | --- | --- |
| `enabled` | `true` | Enable voice without changing normal login behavior |
| `bindAddress` | `0.0.0.0` | Local UDP listening interface |
| `port` | `24454` | The one local UDP port |
| `advertisedPort` | `0` | External port override; `0` uses `port` |
| `range` | `48` | Audible radius in blocks; same dimension only |

If binding the voice port fails, the mod logs an error and leaves Minecraft running with voice disabled.

## Client setup

Put the same jar in your GTNH client's `mods/` directory. Use your OS default microphone and speakers/headphones. Hold **V** to talk; rebind it under **Options → Controls → GTNH Voice Chat** if it conflicts with another mod. The mute/deafen binding starts unassigned; assign it there if desired. Local playback volume is `client.volume` in `config/gtnhvoice.cfg` (restart to apply).

The microphone is opened only during push-to-talk. Opening chat, a GUI, losing game focus, muting, or disconnecting stops transmission. The HUD reports readiness, talking, mute, or an unreachable UDP connection. If a device cannot be opened, fix the OS default device and reconnect. Missing/blocked UDP never prevents joining the Minecraft server.

## Build and test

The build uses the official [GTNH project starter](https://github.com/GTNewHorizons/ExampleMod1.7.10), pinned GTNH convention plugin `2.0.33` and Gradle `9.7.1` with distribution checksum verification. Use **JDK 25 for Gradle** and a **JDK 8 compilation toolchain**, producing Java 8-compatible mod classes.

```sh
./gradlew setupDecompWorkspace
./gradlew spotlessApply
./gradlew build
```

The Gradle test suite exercises real loopback UDP sockets; it requires permission to bind local sockets, not an external port. The codec/network core and Java Sound adapter can also be compiled and tested without downloading Minecraft or Gradle dependencies, using JDK 17+:

```sh
./scripts/test-core.sh
```

The full build requires access to GTNH Maven, Forge Maven, Maven Central, Gradle distributions/plugins, Minecraft libraries/metadata/downloads, and GitHub. Cloud setup must preserve the managed proxy and CA trust. `docs/testing.md` lists the remaining manual game/audio checks.

## Transport and limits

- Minecraft's authenticated connection only negotiates voice capability and issues fresh session credentials. Voice travels over UDP, independently of Minecraft ticks.
- AES-128-GCM authenticates and encrypts UDP datagrams in transit. Separate directional nonces, monotonic sequences, and session rotation prevent packet forgery/replays. The server can decrypt/relay audio; this is not end-to-end encryption between players. Use an authenticated Minecraft server (`online-mode=true`); offline mode weakens player identity.
- Server-authoritative position snapshots enforce distance and dimension routing. No self echo. The server rate-limits sessions and bounds packets; client audio queues are bounded to avoid unbounded latency/memory growth.
- Audio is 16 kHz mono IMA ADPCM, 20 ms per independent block (163 audio bytes). It avoids native codec libraries and extra client downloads. With headers/authentication/IP overhead, allow roughly **100 kbit/s per active speaker per nearby listener**. The client mixes up to 16 simultaneous speakers.
- This is a separate protocol; it is not compatible with Simple Voice Chat clients. Initial playback has a short jitter buffer and distance attenuation, but no stereo positioning, noise suppression, echo cancellation, device-selection UI, groups, or recording. Headphones are recommended.
- One sequence space per direction drops late/out-of-order packets. Independent audio blocks recover on the next packet; there is no retransmission or packet-loss concealment.
