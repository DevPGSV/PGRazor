package dev.pgrazor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class LocalApiSettingsModelTest {
    public static void main(String[] args) throws Exception {
        LocalApiConfig parsed = LocalApiSettingsModel.parse(" 127.0.0.1 ", " 8765 ");
        check(parsed.host.equals("127.0.0.1") && parsed.port == 8765);
        invalid("localhost", "8765");
        invalid("0.0.0.0", "99999");
        invalid("0.0.0.0", "8765.0");
        invalid("0.0.0.0", "80");
        List<InetAddress> interfaces = Arrays.asList(address(192, 168, 40, 3), address(127, 0, 0, 1),
            address(0, 0, 0, 0), InetAddress.getByAddress(new byte[16]), address(169, 254, 1, 2),
            address(239, 1, 2, 3), address(192, 168, 40, 3), address(10, 0, 0, 8));
        check(LocalApiAddresses.candidates(LocalApiConfig.DEFAULT, interfaces).equals(Arrays.asList(
            "http://10.0.0.8:8765/api/v1", "http://192.168.40.3:8765/api/v1")));
        check(LocalApiAddresses.candidates(new LocalApiConfig("10.0.0.8", 8888), interfaces)
            .equals(Collections.singletonList("http://10.0.0.8:8888/api/v1")));
        check(LocalApiAddresses.candidates(parsed, interfaces).isEmpty());

        BlockingStore store = new BlockingStore();
        LocalApiController controller = new LocalApiController(store);
        LocalApiSettingsModel model = new LocalApiSettingsModel(controller);
        check(model.submit(LocalApiSettingsModel.Action.LOAD, null));
        check(store.entered.await(5, TimeUnit.SECONDS));
        check(model.state().action == LocalApiSettingsModel.Action.LOAD);
        check(!model.submit(LocalApiSettingsModel.Action.START, null));
        store.release.countDown();
        await(model);
        check(model.state().error == null && model.binding() == null);
        String oldToken = model.state().settings.token;

        InetAddress loopback = address(127, 0, 0, 1);
        try (ServerSocket occupied = new ServerSocket(0, 0, loopback)) {
            check(model.submit(LocalApiSettingsModel.Action.APPLY, new LocalApiConfig("127.0.0.1", occupied.getLocalPort())));
            await(model);
            check(model.state().settings.config.port == occupied.getLocalPort());
            check(model.submit(LocalApiSettingsModel.Action.START, null));
            await(model);
            check(model.binding() == null && model.state().error.contains("already in use"));
        }
        check(model.submit(LocalApiSettingsModel.Action.START, null));
        await(model);
        check(model.binding() != null && model.state().error == null);
        check(model.submit(LocalApiSettingsModel.Action.ROTATE, null));
        await(model);
        check(model.binding() != null && !model.state().settings.token.equals(oldToken));

        // A view observing a second time reads the same active controller. External logout invalidation
        // is reflected without a view action and never makes pending saved config appear bound.
        LocalApiSettingsModel reopened = model;
        check(reopened.binding().port == model.state().settings.config.port);
        long oldEpoch = controller.generation();
        controller.stop();
        check(reopened.binding() == null);
        try {
            controller.start(oldEpoch);
            throw new AssertionError("Older page action restarted the listener");
        } catch (IOException expected) {}
        check(model.submit(LocalApiSettingsModel.Action.STOP, null));
        await(model);
        check(model.binding() == null && model.state().error == null);
        System.out.println("PASS: HTTP settings validation, candidate addresses, async controls and binding lifecycle");
    }

    private static InetAddress address(int a, int b, int c, int d) throws IOException {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private static void await(LocalApiSettingsModel model) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (model.state().action != null && System.nanoTime() < deadline) Thread.sleep(10);
        check(model.state().action == null);
    }

    private static void invalid(String host, String port) {
        try {
            LocalApiSettingsModel.parse(host, port);
            throw new AssertionError("Invalid settings accepted");
        } catch (IllegalArgumentException expected) {}
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static final class BlockingStore implements LocalApiController.Store {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        LocalApiController.Settings saved;

        public LocalApiController.Settings load() throws IOException {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timeout");
            } catch (InterruptedException error) {
                throw new IOException(error);
            }
            return saved;
        }

        public void save(LocalApiController.Settings value) { saved = value; }
    }
}
