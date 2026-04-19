# beat-link C++ API

This directory contains a **C++17 client library** that gives your C++ project
full access to the [beat-link](https://github.com/Deep-Symmetry/beat-link)
Pioneer DJ Link library through a **gRPC interface**.

## Architecture overview

```
┌──────────────────────────────────────────────┐
│  Your C++ application                         │
│                                               │
│  BeatLinkClient   DeviceMonitor               │
│  BeatMonitor      PlayerMonitor               │
│  MetadataClient   (beat_link_client.h)        │
└────────────────────┬─────────────────────────┘
                     │ gRPC / TCP (localhost:50051)
┌────────────────────▼─────────────────────────┐
│  beat-link gRPC Server (JVM process)          │
│                                               │
│  DeviceServiceImpl   BeatServiceImpl          │
│  PlayerStatusServiceImpl MetadataServiceImpl  │
│  (beat-link-grpc/  Maven module)              │
│                                               │
│  ↕  uses the core beat-link Java library      │
│                                               │
│  DeviceFinder  VirtualCdj  BeatFinder         │
│  MetadataFinder (src/main/java/…)             │
└──────────────────────────────────────────────┘
             │ UDP/TCP  Pioneer DJ Link protocol
       ┌─────┴──────────────────────┐
       │   CDJ-3000  DJM-900NXS2 …  │
       └────────────────────────────┘
```

The Java server runs as a **sidecar process** alongside your C++ binary.
Communication between the two is over **localhost gRPC (port 50051 by default)** –
the latency is typically < 1 ms.

---

## Quick start

### Prerequisites

| Tool | Purpose |
|------|---------|
| Java 11+ JDK | Run the gRPC server |
| Maven 3.6+   | Build the gRPC server JAR |
| CMake 3.16+  | Build the C++ client |
| `protoc` + `grpc_cpp_plugin` | Compile `.proto` → C++ (see below) |

#### Installing gRPC for C++

**macOS (Homebrew)**
```bash
brew install grpc
```

**Ubuntu / Debian**
```bash
sudo apt install -y libgrpc++-dev libprotobuf-dev protobuf-compiler-grpc
```

**From source** (all platforms) – follow the
[gRPC C++ quick-start guide](https://grpc.io/docs/languages/cpp/quickstart/).

---

### Step 1 – Build the beat-link core library

```bash
# from the repository root
mvn install -DskipTests
```

### Step 2 – Build the gRPC server fat JAR

```bash
cd beat-link-grpc
mvn package -DskipTests
cd ..
```

This produces `beat-link-grpc/target/beat-link-grpc-server.jar`.

### Step 3 – Build the C++ client library and example

```bash
cd cpp
cmake -B build .
cmake --build build
```

### Step 4 – Start the gRPC server

```bash
# Linux / macOS
./start-server.sh           # uses port 50051 by default
./start-server.sh 50055     # custom port

# Windows
start-server.bat
start-server.bat 50055
```

You should see output like:
```
Starting DJ Link components...
DeviceFinder started – listening for device announcements on port 50000
VirtualCdj started – receiving player status updates on port 50002
BeatFinder started – listening for beat packets on port 50001
MetadataFinder started – track metadata queries are available
beat-link gRPC server listening on port 50051
```

### Step 5 – Run the example

```bash
./build/example/beat_link_example
# or with a custom server address:
./build/example/beat_link_example 192.168.1.10:50051
```

---

## API reference

All types are declared in [`beat_link_client.h`](beat_link_client.h).
The protobuf message types are in [`../proto/beat_link.proto`](../proto/beat_link.proto).

### `BeatLinkClient`

The connection manager.  Create **one** per server address and share it.

```cpp
auto client = std::make_shared<BeatLinkClient>("localhost:50051");
```

### `DeviceMonitor`

```cpp
DeviceMonitor dm(client);

// Snapshot – returns std::vector<beatlink::DeviceInfo>
auto devices = dm.listDevices();

// Live stream
dm.start([](const beatlink::DeviceInfo& d) {
    std::cout << d.name() << " #" << d.number() << " @ " << d.address() << "\n";
});
// ...
dm.stop();
```

### `BeatMonitor`

```cpp
BeatMonitor bm(client);
bm.start([](const beatlink::BeatEvent& b) {
    std::cout << "Beat from player " << b.device_number()
              << " beat-in-bar=" << b.beat_within_bar()
              << " @ " << b.effective_bpm() << " BPM\n";
});
// ...
bm.stop();
```

### `PlayerMonitor`

```cpp
PlayerMonitor pm(client);
pm.start([](const beatlink::PlayerStatus& s) {
    if (s.is_playing()) {
        std::cout << "Player " << s.device_number()
                  << " playing @ " << s.effective_bpm() << " BPM"
                  << (s.is_tempo_master() ? " [master]" : "") << "\n";
    }
});
// ...
pm.stop();
```

### `MetadataClient`

```cpp
MetadataClient mc(client);

// Get track metadata (blocking)
auto meta = mc.getTrackMetadata(
    /*sourcePlayer=*/ 1,
    /*sourceSlot=*/   "USB_SLOT",
    /*rekordboxId=*/  12345);

std::cout << meta.title() << " – " << meta.artist() << "\n";

// Get cue list
auto cues = mc.getCueList(1, "USB_SLOT", 12345);
for (auto& c : cues) {
    std::cout << "@" << c.position_ms() << "ms";
    if (c.hot_cue_number() > 0)
        std::cout << " [hot-cue " << (char)('A' + c.hot_cue_number() - 1) << "]";
    std::cout << "\n";
}
```

---

## gRPC services

| Service | RPC | Description |
|---------|-----|-------------|
| `DeviceService` | `ListDevices` | Snapshot of all currently known DJ Link devices |
| `DeviceService` | `StreamDeviceAnnouncements` | Server-streaming: new device arrivals |
| `BeatService` | `StreamBeats` | Server-streaming: beat events from all players |
| `PlayerStatusService` | `StreamPlayerStatus` | Server-streaming: CDJ status packets |
| `MetadataService` | `GetTrackMetadata` | Rekordbox metadata for a track |
| `MetadataService` | `GetCueList` | Cue / hot-cue / loop list for a track |

See [`../proto/beat_link.proto`](../proto/beat_link.proto) for the full message and
service definitions.

---

## Integrating into your own CMake project

```cmake
# In your CMakeLists.txt, after adding this repo as a subdirectory (or installing it):
add_subdirectory(path/to/beat-link-cc/cpp)

target_link_libraries(your_target PRIVATE beat_link_client)
target_include_directories(your_target PRIVATE path/to/beat-link-cc/cpp)
```

Or, if you only want the generated proto stubs and prefer to manage gRPC yourself,
copy `../proto/beat_link.proto` into your project and run `protoc` directly.
