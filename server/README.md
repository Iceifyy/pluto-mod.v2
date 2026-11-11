# Pluto Mod friends server

Relays friend requests and messages between players using Pluto Mod.

1. Install Node.js 18 or newer.
2. Run `node server.js` (default port 8080, set `PORT` to change it).
3. In Minecraft, open the Pluto menu, Friends tab, enter `http://YOUR_ADDRESS:8080`
   and click Create Account.

Notes:
- To use it over the internet, put it behind HTTPS (a reverse proxy like Caddy or nginx).
  Over plain http, login tokens travel unencrypted.
- Everyone who wants to chat must use the same server.
- Accounts are claimed first come, first served by Minecraft name. A name is not
  verified against Mojang yet, so on a public server someone could claim your name first.
- Data is stored in data.json next to the server file.
