package org.deepsymmetry.beatlink.grpc;

import io.grpc.stub.StreamObserver;
import org.deepsymmetry.beatlink.DeviceAnnouncement;
import org.deepsymmetry.beatlink.DeviceAnnouncementListener;
import org.deepsymmetry.beatlink.DeviceFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * gRPC service implementation for {@link DeviceServiceGrpc}.
 *
 * <p>Bridges the {@link DeviceFinder} singleton to gRPC callers. It
 * registers itself as a {@link DeviceAnnouncementListener} once and fans
 * device-announcement events out to every active server-streaming observer.</p>
 */
public class DeviceServiceImpl extends DeviceServiceGrpc.DeviceServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(DeviceServiceImpl.class);

    /** All currently active streaming observers. */
    private final CopyOnWriteArrayList<StreamObserver<DeviceInfo>> announcementObservers =
            new CopyOnWriteArrayList<>();

    /** Listener registered with DeviceFinder to receive live announcements. */
    private final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(DeviceAnnouncement announcement) {
            DeviceInfo info = toProto(announcement);
            for (StreamObserver<DeviceInfo> observer : announcementObservers) {
                try {
                    observer.onNext(info);
                } catch (Exception e) {
                    logger.debug("Removing disconnected StreamDeviceAnnouncements observer", e);
                    announcementObservers.remove(observer);
                }
            }
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            // No action needed; StreamDeviceAnnouncements only reports new arrivals.
        }
    };

    /** Constructs the service and registers the DeviceFinder listener. */
    public DeviceServiceImpl() {
        DeviceFinder.getInstance().addDeviceAnnouncementListener(announcementListener);
    }

    // ─── RPC implementations ─────────────────────────────────────────────────

    /**
     * Returns a snapshot of all currently known DJ Link devices.
     */
    @Override
    public void listDevices(ListDevicesRequest request,
                            StreamObserver<ListDevicesResponse> responseObserver) {
        ListDevicesResponse.Builder builder = ListDevicesResponse.newBuilder();
        for (DeviceAnnouncement announcement : DeviceFinder.getInstance().getCurrentDevices()) {
            builder.addDevices(toProto(announcement));
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    /**
     * Keeps the response stream open, pushing every new device announcement to the caller
     * until the call is cancelled or the server shuts down.
     */
    @Override
    public void streamDeviceAnnouncements(StreamRequest request,
                                          StreamObserver<DeviceInfo> responseObserver) {
        announcementObservers.add(responseObserver);
        logger.debug("New StreamDeviceAnnouncements subscriber, total={}", announcementObservers.size());
    }

    // ─── Conversion helpers ──────────────────────────────────────────────────

    static DeviceInfo toProto(DeviceAnnouncement a) {
        return DeviceInfo.newBuilder()
                .setName(a.getDeviceName())
                .setNumber(a.getDeviceNumber())
                .setAddress(a.getAddress().getHostAddress())
                .setTimestampMs(a.getTimestamp())
                .setIsOpusQuad(a.isOpusQuad)
                .setIsXdjAz(a.isXdjAz)
                .setPeerCount(a.getPeerCount())
                .build();
    }
}
