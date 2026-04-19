package org.deepsymmetry.beatlink.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.data.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC service implementation for {@link MetadataServiceGrpc}.
 *
 * <p>Delegates to the {@link MetadataFinder} singleton to retrieve track metadata and
 * cue lists on demand.</p>
 */
public class MetadataServiceImpl extends MetadataServiceGrpc.MetadataServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(MetadataServiceImpl.class);

    // ─── RPC implementations ─────────────────────────────────────────────────

    /**
     * Retrieves rekordbox metadata for the track identified by the request.
     */
    @Override
    public void getTrackMetadata(GetMetadataRequest request,
                                 StreamObserver<TrackMetadata> responseObserver) {
        DataReference ref = buildDataReference(request.getSourcePlayer(),
                request.getSourceSlot(),
                request.getRekordboxId());
        if (ref == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Unknown source slot: " + request.getSourceSlot())
                    .asRuntimeException());
            return;
        }

        org.deepsymmetry.beatlink.data.TrackMetadata meta =
                MetadataFinder.getInstance().requestMetadataFrom(ref);

        if (meta == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No metadata found for track " + request.getRekordboxId()
                            + " on player " + request.getSourcePlayer())
                    .asRuntimeException());
            return;
        }

        responseObserver.onNext(toProto(meta));
        responseObserver.onCompleted();
    }

    /**
     * Retrieves the cue list for the track identified by the request.
     */
    @Override
    public void getCueList(GetCueListRequest request,
                           StreamObserver<GetCueListResponse> responseObserver) {
        DataReference ref = buildDataReference(request.getSourcePlayer(),
                request.getSourceSlot(),
                request.getRekordboxId());
        if (ref == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Unknown source slot: " + request.getSourceSlot())
                    .asRuntimeException());
            return;
        }

        org.deepsymmetry.beatlink.data.TrackMetadata meta =
                MetadataFinder.getInstance().requestMetadataFrom(ref);

        if (meta == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("No metadata (and therefore no cue list) found for track "
                            + request.getRekordboxId() + " on player " + request.getSourcePlayer())
                    .asRuntimeException());
            return;
        }

        GetCueListResponse.Builder builder = GetCueListResponse.newBuilder();
        CueList cueList = meta.getCueList();
        if (cueList != null) {
            for (CueList.Entry entry : cueList.entries) {
                builder.addEntries(toProto(entry));
            }
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    // ─── Conversion helpers ──────────────────────────────────────────────────

    /**
     * Converts a string slot name from the proto request to a {@link CdjStatus.TrackSourceSlot}
     * and builds a {@link DataReference}. Returns {@code null} if the slot string is unrecognised.
     */
    private static DataReference buildDataReference(int player, String slotName, int rekordboxId) {
        CdjStatus.TrackSourceSlot slot;
        try {
            slot = CdjStatus.TrackSourceSlot.valueOf(slotName.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown track source slot name: {}", slotName);
            return null;
        }
        return new DataReference(player, slot, rekordboxId);
    }

    static TrackMetadata toProto(org.deepsymmetry.beatlink.data.TrackMetadata m) {
        TrackMetadata.Builder b = TrackMetadata.newBuilder()
                .setTitle(orEmpty(m.getTitle()))
                .setDurationSecs(m.getDuration())
                .setTempo(m.getTempo())
                .setRating(m.getRating())
                .setYear(m.getYear())
                .setBitRateKbps(m.getBitRate())
                .setDateAdded(orEmpty(m.getDateAdded()))
                .setTrackType(m.trackType.name())
                .setArtworkId(m.getArtworkId());

        if (m.getArtist() != null)        b.setArtist(m.getArtist().label);
        if (m.getAlbum() != null)         b.setAlbum(m.getAlbum().label);
        if (m.getGenre() != null)         b.setGenre(m.getGenre().label);
        if (m.getKey() != null)           b.setKey(m.getKey().label);
        if (m.getLabel() != null)         b.setLabel(m.getLabel().label);
        if (m.getComment() != null)       b.setComment(m.getComment());

        return b.build();
    }

    static CueEntry toProto(CueList.Entry e) {
        CueEntry.Builder b = CueEntry.newBuilder()
                .setHotCueNumber(e.hotCueNumber)
                .setPositionMs((int) e.cueTime)
                .setIsLoop(e.isLoop);

        if (e.isLoop) {
            b.setLoopEndMs((int) e.loopTime);
        }
        if (e.comment != null) {
            b.setComment(e.comment);
        }
        // Prefer the embedded color; fall back to the rekordbox display color.
        if (e.embeddedColor != null) {
            b.setColorArgb(e.embeddedColor.getRGB());
        } else if (e.rekordboxColor != null) {
            b.setColorArgb(e.rekordboxColor.getRGB());
        }

        return b.build();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
