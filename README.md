# One Launcher — for Philips PUS8558 (Google TV)

A tiny, fast home screen for the TV remote, styled after Apple TV's home screen (original design, no Apple assets):

- **Glass widgets** that turn into a white panel when selected
- **File Transfer** — built-in FTP server: select to switch on, hold OK for a new password
- **Memory** — live usage bar (turns orange above 80 %); select to clean RAM
- **Weather + clock** top-right — current conditions, high/low and your city; select it to open **Customize**
- **Wallpapers** — five built-in looks or your own photos, with optional automatic rotation
- **Favourites** — starts with Stremio, SmartTube, HBO Max; add, remove and reorder any app (a "Get" badge opens the Play Store if one isn't installed)
- **Inputs** — every HDMI port the TV reports, renamable any time (PC, STB, Xbox 360, Wii…) with a matching icon, plus Live TV and Settings
- **All Apps** — everything installed, alphabetical
- Tiles lift with a soft sheen when selected; the name appears underneath
- Hold OK on an app = Favourites menu · hold OK on an input = rename · MENU = Customize · Home while home = back to the first tile

No libraries, plain Android framework, a few hundred KB.

---

## 1. Build the APK

**Option A — GitHub (no Android Studio needed)**
1. Create a new GitHub repo and upload this folder's contents.
2. Go to **Actions → Build APK → Run workflow** (it also runs on every push).
3. When it finishes, download **OneLauncher-apk** from the run page and unzip it.

**Option B — Android Studio**
Open the folder, let it sync, then *Build → Build APK(s)*.

## 2. Install on the TV

On the TV: *Settings → System → About → Android TV OS build* — press OK 7 times to enable
Developer options, then turn on **USB debugging / Network debugging**.

From a PC on the same network (with `adb` installed):

```
adb connect <TV-IP-address>
adb install -r app-release.apk
```

## 3. Make it the home screen

Google TV doesn't let you pick another home app in Settings, so turn off the stock
launcher with ADB. After that, pressing **Home** opens One Launcher.

```
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

If the TV asks which home app to use, choose **One Launcher → Always**.

**To undo (restore Google TV home):**
```
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```

> With the stock launcher disabled, Google TV's recommendations and profile switcher won't
> show. Settings, voice search and Play Store keep working. A big firmware update may
> re-enable the stock launcher; just run the commands again.

## 4. File Transfer (FTP)

1. Select the **File Transfer** card. It shows the address, user and password, e.g.
   `192.168.1.23:2121 · tv / 482913`.
2. On your PC use **FileZilla** (or Windows Explorer: `ftp://192.168.1.23:2121`); on a phone
   any FTP app works. Use **passive mode** (the default almost everywhere), user `tv`,
   and the password shown.
3. Drag files across. **Send an `.apk` and the TV offers to install it** straight away.

Details:
- The password stays the same so saved FileZilla sites keep working. Hold OK on the card for a new one.
- The server turns itself off after 1 hour with no activity.
- Only works on your home network; nothing is reachable from the internet.

**Which folder is shared?** Out of the box only the launcher's own folder is shared (Android's
rule). To share the TV's whole internal storage (Download, Movies, etc.) run once:

```
adb shell appops set --uid com.marcus.onelauncher MANAGE_EXTERNAL_STORAGE allow
```

The card then shows the full storage. **To allow installing APKs** sent over FTP, accept the
prompt the first time, or run:

```
adb shell appops set com.marcus.onelauncher REQUEST_INSTALL_PACKAGES allow
```

## 5. Favourites

- **Add:** hold OK on any app in *All Apps* → **Add to Favourites**.
- **Remove / reorder:** hold OK on a favourite → **Move left**, **Move right** or **Remove from Favourites**.
- **Many at once:** Customize → **Edit favourites** gives a tick-list of every app.

If you remove them all, the row shows an **Add favourites** tile.

## 6. Input names

Hold OK on an HDMI tile (or Customize → **Rename inputs**) and pick a quick name — PC, STB,
Xbox 360, Xbox Series X, PlayStation 5/4, Wii, Nintendo Switch, Apple TV, Chromecast, Fire TV,
Blu-ray, Soundbar, Laptop, Camera — or **Type a name…** for anything else. Rename again any time;
**Reset** brings back the TV's own name.

The icon follows the name: consoles get a controller, PC/laptop/Mac a monitor, STB/satellite/
streaming boxes a set-top box; anything else keeps the HDMI plug.

## 7. Wallpapers

Select the **weather/clock** block (or press MENU) → **Customize → Wallpaper**. Moving across the
thumbnails previews each wallpaper live behind the menu; OK keeps it, Back puts the old one back.

**Your own photos (JPG, PNG or WebP):**
1. Turn on **File Transfer** and connect with FileZilla.
2. Drop photos into the **Wallpapers** folder you'll see there.
3. The TV asks "Use it as your wallpaper?" straight away, and the photo also appears in the picker.

Landscape 1920×1080 or larger looks best; big photos are shrunk to screen size automatically so
they don't eat memory. Photo wallpapers get a gentle dark overlay so the text stays readable.

**From a USB stick:** put a folder called `Wallpapers` at the top of the stick. This needs the
all-files access command from the File Transfer section (Android blocks USB access otherwise).

**Change automatically:** Customize → *Change wallpaper automatically* → every hour, 6 hours or
day. It cycles through your own photos (or the built-in looks if you haven't added any).

## 8. Weather

Shown beside the clock: icon, temperature, today's high/low, condition and city. It updates
every 30 minutes and keeps showing the last reading for up to 6 hours if the internet drops.

In **Customize** you can:
- **Weather location:** *Automatic* works out your city from the TV's internet connection;
  *Choose a city…* lets you type one (the remote's keyboard or voice typing both work).
- **Temperature:** switch °C / °F.
- **Show weather:** turn it off entirely.

Data comes from [Open-Meteo](https://open-meteo.com) (free, no account). Automatic location
uses ipwho.is (falling back to geojs.io), which see only the TV's public IP address.

## Customising

- **Default favourites** (before you change them on the TV): `FAVOURITES` in `Apps.kt`.
- **Quick input names:** `InputLabels.SUGGESTIONS` in `Favourites.kt`.
- **Built-in wallpapers:** `res/drawable/wallpaper.xml` (Midnight) and `wp_*.xml`; list in `Wallpapers.kt`.
- **Look:** `card_bg.xml`, `tile_bg.xml`, `tile_fg.xml`; weather icons are `wx_*.xml`.

## About "Clean RAM"

It stops background and cached app processes (`killBackgroundProcesses`). Android
deliberately protects whatever is playing, the foreground app and core system services,
so it frees what's safe to free. Expect a few hundred MB on a busy TV; the system refills
some of it over time, which is normal.

## Android Studio: "Incompatible Gradle JVM version"

Newer Android Studio runs Gradle on Java 25, which this project's Gradle (8.10.2) doesn't support.
Click **Apply compatible Gradle JDK configuration and sync** in that message. If it's gone:
*Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK* → pick
**jbr-21** (Embedded JetBrains Runtime) or any JDK 17–21, then *File → Sync Project with Gradle Files*.

## If HDMI tiles don't appear or don't switch

Some Philips firmware hides inputs from third-party apps. If the Inputs row only shows
Live TV and Settings, use the **Sources** button on the remote, or report what the row
shows and the TV's software version so a Philips-specific fallback can be added.
