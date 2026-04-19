package org.deepsymmetry.beatlink.grpc;

import io.grpc.stub.StreamObserver;
import org.deepsymmetry.beatlink.Beat;
import org.deepsymmetry.beatlink.BeatFinder;
import org.deepsymmetry.beatlink.BeatListener;
import org.deepsymmetry.beatlink.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * gRPC service implementation for {@link BeatServiceGrpc}.
 *
 * <p>Registers a single {@link BeatListener} with {@link BeatFinder} and fans
 * beat events out to every active server-streaming observer.</p>
 */
public class BeatServiceImpl extends BeatServiceGrpc.BeatServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(BeatServiceImpl.class);

    /** All currently active streaming observers. */
    private final CopyOnWriteArrayList<StreamObserver<BeatEvent>> beatObservers =
            new CopyOnWriteArrayList<>();

    /** Single listener registered with BeatFinder. */
    private final BeatListener beatListener = beat -> {
        BeatEvent event = toProto(beat);
        for (StreamObserver<BeatEvent> observer : beatObservers) {
            try {
                observer.onNext(event);
            } catch (Exception e) {
                logger.debug("Removing disconnected StreamBeats observer", e);
                beatObservers.remove(observer);
            }
        }
    };

    /** Constructs the service and registers the BeatFinder listener. */
    public BeatServiceImpl() {
        BeatFinder.getInstance().addBeatListener(beatListener);
    }

    // ─── RPC implementations ─────────────────────────────────────────────────

    /**
     * Keeps the response stream open, pushing every beat event to the caller
     * until the call is cancelled or the server shuts down.
     */
    @Override
    public void streamBeats(StreamRequest request,
                            StreamObserver<BeatEvent> responseObserver) {
        beatObservers.add(responseObserver);
        logger.debug("New StreamBeats subscriber, total={}", beatObservers.size());
    }

    // ─── Conversion helpers ──────────────────────────────────────────────────

    static BeatEvent toProto(Beat b) {
        BeatEvent.Builder builder = BeatEvent.newBuilder()
                .setDeviceNumber(b.getDeviceNumber())
                .setDeviceName(b.getDeviceName())
                .setAddress(b.getAddress().getHostAddress())
                .setTimestampNs(b.getTimestamp())
                .setPitch(b.getPitch())
                .setBpm(b.getBpm())
                .setEffectiveBpm(b.getEffectiveTempo())
                .setBeatWithinBar(b.getBeatWithinBar())
                .setBeatWithinBarMeaningful(b.isBeatWithinBarMeaningful())
                .setIsSynced(false)  // Safe default; requires VirtualCdj which may not be running
                .setNextBeatMs(b.getNextBeat())
                .setSecondBeatMs(b.getSecondBeat())
                .setNextBarMs(b.getNextBar())
                .setFourthBeatMs(b.getFourthBeat())
                .setSecondBarMs(b.getSecondBar())
                .setEighthBeatMs(b.getEighthBeat());

        // isTempoMaster / isSynced require VirtualCdj; guard against IllegalStateException.
        try {
            builder.setIsTempoMaster(b.isTempoMaster());
        } catch (IllegalStateException e) {
            builder.setIsTempoMaster(false);
        }
        try {
            builder.setIsSynced(b.isSynced());
        } catch (IllegalStateException e) {
            builder.setIsSynced(false);
        }

        return builder.build();
    }
}
