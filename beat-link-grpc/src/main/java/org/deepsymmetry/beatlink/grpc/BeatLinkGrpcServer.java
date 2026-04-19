package org.deepsymmetry.beatlink.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.deepsymmetry.beatlink.BeatFinder;
import org.deepsymmetry.beatlink.DeviceFinder;
import org.deepsymmetry.beatlink.VirtualCdj;
import org.deepsymmetry.beatlink.data.MetadataFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Entry point for the beat-link gRPC server.
 *
 * <p>Starts the DJ Link protocol components (DeviceFinder, VirtualCdj, BeatFinder,
 * MetadataFinder) and then listens for gRPC calls on the specified port (default 50051).</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar beat-link-grpc-server.jar [port]
 * </pre>
 *
 * <p>The server exposes four services:
 * <ul>
 *   <li>{@link DeviceServiceImpl} – device discovery</li>
 *   <li>{@link BeatServiceImpl} – beat events</li>
 *   <li>{@link PlayerStatusServiceImpl} – CDJ status updates</li>
 *   <li>{@link MetadataServiceImpl} – track metadata and cue lists</li>
 * </ul>
 * </p>
 */
public class BeatLinkGrpcServer {

    private static final Logger logger = LoggerFactory.getLogger(BeatLinkGrpcServer.class);

    /** Default gRPC port. Does not conflict with any DJ Link port (50000–50002). */
    public static final int DEFAULT_PORT = 50051;

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port argument '" + args[0] + "', using default " + DEFAULT_PORT);
            }
        }

        // ── Start DJ Link components ──────────────────────────────────────────
        logger.info("Starting DJ Link components...");

        try {
            DeviceFinder.getInstance().start();
            logger.info("DeviceFinder started – listening for device announcements on port 50000");
        } catch (java.net.SocketException e) {
            logger.error("Failed to start DeviceFinder", e);
            System.exit(1);
        }

        try {
            boolean started = VirtualCdj.getInstance().start();
            if (started) {
                logger.info("VirtualCdj started – receiving player status updates on port 50002");
            } else {
                logger.warn("VirtualCdj could not find a free device number; status updates may be unavailable");
            }
        } catch (Exception e) {
            logger.warn("VirtualCdj could not start (no status updates will be available): {}", e.getMessage());
        }

        try {
            BeatFinder.getInstance().start();
            logger.info("BeatFinder started – listening for beat packets on port 50001");
        } catch (java.net.SocketException e) {
            logger.warn("BeatFinder could not start (no beat events will be available): {}", e.getMessage());
        }

        try {
            MetadataFinder.getInstance().start();
            logger.info("MetadataFinder started – track metadata queries are available");
        } catch (Exception e) {
            logger.warn("MetadataFinder could not start (no metadata will be available): {}", e.getMessage());
        }

        // ── Start gRPC server ─────────────────────────────────────────────────
        Server server = ServerBuilder.forPort(port)
                .addService(new DeviceServiceImpl())
                .addService(new BeatServiceImpl())
                .addService(new PlayerStatusServiceImpl())
                .addService(new MetadataServiceImpl())
                .build()
                .start();

        logger.info("beat-link gRPC server listening on port {}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down beat-link gRPC server...");
            server.shutdown();
            MetadataFinder.getInstance().stop();
            BeatFinder.getInstance().stop();
            VirtualCdj.getInstance().stop();
            DeviceFinder.getInstance().stop();
            logger.info("Shutdown complete.");
        }, "grpc-shutdown"));

        server.awaitTermination();
    }
}
