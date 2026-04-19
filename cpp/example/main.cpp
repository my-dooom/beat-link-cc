/**
 * @file main.cpp
 * @brief Example demonstrating beat-link C++ API via gRPC.
 *
 * Prerequisites
 * ─────────────
 * 1. Build and install beat-link:
 *      cd /path/to/beat-link-cc  &&  mvn install -DskipTests
 *
 * 2. Build the gRPC server fat-JAR:
 *      cd beat-link-grpc  &&  mvn package -DskipTests
 *
 * 3. Start the server (default port 50051):
 *      ./start-server.sh          # or start-server.bat on Windows
 *
 * 4. Build this example (from the cpp/ directory):
 *      cmake -B build .  &&  cmake --build build
 *
 * 5. Run:
 *      ./build/example/beat_link_example [host:port]
 */

#include <chrono>
#include <csignal>
#include <iomanip>
#include <iostream>
#include <string>
#include <thread>

#include "beat_link_client.h"

// ─── Signal handling ──────────────────────────────────────────────────────────

static std::atomic<bool> g_running{true};

static void sigHandler(int /*sig*/) {
    g_running = false;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

static std::string slot_emoji(const std::string& slot) {
    if (slot == "USB_SLOT") return "💾 USB";
    if (slot == "SD_SLOT")  return "💿 SD";
    if (slot == "CD_SLOT")  return "📀 CD";
    if (slot == "COLLECTION") return "🖥️ Collection";
    return slot;
}

// ─── main ─────────────────────────────────────────────────────────────────────

int main(int argc, char* argv[]) {
    std::string address = "localhost:50051";
    if (argc > 1) {
        address = argv[1];
    }

    std::cout << "Connecting to beat-link gRPC server at " << address << "\n\n";

    std::signal(SIGINT,  sigHandler);
    std::signal(SIGTERM, sigHandler);

    auto client = std::make_shared<BeatLinkClient>(address);

    // ── 1. Snapshot – print all currently known devices ──────────────────────
    {
        DeviceMonitor dm(client);
        try {
            auto devices = dm.listDevices();
            std::cout << "═══ Currently known DJ Link devices (" << devices.size() << ") ═══\n";
            for (const auto& d : devices) {
                std::cout << "  • " << std::setw(12) << std::left << d.name()
                          << "  device #" << d.number()
                          << "  @ " << d.address()
                          << (d.is_opus_quad() ? "  [Opus Quad]" : "")
                          << (d.is_xdj_az()    ? "  [XDJ-AZ]"   : "")
                          << "\n";
            }
        } catch (const std::exception& e) {
            std::cerr << "  ⚠  ListDevices failed: " << e.what() << "\n";
        }
        std::cout << "\n";
    }

    // ── 2. Stream device announcements ───────────────────────────────────────
    DeviceMonitor dm(client);
    dm.start([](const beatlink::DeviceInfo& d) {
        std::cout << "[Device] " << d.name()
                  << " #" << d.number()
                  << " @ " << d.address()
                  << "  (peers: " << d.peer_count() << ")\n";
    });

    // ── 3. Stream beat events ─────────────────────────────────────────────────
    BeatMonitor bm(client);
    bm.start([](const beatlink::BeatEvent& b) {
        std::cout << "[Beat ] player=" << b.device_number()
                  << "  beat_in_bar=" << b.beat_within_bar()
                  << "  BPM=" << std::fixed << std::setprecision(2) << b.effective_bpm()
                  << (b.is_tempo_master() ? "  ★ MASTER" : "")
                  << "\n";
    });

    // ── 4. Stream CDJ player status ───────────────────────────────────────────
    PlayerMonitor pm(client);
    pm.start([](const beatlink::PlayerStatus& s) {
        if (!s.is_playing()) return;   // Only print while playing
        std::cout << "[Status] player=" << s.device_number()
                  << "  state=" << s.play_state()
                  << "  BPM=" << std::fixed << std::setprecision(2) << s.effective_bpm()
                  << "  track=" << s.rekordbox_id()
                  << "  slot=" << slot_emoji(s.track_source_slot())
                  << (s.is_synced()       ? "  [sync]"    : "")
                  << (s.is_tempo_master() ? "  [master]"  : "")
                  << (s.is_on_air()       ? "  [on-air]"  : "")
                  << "\n";
    });

    // ── 5. On first beat – fetch metadata example ─────────────────────────────
    MetadataClient mc(client);
    std::atomic<bool> fetched_meta{false};

    // Re-use the beat monitor callback closure to trigger a one-shot metadata fetch.
    PlayerMonitor pm2(client);
    pm2.start([&mc, &fetched_meta](const beatlink::PlayerStatus& s) {
        if (fetched_meta || s.rekordbox_id() == 0 || s.track_source_slot() == "NO_TRACK") {
            return;
        }
        if (!fetched_meta.exchange(true)) {
            try {
                auto meta = mc.getTrackMetadata(s.track_source_player(),
                                                s.track_source_slot(),
                                                s.rekordbox_id());
                std::cout << "\n═══ Track metadata for rekordbox ID " << s.rekordbox_id() << " ═══\n"
                          << "  Title   : " << meta.title()  << "\n"
                          << "  Artist  : " << meta.artist() << "\n"
                          << "  Album   : " << meta.album()  << "\n"
                          << "  BPM     : " << (meta.tempo() / 100.0) << "\n"
                          << "  Duration: " << meta.duration_secs() << "s\n"
                          << "  Key     : " << meta.key()    << "\n"
                          << "  Genre   : " << meta.genre()  << "\n";

                auto cues = mc.getCueList(s.track_source_player(),
                                          s.track_source_slot(),
                                          s.rekordbox_id());
                std::cout << "  Cues    : " << cues.size() << " entries\n";
                for (const auto& c : cues) {
                    std::cout << "    @" << c.position_ms() << "ms";
                    if (c.hot_cue_number() > 0)
                        std::cout << "  hot-cue " << static_cast<char>('A' + c.hot_cue_number() - 1);
                    if (c.is_loop())
                        std::cout << "  [loop until " << c.loop_end_ms() << "ms]";
                    if (!c.comment().empty())
                        std::cout << "  \"" << c.comment() << "\"";
                    std::cout << "\n";
                }
                std::cout << "\n";
            } catch (const std::exception& e) {
                std::cerr << "  ⚠  Metadata fetch failed: " << e.what() << "\n";
                fetched_meta = false;  // allow retry
            }
        }
    });

    // ── Run until Ctrl-C ──────────────────────────────────────────────────────
    std::cout << "Monitoring the DJ Link network (press Ctrl-C to quit)...\n\n";
    while (g_running) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    std::cout << "\nStopping monitors...\n";
    pm2.stop();
    pm.stop();
    bm.stop();
    dm.stop();
    std::cout << "Done.\n";
    return 0;
}
