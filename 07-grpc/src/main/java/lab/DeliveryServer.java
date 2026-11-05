package lab;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lab.grpc.*;

import java.io.IOException;
import java.util.Map;

/**
 * Exercise 07 — gRPC server.
 *
 * Run:  mvn -q compile exec:java                     (server on localhost:9090)
 *
 * `mvn compile` first runs protoc on src/main/proto/delivery.proto and generates:
 *   - message classes  EtaRequest, EtaReply, RiderLocation ...  (with builders)
 *   - DeliveryServiceGrpc.DeliveryServiceImplBase  -> you extend this (server)
 *   - DeliveryServiceGrpc.newBlockingStub(...)      -> the client uses this
 */
public class DeliveryServer {

    static final int PORT = 9090;

    /** Fake data: where each order's rider is right now. */
    record Trip(String riderName, double lat, double lng, int metersLeft) {}

    static final Map<String, Trip> TRIPS = Map.of(
            "ord_1", new Trip("Ravi", 12.9352, 77.6245, 2400),
            "ord_2", new Trip("Imran", 12.9716, 77.5946, 900));

    static final double METERS_PER_MINUTE = 250;   // ~15 km/h in Bengaluru traffic

    static class DeliveryServiceImpl extends DeliveryServiceGrpc.DeliveryServiceImplBase {

        /**
         * TODO(1): Unary RPC.
         *   - Unknown order -> responseObserver.onError(
         *                        Status.NOT_FOUND.withDescription("No trip for ord_x").asRuntimeException());
         *     (gRPC has its own status codes: NOT_FOUND, INVALID_ARGUMENT, DEADLINE_EXCEEDED, UNAVAILABLE...)
         *   - Otherwise minutes = ceil(metersLeft / METERS_PER_MINUTE) and reply:
         *        responseObserver.onNext(EtaReply.newBuilder().setOrderId(...).setMinutes(...).setRiderName(...).build());
         *        responseObserver.onCompleted();      // <- don't forget, or the client waits forever
         */
        @Override
        public void getEta(EtaRequest request, StreamObserver<EtaReply> responseObserver) {
            Trip trip = TRIPS.get(request.getOrderId());
            if (trip == null) {
                responseObserver.onError(
                        Status.NOT_FOUND.withDescription("No trip for " + request.getOrderId()).asRuntimeException());
                return;
            }
            int minutes = (int) Math.ceil(trip.metersLeft() / METERS_PER_MINUTE);
            EtaReply reply = EtaReply.newBuilder()
                    .setOrderId(request.getOrderId())
                    .setMinutes(minutes)
                    .setRiderName(trip.riderName())
                    .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }

        /**
         * TODO(2): Server-streaming RPC. Send the rider's position 6 times, every 500 ms,
         *          getting closer each time (metersLeft goes down to 0, lat/lng drift a little).
         *   - Unknown order -> NOT_FOUND like above
         *   - Call responseObserver.onNext(location) for each point, onCompleted() at the end
         *   - If the client hangs up early, stop:
         *        var call = (ServerCallStreamObserver<RiderLocation>) responseObserver;
         *        if (call.isCancelled()) return;
         */
        @Override
        public void trackRider(TrackRequest request, StreamObserver<RiderLocation> responseObserver) {
            Trip trip = TRIPS.get(request.getOrderId());
            if (trip == null) {
                responseObserver.onError(
                        Status.NOT_FOUND.withDescription("No trip for " + request.getOrderId()).asRuntimeException());
                return;
            }
            ServerCallStreamObserver<RiderLocation> call = (ServerCallStreamObserver<RiderLocation>) responseObserver;
            for (int i = 0; i < 6; i++) {
                if (call.isCancelled()) {
                    return;
                }
                double fraction = (double) i / 5.0;
                int metersLeft = (int) (trip.metersLeft() * (1.0 - fraction));
                double lat = trip.lat() + (i * 0.0004);
                double lng = trip.lng() + (i * 0.0004);

                RiderLocation loc = RiderLocation.newBuilder()
                        .setOrderId(request.getOrderId())
                        .setLat(lat)
                        .setLng(lng)
                        .setMetersLeft(metersLeft)
                        .setTimestampMs(System.currentTimeMillis())
                        .build();
                responseObserver.onNext(loc);

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            responseObserver.onCompleted();
        }

        /**
         * TODO(3) — STRETCH: Client-streaming RPC.
         *   Return a StreamObserver<RiderLocation> that:
         *     onNext      -> counts points and adds the distance from the previous point (use km(...))
         *     onError     -> just log it
         *     onCompleted -> responseObserver.onNext(RouteSummary{points, totalKm}); responseObserver.onCompleted();
         */
        @Override
        public StreamObserver<RiderLocation> uploadRoute(StreamObserver<RouteSummary> responseObserver) {
            return new StreamObserver<RiderLocation>() {
                private int count = 0;
                private double totalKm = 0.0;
                private RiderLocation prev = null;

                @Override
                public void onNext(RiderLocation point) {
                    count++;
                    if (prev != null) {
                        totalKm += km(prev, point);
                    }
                    prev = point;
                }

                @Override
                public void onError(Throwable t) {
                    System.err.println("uploadRoute error: " + t.getMessage());
                }

                @Override
                public void onCompleted() {
                    RouteSummary summary = RouteSummary.newBuilder()
                            .setPoints(count)
                            .setTotalKm(totalKm)
                            .build();
                    responseObserver.onNext(summary);
                    responseObserver.onCompleted();
                }
            };
        }
    }

    /** Distance between two points in km (haversine). Given. */
    static double km(RiderLocation a, RiderLocation b) {
        double dLat = Math.toRadians(b.getLat() - a.getLat()), dLng = Math.toRadians(b.getLng() - a.getLng());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.getLat())) * Math.cos(Math.toRadians(b.getLat())) * Math.pow(Math.sin(dLng / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(h));
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Server server = ServerBuilder.forPort(PORT)
                .addService(new DeliveryServiceImpl())
                .build()
                .start();
        System.out.println("gRPC DeliveryService listening on localhost:" + PORT + "  (HTTP/2, protobuf)");
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }
}
