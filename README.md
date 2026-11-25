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

## Troubleshooting

**"No answer from ..." / scan finds nothing** — the *listening* computer's firewall is blocking the ports. Open them there:

- **Linux (ufw):** `sudo ufw allow 5000/tcp && sudo ufw allow 5001/udp`
- **Windows:** when the "Windows Defender Firewall" popup appears for Java, tick **Private networks** and click Allow. Or in an admin PowerShell:
  `New-NetFirewallRule -DisplayName LanChat -Direction Inbound -Protocol TCP -LocalPort 5000 -Action Allow`
  `New-NetFirewallRule -DisplayName LanChatScan -Direction Inbound -Protocol UDP -LocalPort 5001 -Action Allow`
  Also make sure the Wi-Fi is set to a *Private* network, not *Public*.
- **macOS:** System Settings → Network → Firewall → allow incoming connections for `java`.

**"Connection refused"** — the IP is reachable but nobody is listening: the friend must run `listen` first, and both must use the same port.

**"Port 5000 is already in use"** (common on macOS, where AirPlay Receiver uses 5000) — use another port: `listen 5050` / `connect <ip> 5050`.

**Still nothing?** Both computers must be on the same network (IPs like `192.168.1.x` on both). Guest Wi-Fi, school/office Wi-Fi and some routers block devices from talking to each other ("AP/client isolation"). If you are not in the same place, install [Tailscale](https://tailscale.com) on both and use the friend's `100.x.x.x` address with `connect`.

Both sides need Java 17+ (`java -version`); older Java fails with compile errors.
