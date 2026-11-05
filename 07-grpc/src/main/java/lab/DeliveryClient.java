package lab;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lab.grpc.*;

import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Exercise 07 — gRPC client.
 *
 * Run (server must be running):
 *   mvn -q compile exec:java -Dexec.mainClass=lab.DeliveryClient
 */
public class DeliveryClient {

    public static void main(String[] args) throws Exception {
        // One channel = one HTTP/2 connection, reused for every call (and multiplexed).
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                .usePlaintext()            // no TLS for the lab
                .build();
        DeliveryServiceGrpc.DeliveryServiceBlockingStub stub = DeliveryServiceGrpc.newBlockingStub(channel);

        System.out.println("=== 1. Unary: GetEta ===");
        unary(stub);

        System.out.println("\n=== 2. Server streaming: TrackRider ===");
        streaming(stub);

        System.out.println("\n=== 3. Client streaming: UploadRoute (stretch) ===");
        clientStreaming(channel);

        channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
    }

    /**
     * TODO(4): Unary calls.
     *   a) Call stub.withDeadlineAfter(2, TimeUnit.SECONDS).getEta(EtaRequest...) for "ord_1" and print
     *      rider name + minutes.
     *   b) Print the size on the wire:  reply.getSerializedSize()  vs the same data as JSON
     *      (build the JSON string by hand and print its .length()). Which is smaller, and why?
     *   c) Call it for "ord_999" inside try/catch (StatusRuntimeException e) and print e.getStatus().getCode()
     *      and e.getStatus().getDescription().
     */
    static void unary(DeliveryServiceGrpc.DeliveryServiceBlockingStub stub) {
        // a) Unary call for "ord_1"
        EtaReply reply = stub.withDeadlineAfter(2, TimeUnit.SECONDS)
                .getEta(EtaRequest.newBuilder().setOrderId("ord_1").build());
        System.out.println(reply.getRiderName() + " arrives in " + reply.getMinutes() + " min");

        // b) Compare protobuf wire size vs JSON
        int protoBytes = reply.getSerializedSize();
        String json = String.format("{\"order_id\":\"%s\",\"minutes\":%d,\"rider_name\":\"%s\"}",
                reply.getOrderId(), reply.getMinutes(), reply.getRiderName());
        int jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        System.out.printf("wire size: protobuf %d bytes vs JSON %d bytes%n", protoBytes, jsonBytes);

        // c) Error handling for unknown order "ord_999"
        try {
            stub.withDeadlineAfter(2, TimeUnit.SECONDS)
                    .getEta(EtaRequest.newBuilder().setOrderId("ord_999").build());
        } catch (StatusRuntimeException e) {
            System.out.println("ord_999 -> " + e.getStatus().getCode() + ": " + e.getStatus().getDescription());
        }
    }

    /**
     * TODO(5): Server streaming.
     *   Iterator<RiderLocation> it = stub.trackRider(TrackRequest...("ord_1"));
     *   Print each location as it arrives (metersLeft, lat, lng). Notice they arrive ~500 ms apart.
     *   Then try "ord_2" with stub.withDeadlineAfter(1, TimeUnit.SECONDS) -- what happens mid-stream?
     */
    static void streaming(DeliveryServiceGrpc.DeliveryServiceBlockingStub stub) {
        Iterator<RiderLocation> it = stub.trackRider(TrackRequest.newBuilder().setOrderId("ord_1").build());
        while (it.hasNext()) {
            RiderLocation loc = it.next();
            System.out.printf("  %4d m left  (%.4f, %.4f)%n", loc.getMetersLeft(), loc.getLat(), loc.getLng());
        }

        try {
            Iterator<RiderLocation> it2 = stub.withDeadlineAfter(1, TimeUnit.SECONDS)
                    .trackRider(TrackRequest.newBuilder().setOrderId("ord_2").build());
            while (it2.hasNext()) {
                it2.next();
            }
        } catch (StatusRuntimeException e) {
            System.out.println("  ord_2 stream ended with " + e.getStatus().getCode() + " (deadline hit mid-stream)");
        }
    }

    /** Given: client streaming needs the async stub. Works once TODO(3) on the server is done. */
    static void clientStreaming(ManagedChannel channel) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<RiderLocation> upload = DeliveryServiceGrpc.newStub(channel).uploadRoute(new StreamObserver<>() {
            @Override public void onNext(RouteSummary s) { System.out.printf("  server says: %d points, %.2f km%n", s.getPoints(), s.getTotalKm()); }
            @Override public void onError(Throwable t) { System.out.println("  " + io.grpc.Status.fromThrowable(t)); done.countDown(); }
            @Override public void onCompleted() { done.countDown(); }
        });
        for (int i = 0; i < 5; i++) {
            upload.onNext(RiderLocation.newBuilder().setOrderId("ord_1")
                    .setLat(12.9352 + i * 0.003).setLng(77.6245 + i * 0.003).build());
        }
        upload.onCompleted();
        done.await(5, TimeUnit.SECONDS);
    }
}
