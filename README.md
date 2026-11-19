# netnet — LanChat

Peer-to-peer terminal chat and file transfer over a LAN, in a single Java file. Requires Java 17+.

## Usage

```bash
java LanChat.java listen [port]          # wait for a peer (default port 5000)
java LanChat.java connect <ip> [port]    # connect to a peer
java LanChat.java scan                   # find listening peers on the LAN
```

In chat:

| Command | Action |
|---|---|
| text + Enter | send a message |
| `/send <path>` | send a file (with progress bar) |
| `/help` | show commands |
| `/quit` | disconnect |

Received files are saved to `./received/`.

## Ports

- TCP 5000 — chat and files
- UDP 5001 — LAN discovery (`scan`)

Traffic is not encrypted; use on trusted networks.
