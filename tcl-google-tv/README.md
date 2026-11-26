# TCL Google TV clean-up (50S450G, Android 11)

Scripts that strip the ads and background junk off a TCL Google TV, speed up the menus, and
replace the Google TV home screen with **Projectivy Launcher**, a clean, ad-free row of app icons
much like Apple TV's home screen. No root needed, and everything can be undone.

You run them from a PC on the same network as the TV. The PC talks to the TV over
**ADB** (Android Debug Bridge), Android's own maintenance link.

| File | What it is |
|---|---|
| `packages.txt` | What gets turned off: `x` = turned off, `-` = left alone. Edit it to taste. |
| `tvtune.sh` | The script that runs on the TV. You don't run this directly. |
| `run.sh` / `run.ps1` | Mac/Linux and Windows launchers: connect to the TV, copy the files over and run one step. |

## 1. Prepare the TV (one time)

1. **Turn on Developer options:** Settings → System → About → scroll to **Android TV OS build**
   and press OK on it 7 times, until it says you're a developer.
2. **Turn on debugging:** Settings → System → Developer options → **USB debugging** → On.
   This also opens ADB over the network on port 5555.
3. **Note the TV's IP address:** Settings → Network & Internet → your network (or
   Settings → System → About → Status). For example, `192.168.1.50`.
   Give the TV a fixed IP or DHCP reservation in your router if you'll come back to this.

## 2. Prepare the PC (one time)

1. Download **SDK Platform-Tools** for your OS from
   <https://developer.android.com/tools/releases/platform-tools> and unzip it.
2. Either add that folder to your PATH, or copy everything in it into this
   `tcl-google-tv` folder. The launchers look here for `adb` too.
3. The PC must be on the same network as the TV (not a guest Wi-Fi network).

## 3. Run the steps, in order

Windows (PowerShell, in this folder):

```powershell
powershell -ExecutionPolicy Bypass -File .\run.ps1 192.168.1.50 scan
```

Mac/Linux (terminal, in this folder):

```bash
./run.sh 192.168.1.50 scan
```

Swap `scan` for each step below. The first time you connect, the TV asks
**Allow USB debugging?**: tick **Always allow from this computer** and press OK.
Each step also saves its output to `tvtune-<step>.log` next to the scripts.

1. **`scan`**: changes nothing. Shows the TV model, free RAM and storage, which listed packages
   are on the TV, every other TCL package, and your downloaded apps.
   **Look this over (or paste `tvtune-scan.log` into a Claude session) before `apply`.** Package names vary between
   TCL firmware versions.
2. **`apply`**: the debloat and speed-up:
   - turns off every `x` package in `packages.txt` that's on the TV
   - sets menu animations to 0.5× (snappier, still smooth)
   - clears app caches
   - recompiles installed apps for the TV's CPU (5–15 minutes; leave the TV on)

   Then **restart the TV**: Settings → System → Restart.
3. **`launcher`**: opens **Projectivy Launcher** in the Play Store on the TV. Select **Install**
   and the script waits for it. It then turns on Projectivy's accessibility service, which lets
   Projectivy catch the Home button, and opens it. In Projectivy's settings, turn on the option to
   override the current launcher, then press Home to check.
4. **`replace-home`**: *only if Home still opens Google TV after a restart.* Turns off the stock
   Google TV home screen (`launcherx`) and the setup wizard (`setupwraith`), which also claims
   Home, so Projectivy is the only home screen left. The script refuses to run this until
   Projectivy is installed, because otherwise the TV would boot to a blank screen.

### Make it look like Apple TV

In Projectivy's settings:

- Turn off the channel/recommendation rows so only your apps show.
- Put your apps in the order you want, and hide the ones you never open.
- Pick a plain dark wallpaper.

You end up with one clean row of large app icons and no ads.

### Worth changing by hand

These live in the TV menus; the script can't set them reliably:

- **Apps only mode:** Settings → Accounts & Profiles → your Google account → Apps only mode.
  It removes Google TV's recommendation feed, the biggest background load. It also limits
  voice/Assistant features, so skip it if you rely on voice search.
- **Usage & diagnostics:** Settings → Privacy → Usage & diagnostics → Off.
- **Downloaded apps:** uninstall preloaded apps you don't use (the `scan` step lists them) in
  Settings → Apps. That frees storage on the TV's small built-in drive.

## Choosing what gets turned off

`packages.txt` has three groups:

- **TCL ads, promos, telemetry, store demo:** all `x`.
- **Google extras:** feedback and Play Games are `x`. Play Movies, YouTube Music/Kids, TalkBack,
  Ambient mode and Chromecast are `-`. Change any you don't use to `x`.
- **TCL features you might use:** TCL TV+ channels, USB media player, gallery, screen
  mirroring, game bar. All `-`.

The top of the file lists packages that must never be added: they handle HDMI/antenna inputs,
the remote, Wi-Fi setup, or have been reported to boot-loop TCL TVs. Packages that aren't on
your TV are skipped.

"Turned off" means `pm disable-user`: the app stays installed but can't run, show up, or use
RAM. Nothing is deleted.

## Undo

```bash
./run.sh 192.168.1.50 restore          # Windows: .\run.ps1 192.168.1.50 restore
```

This turns back on everything the script turned off (it keeps its own record on the TV) and
puts animations back to 1×. Then restart the TV. To bring back just one app:
`adb shell pm enable <package>`.

A factory reset (Settings → System → About → Factory reset) also undoes all of it.

## Troubleshooting

- **"Couldn't connect"**: check the IP; turn USB debugging off and on again; restart the TV;
  make sure the PC isn't on a guest network or VPN.
- **"unauthorized" / no prompt on the TV**: on the TV, Developer options → Revoke USB
  debugging authorizations, then connect again and accept the prompt.
- **Something stopped working after `apply`**: run `restore`, restart, then mark the suspect
  package `-` in `packages.txt` and run `apply` again.
- **Blank screen after `replace-home`**: run `restore`; it brings the Google TV home screen back.
