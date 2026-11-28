# LibreTag Android app 0.14.2 (preview)

Signing certificate SHA-256: `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`. Android 8.0 or newer.

This release goes with the quiet mesh in tracker firmware 0.10. The app now does part of the work the repeaters used to do. Everything has to be updated together: the app needs tracker firmware 0.10.0 or newer and bridges at Android 0.6.0 or Python 0.7.0, and it doesn't read the older formats.

## The app confirms your tag's reports

Your tag mostly sends a quiet hello that only a node or a bridge close to it hears. The app opens each report and sends a short confirmation back through your own MeshCore node, or through the Nostr bridge that heard it. As long as the tag gets these, it doesn't flood the mesh.

- Keep the app running in the background, as before. The phone wakes shortly before each report the app expects, checks that its relays still answer and reconnects the ones that went quiet, so the confirmation goes out in time even when the phone sleeps.
- Commands go to the tag in its listening slots, which the app works out from the tag's last confirmed report. You still just press the button. The command card says when the next try is and whether a bridge could send it.

## What you see

- Each tag shows how it reaches you: in range of your node, in range of a bridge, through one or two repeaters to a bridge, or no bridge nearby (then it reports over the wider mesh).
- New notices tell you why a tag is quiet before raising an alarm:
  - the bridge that hears it is offline;
  - reports over the wider mesh are used up for today;
  - your MeshCore node is off;
  - Lost or Track ended;
  - a command did not reach the tag;
  - the tag is heard but doesn't seem to get the answers.
- Two new settings in the tag's settings:
  - **Check in over the wider mesh after**: how long the tag waits for an answer from nearby before it sends one report that repeaters carry.
  - **Report at once when it moves**: if nothing nearby answers, the tag goes over the wider mesh after a few seconds instead of at its next check. It uses more airtime.

## Fixes from the field tests (0.14.1, 0.14.2)

- A relay connection could die without the app noticing while the phone slept. A report then reached the app late, its confirmation never went out, and the tag fell back to a flood. The wake-up before each report fixes this.
- After Track or Lost ended, the app could raise a false "silent" alarm. With firmware 0.10.1 or newer the last report already says the tag is back to normal.

## Upgrading

Install over 0.13.3. Update your tags too: the app carries tracker firmware 0.10.2 and offers the update over the cable or Bluetooth. A tag on 0.9.x can't talk to this app.

If you send the confirmations through your own node with MeshCore Open, the app uses the Local API features your MeshCore Open build offers. Test build `localapi-test-10` works.
