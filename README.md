# Carom

Open-source phone-to-desktop sync for notifications and clipboard, running on your own
Cloudflare account. An Android app mirrors notifications and clipboard to a macOS
menu-bar app through a small Worker and one Durable Object (the *Hub*) that you deploy
yourself. Everything is end-to-end encrypted: the Hub only ever sees ciphertext.

| Folder | What |
|---|---|
| [`protocol/`](protocol/) | Protocol spec, JSON Schemas, test vectors, TypeScript reference implementation |
| [`hub/`](hub/) | Cloudflare Worker + Durable Object, and the `carom-hub` deploy CLI |
| [`android/`](android/) | Kotlin Android app (in development) |
| [`macos/`](macos/) | Swift menu-bar app; `CaromKit` core package (in development) |
| [`docs/`](docs/) | Client behaviour contract; setup guide and threat model (planned) |

Status: early development. Read [`protocol/SPEC.md`](protocol/SPEC.md) for the contract.

## License

Carom is licensed under the [GNU General Public License v3.0](LICENSE).
