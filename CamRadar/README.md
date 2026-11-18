# CamRadar

CamRadar is a native Android app (Kotlin, Jetpack Compose, MVVM, Room, coroutines, OpenStreetMap via osmdroid) that shows camera infrastructure around you from two separate sources:

1. **Geographic discovery.** Cameras with a documented physical position, taken from the OpenStreetMap surveillance registry (`man_made=surveillance`) or from a registry CSV you import.
2. **Authorized local-network discovery.** IP cameras on a private network that you have explicitly authorized. They are found with ONVIF WS-Discovery, UPnP/SSDP, RTSP and HTTP identification.

GPS coordinates on their own do **not** reveal the IP addresses of nearby cameras. CamRadar never presents IP geolocation as a camera's physical position.

## What needs what

| Feature | Internet | Real dataset | Authorized LAN | Location permission |
|---|---|---|---|---|
| Map tiles | ✅ (tiles are cached afterwards) | – | – | – |
| Public registry (OSM Overpass) | ✅ opt-in | OpenStreetMap contributors' data | – | Optional (otherwise the map centre is used) |
| Imported registry CSV | – | Your own file (for example municipal open data) | – | Optional |
| Local discovery (ONVIF/SSDP/RTSP/HTTP) | – | – | ✅ Wi-Fi/Ethernet, private subnet ≤ /22, authorized by you | Needed on Android to read the SSID |
| Credential verification | – | – | ✅ | – |
| Cached inventory / offline mode | – | Data stored earlier | – | – |
| Simulated GPS (emulator) | – | – | – | – |

OSM coverage of cameras varies a lot between cities, and many installations aren't mapped at all. An empty result means "nothing mapped here", not "no cameras here".

## Build and install

Requirements: Android Studio Ladybug (2024.2) or newer, JDK 17, Android SDK 35.

```bash
# Open the folder in Android Studio and let it sync. Studio creates the Gradle wrapper jar.
# Or from a shell that has Gradle 8.10+ installed:
gradle wrapper --gradle-version 8.10.2
./gradlew :app:assembleDebug
./gradlew :app:installDebug          # device or emulator connected
```

No API keys are needed. Map tiles come from the standard OSM tile servers; follow the OSM tile usage policy if you distribute the app widely.

## Tests

```bash
./gradlew :core:test                 # 63 JVM tests: parsers, classifier, guard, engine, timeouts, offline, GPS simulation
./gradlew :app:testDebugUnitTest     # entity mapping and source grouping
./gradlew :app:connectedDebugAndroidTest   # emulator: Room, permission UI, end-to-end flow
```

None of the tests need a physical camera or a real network.
- The engine tests use a scripted in-memory `FakeTransport`. They assert that no host outside the authorized subnet is ever contacted, and that discovery never sends SETUP/PLAY requests or credentials.
- The timeout tests use `127.0.0.1` sockets only.
- The emulator test uses a simulated location, a fake registry, an in-memory database and a transport that drops all traffic.

| Requirement | Where |
|---|---|
| Unit tests | `core/src/test`, `app/src/test` |
| Location permission tests | `PermissionRationaleTest`, `EmulatorIntegrationTest` (GrantPermissionRule) |
| GPS simulation | `OfflineAndGpsTest.simulatedWalkChangesNearbySet`, `EmulatorIntegrationTest` |
| Discovery parser tests | `ProtocolParsersTest`, `GeoSourcesTest` |
| Authentication classification | `AuthClassifierTest`, `LocalDiscoveryEngineTest` |
| Network timeouts | `NetworkTimeoutTest`, `LocalDiscoveryEngineTest.stopCancelsDiscovery` |
| Offline mode | `OfflineAndGpsTest`, `EmulatorIntegrationTest.offlineModeKeepsWorking` |
| Emulator integration | `EmulatorIntegrationTest`, `CameraDaoTest` |

## Architecture

```
core/  (pure Kotlin, no Android, fully unit-tested)
  geo/        LatLon, haversine distance, SearchRadius (100 m–5 km), AccuracyLevel,
              OverpassSource + parser, RegistryCsvParser, LocationSource, SimulatedLocationSource
  net/        IPv4 subnet maths, RFC 1918 checks
  discovery/  NetworkGuard, ONVIF/SSDP/RTSP/HTTP protocol code, Fingerprinter,
              AuthClassifier, NetTransport/SocketTransport, LocalDiscoveryEngine
  service/    GeoDiscoveryService (online refresh with cache fallback, radius filtering)
app/   (Android)
  data/       Room entities/DAOs (cameras, authorized_networks), CameraRepository, SettingsStore
  location/   FusedLocationSource (foreground only, mock-fix detection)
  net/        AndroidNetworkInfo (active network, SSID/BSSID, prefix), multicast lock
  di/         AppContainer (manual DI, test overrides)
  ui/         MainViewModel (MVVM state), Map / Cameras / Local network / Settings screens, detail sheet
```

## Discovery engine: what it actually sends

All of the following happens only after `NetworkGuard` allows it, and only to addresses inside the authorized subnet. Each target is checked again before it is contacted.

1. An ONVIF WS-Discovery `Probe` for `NetworkVideoTransmitter`, sent to multicast 239.255.255.250:3702.
2. An SSDP `M-SEARCH` (239.255.255.250:1900). The UPnP description XML is then fetched, but only if its `LOCATION` is inside the subnet.
3. TCP connects to ports 554, 8554, 80, 8080, 8000 and 443 on each host, 32 hosts at a time, with a 400 ms timeout.
4. RTSP `OPTIONS` and `DESCRIBE` without credentials. `DESCRIBE` returns only the SDP text. **No SETUP/PLAY is sent, so no media is ever received.**
5. HTTP `GET /` to read the Server header, page title and any authentication challenge.
6. ONVIF `GetDeviceInformation` to read the manufacturer, model and firmware.

Hosts that don't look like cameras (a router, for example) are thrown away and not stored. The **Stop discovery** button cancels the coroutine, and the socket timeouts make sure it stops within about a second.

### Authentication classification

| Result | When |
|---|---|
| Authentication required | RTSP `DESCRIBE` → 401, ONVIF `NotAuthorized`, or (for the web UI only) HTTP 401 |
| No authentication detected through approved checks | RTSP `DESCRIBE` returns an SDP without credentials, or ONVIF device info is readable without credentials (the stream itself isn't tested) |
| Authentication status unknown | Nothing conclusive. **A reachable HTTP login page stays "unknown"**, because a web interface is not a video stream |
| Verified using your credentials | You typed credentials for your own device and they were accepted |

Each result also records the **surface that was checked**: stream, device service, or web interface only.

Credential verification sends **exactly one** authenticated attempt per protocol, using what you typed (RTSP Digest/Basic, ONVIF WS-UsernameToken). Credentials are never stored, logged or retried. There is no dictionary, no default-password list and no bypass logic.

## Location accuracy on the map

- **● solid marker**: exact registered coordinates (an OSM node, or a CSV row marked `exact`).
- **◌ dashed ring**: approximate. This covers an OSM way centroid, a CSV row marked `approximate`/`ip_geolocation`, or a LAN camera placed at *your* position when it was found. Distances for these show as `≈`.
- The blue dot and circle show your GPS fix and its accuracy radius, also labelled High/Medium/Low/Poor. Simulated fixes are labelled **SIMULATED**.

## Privacy and safety

- Location is requested with an explanation first. Only *while-in-use* location is used: `ACCESS_BACKGROUND_LOCATION` isn't declared, and updates stop in `onStop`.
- The camera inventory stays on the device. There's no upload path, and the database is excluded from cloud backup and device transfer.
- The public registry is off by default. When you turn it on, only the search area is sent to the Overpass API.
- CamRadar never opens camera video, never guesses passwords and never scans cellular, VPN, public or unauthorized networks.
- Revoking a network also deletes every camera found on it.

### Registry CSV format

```
id,name,lat,lon,manufacturer,model,category,precision
a1,"Main St / 5th",42.6981,23.3222,Axis,P1455,TRAFFIC,exact
```
`category` is one of `PUBLIC_SURVEILLANCE, TRAFFIC, PRIVATE_SURVEILLANCE, WEBCAM, LOCAL_NETWORK, OTHER`. `precision` is `exact | approximate | ip_geolocation`.

## Known limitations

- Android 10+ hides the SSID unless location permission is granted. Without it, the network shows up as "no SSID" and you can authorize it by subnet only.
- Discovery covers IPv4 only, and only subnets of /22 or smaller (at most about 1,022 hosts).
- The RTSP check probes the root path `rtsp://ip:port/`. Some cameras answer 404 there, so the result stays *unknown* rather than trying other paths.
- HTTPS (443) is only recorded as open. Its TLS web UI isn't fingerprinted.
