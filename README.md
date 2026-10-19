# Signal Radar

Android scanner (built for the Galaxy S24 Ultra, works on Android 12+) that finds Bluetooth and Wi-Fi
devices around you, labels them by manufacturer, shows them on a radar, and flags Flock / ALPR cameras,
hidden cameras, camera glasses, body cams and trackers.

## Install
Open the **Releases** page on your phone, download the newest `SignalRadar-1.N.apk`, and open it.
Every push builds a new numbered release; new versions install over old ones.

## What it does
- **Radar** – live blips for BLE, classic Bluetooth and Wi-Fi devices. Distance comes from signal strength
  (Wi-Fi RTT when the access point supports it). Direction is real for mapped ALPR cameras and for any device
  you do a 360° sweep on; the radar rotates with your compass.
- **Devices** – everything seen, with manufacturer (IEEE MAC registry, Bluetooth SIG company IDs, Wi-Fi WPS
  manufacturer/model), category, RSSI, estimated distance, raw advertising data.
- **Find** – big live RSSI, warmer/colder, Geiger-style beeper, direction sweep, Wi-Fi RTT ranging.
- **Threats**
  - Flock: MAC prefix `B4:1E:52` (Flock Safety), BLE manufacturer `0x09C8` (XUNTONG battery pack), Raven
    services `0x3100–0x3500`, `Flock-…` / `FS Ext Battery` / `Pigvision` names, plus weak community OUIs.
  - Hidden cameras: spy-cam hotspot names (V380, HDWiFiCam, LookCam, iCSee…), camera-maker OUIs, Bluetooth
    camera class, WPS camera models.
  - Camera glasses (Meta / Luxottica / Snap company IDs), body cams (Axon etc.), trackers (AirTag away from owner,
    Tile, SmartTag, Google Find Hub).
- **Tools**
  - *Scan this Wi-Fi for cameras*: ONVIF WS-Discovery, SSDP, and a port sweep (RTSP, Dahua 37777, XMeye 34567…)
    with banner grabbing.
  - *ALPR map*: license-plate cameras mapped by DeFlock / OpenStreetMap near you, shown on the radar with live
    distance and bearing.
  - *Lens finder*: flashlight + glint filter camera view; front camera for IR LEDs.

## Limits (phone hardware)
- Phones can't put Wi-Fi in monitor mode, so they can't see Wi-Fi *clients* or probe requests — only access points
  and Bluetooth advertisers. Newer Flock firmware is mostly radio-silent, which is why the ALPR map is included.
- Android allows 4 Wi-Fi scans per 2 minutes per app. Turn off *Developer options → Wi-Fi scan throttling* for faster
  Wi-Fi updates.
- Location must be on for Android to return scan results.

## Build
GitHub Actions builds `app-debug.apk` on every push (`.github/workflows/build.yml`) and publishes a release.
Vendor tables in `app/src/main/assets` are regenerated with `python3 scripts/gen_vendor_data.py`.
