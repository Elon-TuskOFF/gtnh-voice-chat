# Validation

## Automated

`./scripts/test-core.sh` compiles the core and Java Sound adapter to Java 8 bytecode and runs checks for codec quality/silence, malformed audio, per-byte ciphertext/header tampering, wrong keys/directions, rate limiting, proximity/dimension routing, actual encrypted UDP delivery, replay rejection, absent players, logout/reconnect key invalidation, unregistered clients, and clean socket shutdown.

`./gradlew test` wraps the same suite in JUnit. `./gradlew build` must additionally compile the Forge adapter, process resources, reobfuscate the jar, and run the GTNH build checks. Standalone success does not establish Forge or game compatibility.

The full `./gradlew --no-daemon build` and artifact upload succeeded in [GitHub Actions run 42243870440](https://github.com/Elon-TuskOFF/gtnh-voice-chat/actions/runs/42243870440) for commit `00f994f`. This validates the build and automated checks, but not the manual game/audio checklist below.

## Required game checks before release

Use a real dedicated GTNH server and two GTNH clients. Keep the same pack versions across them. Install the voice mod on the server and only one client first.

- Confirm the client without this mod joins and plays normally; verify no missing-mod rejection or unknown custom-payload errors.
- Install the same jar on the second client. Confirm both authenticate on the single configured UDP port and see `Voice: ready`.
- Hold the PTT key on one client and hear speech on the other. Repeat in the opposite direction and then simultaneously. Confirm silence when PTT is released or chat/a GUI is opened, focus is lost, or voice is muted.
- Move farther than the configured radius, change dimension, die, respawn, then reconnect. Check routing, restored voice, no self echo, no stale audio, and no leftover capture devices or threads.
- Join a GTNH server without this mod using a modded voice client. Minecraft must work and the client must not send a voice handshake to an unregistered channel.
- Block the UDP port, use a busy UDP port, disable the server voice config, and test an incompatible voice protocol. Minecraft login/play must continue; only voice may be unavailable.
- Test external players through the actual hosting firewall/NAT. If external and internal port numbers differ, test `advertisedPort`.
- Test default input/output devices on Windows and Linux using the actual Java version used by your GTNH launcher, including the modern-Java GTNH distribution. Check for crackling, clipping, CPU use, and microphone release on disconnect.

This checklist has not yet been run. The cloud machine's loopback tests do not validate microphones, internet UDP/NAT, or GTNH pack interactions.
