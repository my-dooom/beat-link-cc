package org.deepsymmetry.beatlink.grpc;

import io.grpc.stub.StreamObserver;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.DeviceUpdate;
import org.deepsymmetry.beatlink.DeviceUpdateListener;
import org.deepsymmetry.beatlink.VirtualCdj;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * gRPC service implementation for {@link PlayerStatusServiceGrpc}.
 *
 * <p>Registers a single {@link DeviceUpdateListener} with {@link VirtualCdj} and fans
 * CDJ status updates out to every active server-streaming observer.
 * Only {@link CdjStatus} updates are forwarded; mixer status updates are silently ignored.</p>
 */
public class PlayerStatusServiceImpl extends PlayerStatusServiceGrpc.PlayerStatusServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(PlayerStatusServiceImpl.class);

    /** All currently active streaming observers. */
    private final CopyOnWriteArrayList<StreamObserver<PlayerStatus>> statusObservers =
            new CopyOnWriteArrayList<>();

    /** Single listener registered with VirtualCdj. */
    private final DeviceUpdateListener updateListener = update -> {
        if (!(update instanceof CdjStatus)) {
            return;
        }
        PlayerStatus status = toProto((CdjStatus) update);
        for (StreamObserver<PlayerStatus> observer : statusObservers) {
            try {
                observer.onNext(status);
            } catch (Exception e) {
                logger.debug("Removing disconnected StreamPlayerStatus observer", e);
                statusObservers.remove(observer);
            }
        }
    };

    /** Constructs the service and registers the VirtualCdj listener. */
    public PlayerStatusServiceImpl() {
        VirtualCdj.getInstance().addUpdateListener(updateListener);
    }

    // ─── RPC implementations ─────────────────────────────────────────────────

    /**
     * Keeps the response stream open, pushing every CDJ status update to the caller
     * until the call is cancelled or the server shuts down.
     */
    @Override
    public void streamPlayerStatus(StreamRequest request,
                                   StreamObserver<PlayerStatus> responseObserver) {
        statusObservers.add(responseObserver);
        logger.debug("New StreamPlayerStatus subscriber, total={}", statusObservers.size());
    }

    // ─── Conversion helpers ──────────────────────────────────────────────────

    static PlayerStatus toProto(CdjStatus s) {
        return PlayerStatus.newBuilder()
                .setDeviceNumber(s.getDeviceNumber())
                .setDeviceName(s.getDeviceName())
                .setAddress(s.getAddress().getHostAddress())
                .setTimestampNs(s.getTimestamp())
                .setPitch(s.getPitch())
                .setBpm(s.getBpm())
                .setEffectiveBpm(s.getEffectiveTempo())
                .setIsPlaying(s.isPlaying())
                .setIsSynced(s.isSynced())
                .setIsTempoMaster(s.isTempoMaster())
                .setIsOnAir(s.isOnAir())
                .setBeatWithinBar(s.getBeatWithinBar())
                .setRekordboxId(s.getRekordboxId())
                .setTrackSourcePlayer(s.getTrackSourcePlayer())
                .setTrackSourceSlot(s.getTrackSourceSlot().name())
                .setTrackType(s.getTrackType().name())
                .setPlayState(s.getPlayState1().name())
                .setIsFromOpusQuad(s.isFromOpusQuad)
                .setIsPreNexus(s.isPreNexusCdj())
                .build();
    }
}
