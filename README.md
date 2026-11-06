# AtmospheraLib

Shared client rendering library for the Atmosphera mods. It patches the Sodium-port terrain renderer (Embeddium/Xenon) with spare vertex bits, frame uniforms, scene depth copies and a shared wind/clock service that Gale, Torrente and Lumis use.

Client-only. Never required on a server.

## Requirements

- Minecraft 1.20.1, Forge 47.x
- Embeddium (optional): without a Sodium-port terrain renderer, the library stays inactive

## Part of Atmosphera

- [AtmospheraLib](https://github.com/SolarBiscuit/AtmospheraLib) (this repository)
- [Gale](https://github.com/SolarBiscuit/Gale): wind and waving foliage
- [Lumis](https://github.com/SolarBiscuit/Lumis): coloured light
- [Torrente](https://github.com/SolarBiscuit/Torrente): water

## License

GNU General Public License v3.0 only (GPL-3.0-only). See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Credit: SolarBiscuit. If you redistribute this mod or a modified version, keep the copyright and attribution notices and link to this repository. Modified versions must be marked as modified (GPL-3.0 section 7, see NOTICE).
