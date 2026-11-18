# NearCams

An Android app that shows **public, operator-published** IP cameras near you —
traffic cams, harbors, ski resorts, city squares, wildlife cams — on a list and a
map, plays their live feeds, and exposes PTZ controls **only** where the operator
has made controls public.

It is scoped to cameras that are meant to be watched. It does **not** scan for or
connect to unsecured/private cameras; a missing password is not permission to view
or control someone's camera.

## What's inside
- **Kotlin + Jetpack Compose**, single-activity, Navigation-Compose
- **Nearby list** sorted by distance from your location
- **Map** view (OpenStreetMap via osmdroid — no API key needed)
- **Detail** screen: HLS/DASH/RTSP via Media3/ExoPlayer, refreshing snapshot cams via Coil
- **PTZ controls** (left/right/up/down/zoom/stop) for feeds whose operator exposes a
  public CGI-style control endpoint (`ptzBaseUrl`). Most feeds are view-only.
- Two data sources: a bundled editable catalog + the optional Windy Webcams API

## Run it
1. Open the folder in **Android Studio** (Koala / 2024.1+). Let it sync Gradle;
   Android Studio will generate the Gradle wrapper JAR if it's missing.
2. Run on a device/emulator with Google Play services (for location). It works
   without granting location — you just won't get distance sorting or GPS discovery.

It runs out of the box from `app/src/main/assets/cameras.json`, which ships with two
public **test streams** so you can confirm playback immediately.

## Add real public cameras
Edit `app/src/main/assets/cameras.json`. Each entry:

```json
{
  "id": "unique-id",
  "title": "Name",
  "location": "City, Country",
  "lat": 42.69, "lon": 23.32,
  "feedType": "STREAM",                 // STREAM (.m3u8/.mpd/rtsp) or IMAGE (still)
  "url": "https://.../stream.m3u8",
  "source": "https://operator-page",    // attribution (optional)
  "ptzBaseUrl": "https://.../cgi-bin/ptz" // optional, only if operator exposes it
}
```

Good sources of genuinely public feeds: government DOT traffic-camera pages, port
authorities, ski resorts, universities, and webcam directories like Windy.

## Optional: live "near me" discovery (Windy)
1. Get a free key at <https://api.windy.com/webcams> (Webcams API).
2. Put it in `gradle.properties`:
   ```
   WINDY_API_KEY=your_key_here
   ```
3. Rebuild. With a key + location permission, the app queries Windy for public
   webcams within ~250 km and merges them with your catalog.

## Notes
- `usesCleartextTraffic` is on because some public cams serve plain HTTP. Tighten it
  with a `network-security-config` if you only use HTTPS feeds.
- The PTZ controller uses a generic `?move=left|right|...` pattern. Real cameras vary
  (ONVIF SOAP, vendor CGI); adapt `PtzController` per operator as needed.
- Only point this at feeds that are published for public viewing.
