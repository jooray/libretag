# LibreTag Android app 0.13.3 (preview)

Signing certificate SHA-256: `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`. Android 8.0 or newer.

## Share a tag with full access

**Share** in the tag's menu has a third level next to **View only** and **View and control**. **Full access** also hands over the tag's console key, so the other phone can do what yours does over the cable and Bluetooth: change the tag's settings, connect over Bluetooth, wake the tag over Bluetooth and update its firmware. It's meant for your own second phone, a tablet or a laptop.

- Before the code shows, the app warns you. Whoever has the code can change the tag's settings, give it a new key or unpair it, and you can't take the code back. Share it only with your own devices or with someone you trust completely.
- **Copy link** under the code puts the share link on the clipboard (marked as sensitive, so Android doesn't preview it), for a computer or a phone you can't scan from. It works at every level.
- The other phone scans the code with **Add tag** as before. A tag shared with full access shows **Service** and **Share** in its menu. If you already follow that tag, scanning a new code replaces the old level.
- The code carries no pairing key. The other phone can't read where the tag was before you shared it, and it can't send the tag's own Nostr messages.
- If the other phone gives the tag a new key (Service > New key), it becomes the tag's owner and your phone loses the tag. That's what the warning is about.

## Smaller changes

- The **Track** button no longer says "1 h". It opens the dialog where you pick how often and for how long.

- The firmware update screen is now translated into Slovak, Czech, German, Spanish and Dutch. It was English only.

## The console from a laptop

The tools in the repository's `host/` folder take the same link. `libretag share import` keeps the keys in a file only you can read (`~/.config/libretag/tags/`, mode 600) and never prints them. `libretag console` unlocks the tag on USB and runs console commands, and `tools/blecon.py run --tag` does the same over Bluetooth. That's enough for a script, or an agent, working on your own tag:

```sh
cd host
pbpaste | uv run libretag share import -
uv run libretag console -c status
```

## Upgrading

Install over 0.13.1. Tracker firmware 0.9.5 or newer, as before. The app carries firmware 0.9.6 and offers the update over the cable or Bluetooth. Both phones need 0.13.3 for full access. An older app reads a full-access code as View and control.
