#pragma once

/**
 * @file beat_link_client.h
 * @brief Idiomatic C++17 client for the beat-link gRPC server.
 *
 * This header provides four lightweight wrapper classes that hide the
 * generated gRPC stub types behind a clean, callback-based API:
 *
 *  - BeatLinkClient  – connection manager (create one per server address)
 *  - DeviceMonitor   – snapshot + live stream of DJ Link devices
 *  - BeatMonitor     – live stream of beat events
 *  - PlayerMonitor   – live stream of CDJ status updates
 *  - MetadataClient  – synchronous track metadata and cue list retrieval
 *
 * ### Quick-start
 * @code
 *   auto client = std::make_shared<BeatLinkClient>("localhost:50051");
 *
 *   // Beat events
 *   BeatMonitor beats(client);
 *   beats.start([](const beatlink::BeatEvent& b) {
 *       std::cout << "Beat from player " << b.device_number()
 *                 << " @ " << b.effective_bpm() << " BPM\n";
 *   });
 *
 *   // Device list (blocking snapshot)
 *   DeviceMonitor devices(client);
 *   for (auto& d : devices.listDevices())
 *       std::cout << "Device: " << d.name() << " #" << d.number() << "\n";
 *
 *   std::this_thread::sleep_for(std::chrono::seconds(60));
 *   beats.stop();
 * @endcode
 */

#include <functional>
#include <memory>
#include <string>
#include <thread>
#include <vector>
#include <atomic>
#include <stdexcept>

#include <grpcpp/grpcpp.h>

// Generated headers (produced by protobuf-maven-plugin / protoc at build time)
#include "beat_link.pb.h"
#include "beat_link.grpc.pb.h"

// ─── BeatLinkClient ───────────────────────────────────────────────────────────

/**
 * Manages the gRPC channel to a beat-link gRPC server.
 *
 * A single BeatLinkClient can be shared by multiple Monitor objects.
 * The channel is created lazily on first use and is kept open for the
 * lifetime of the BeatLinkClient instance.
 */
class BeatLinkClient {
public:
    /**
     * Creates a client connected to the given server address.
     *
     * @param address  Host and port, e.g. "localhost:50051".
     * @param creds    gRPC credentials; defaults to insecure (plain-text).
     */
    explicit BeatLinkClient(
        const std::string& address,
        std::shared_ptr<grpc::ChannelCredentials> creds = grpc::InsecureChannelCredentials())
        : channel_(grpc::CreateChannel(address, creds)) {}

    /** Returns the shared gRPC channel (creates it on first call). */
    std::shared_ptr<grpc::Channel> channel() const { return channel_; }

private:
    std::shared_ptr<grpc::Channel> channel_;
};

// ─── DeviceMonitor ─────────────────────────────────────────────────────────────

/**
 * Provides device-discovery functionality backed by the DeviceService gRPC service.
 */
class DeviceMonitor {
public:
    using DeviceCallback = std::function<void(const beatlink::DeviceInfo&)>;

    explicit DeviceMonitor(std::shared_ptr<BeatLinkClient> client)
        : stub_(beatlink::DeviceService::NewStub(client->channel())) {}

    /**
     * Blocking call that returns all currently known devices.
     *
     * @throws std::runtime_error on gRPC failure.
     */
    std::vector<beatlink::DeviceInfo> listDevices() {
        beatlink::ListDevicesRequest req;
        beatlink::ListDevicesResponse resp;
        grpc::ClientContext ctx;

        grpc::Status status = stub_->ListDevices(&ctx, req, &resp);
        if (!status.ok()) {
            throw std::runtime_error("ListDevices failed: " + status.error_message());
        }

        std::vector<beatlink::DeviceInfo> result;
        result.reserve(resp.devices_size());
        for (const auto& d : resp.devices()) {
            result.push_back(d);
        }
        return result;
    }

    /**
     * Starts a background thread that calls @p cb for every device announcement.
     *
     * The callback is invoked on the background thread; ensure it is thread-safe.
     * Call stop() to terminate the stream.
     *
     * @throws std::runtime_error if already running.
     */
    void start(DeviceCallback cb) {
        if (running_.exchange(true)) {
            throw std::runtime_error("DeviceMonitor is already running");
        }
        thread_ = std::thread([this, cb = std::move(cb)]() {
            beatlink::StreamRequest req;
            grpc::ClientContext ctx;
            ctx_ptr_ = &ctx;

            auto reader = stub_->StreamDeviceAnnouncements(&ctx, req);
            beatlink::DeviceInfo info;
            while (running_ && reader->Read(&info)) {
                cb(info);
            }
            reader->Finish();
            running_ = false;
        });
    }

    /**
     * Stops the background streaming thread and blocks until it has exited.
     */
    void stop() {
        running_ = false;
        if (ctx_ptr_) {
            ctx_ptr_->TryCancel();
            ctx_ptr_ = nullptr;
        }
        if (thread_.joinable()) {
            thread_.join();
        }
    }

    ~DeviceMonitor() { stop(); }

private:
    std::unique_ptr<beatlink::DeviceService::Stub> stub_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    grpc::ClientContext* ctx_ptr_{nullptr};
};

// ─── BeatMonitor ──────────────────────────────────────────────────────────────

/**
 * Streams beat events from all DJ Link devices via the BeatService gRPC service.
 */
class BeatMonitor {
public:
    using BeatCallback = std::function<void(const beatlink::BeatEvent&)>;

    explicit BeatMonitor(std::shared_ptr<BeatLinkClient> client)
        : stub_(beatlink::BeatService::NewStub(client->channel())) {}

    /**
     * Starts a background thread that calls @p cb for every beat event.
     *
     * @throws std::runtime_error if already running.
     */
    void start(BeatCallback cb) {
        if (running_.exchange(true)) {
            throw std::runtime_error("BeatMonitor is already running");
        }
        thread_ = std::thread([this, cb = std::move(cb)]() {
            beatlink::StreamRequest req;
            grpc::ClientContext ctx;
            ctx_ptr_ = &ctx;

            auto reader = stub_->StreamBeats(&ctx, req);
            beatlink::BeatEvent event;
            while (running_ && reader->Read(&event)) {
                cb(event);
            }
            reader->Finish();
            running_ = false;
        });
    }

    /**
     * Stops the background streaming thread and blocks until it has exited.
     */
    void stop() {
        running_ = false;
        if (ctx_ptr_) {
            ctx_ptr_->TryCancel();
            ctx_ptr_ = nullptr;
        }
        if (thread_.joinable()) {
            thread_.join();
        }
    }

    ~BeatMonitor() { stop(); }

private:
    std::unique_ptr<beatlink::BeatService::Stub> stub_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    grpc::ClientContext* ctx_ptr_{nullptr};
};

// ─── PlayerMonitor ────────────────────────────────────────────────────────────

/**
 * Streams CDJ status updates via the PlayerStatusService gRPC service.
 */
class PlayerMonitor {
public:
    using StatusCallback = std::function<void(const beatlink::PlayerStatus&)>;

    explicit PlayerMonitor(std::shared_ptr<BeatLinkClient> client)
        : stub_(beatlink::PlayerStatusService::NewStub(client->channel())) {}

    /**
     * Starts a background thread that calls @p cb for every CDJ status update.
     *
     * @throws std::runtime_error if already running.
     */
    void start(StatusCallback cb) {
        if (running_.exchange(true)) {
            throw std::runtime_error("PlayerMonitor is already running");
        }
        thread_ = std::thread([this, cb = std::move(cb)]() {
            beatlink::StreamRequest req;
            grpc::ClientContext ctx;
            ctx_ptr_ = &ctx;

            auto reader = stub_->StreamPlayerStatus(&ctx, req);
            beatlink::PlayerStatus status;
            while (running_ && reader->Read(&status)) {
                cb(status);
            }
            reader->Finish();
            running_ = false;
        });
    }

    /**
     * Stops the background streaming thread and blocks until it has exited.
     */
    void stop() {
        running_ = false;
        if (ctx_ptr_) {
            ctx_ptr_->TryCancel();
            ctx_ptr_ = nullptr;
        }
        if (thread_.joinable()) {
            thread_.join();
        }
    }

    ~PlayerMonitor() { stop(); }

private:
    std::unique_ptr<beatlink::PlayerStatusService::Stub> stub_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    grpc::ClientContext* ctx_ptr_{nullptr};
};

// ─── MetadataClient ───────────────────────────────────────────────────────────

/**
 * Provides synchronous track-metadata retrieval via the MetadataService gRPC service.
 */
class MetadataClient {
public:
    explicit MetadataClient(std::shared_ptr<BeatLinkClient> client)
        : stub_(beatlink::MetadataService::NewStub(client->channel())) {}

    /**
     * Retrieves rekordbox metadata for the specified track.
     *
     * @param sourcePlayer  Device number of the player holding the track.
     * @param sourceSlot    Slot name: "USB_SLOT", "SD_SLOT", "CD_SLOT", or "COLLECTION".
     * @param rekordboxId   Rekordbox track ID.
     * @throws std::runtime_error on gRPC failure or if the track is not found.
     */
    beatlink::TrackMetadata getTrackMetadata(int sourcePlayer,
                                             const std::string& sourceSlot,
                                             int rekordboxId) {
        beatlink::GetMetadataRequest req;
        req.set_source_player(sourcePlayer);
        req.set_source_slot(sourceSlot);
        req.set_rekordbox_id(rekordboxId);

        beatlink::TrackMetadata resp;
        grpc::ClientContext ctx;
        grpc::Status status = stub_->GetTrackMetadata(&ctx, req, &resp);
        if (!status.ok()) {
            throw std::runtime_error("GetTrackMetadata failed: " + status.error_message());
        }
        return resp;
    }

    /**
     * Retrieves the cue/hot-cue/loop list for the specified track.
     *
     * @param sourcePlayer  Device number of the player holding the track.
     * @param sourceSlot    Slot name: "USB_SLOT", "SD_SLOT", "CD_SLOT", or "COLLECTION".
     * @param rekordboxId   Rekordbox track ID.
     * @throws std::runtime_error on gRPC failure or if the track is not found.
     */
    std::vector<beatlink::CueEntry> getCueList(int sourcePlayer,
                                               const std::string& sourceSlot,
                                               int rekordboxId) {
        beatlink::GetCueListRequest req;
        req.set_source_player(sourcePlayer);
        req.set_source_slot(sourceSlot);
        req.set_rekordbox_id(rekordboxId);

        beatlink::GetCueListResponse resp;
        grpc::ClientContext ctx;
        grpc::Status status = stub_->GetCueList(&ctx, req, &resp);
        if (!status.ok()) {
            throw std::runtime_error("GetCueList failed: " + status.error_message());
        }

        std::vector<beatlink::CueEntry> result;
        result.reserve(resp.entries_size());
        for (const auto& e : resp.entries()) {
            result.push_back(e);
        }
        return result;
    }

private:
    std::unique_ptr<beatlink::MetadataService::Stub> stub_;
};
