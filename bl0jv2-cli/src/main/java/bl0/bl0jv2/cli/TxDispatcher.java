package bl0.bl0jv2.cli;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The single reader of the VM's host TX window (see {@link NicFrame#pollTx}),
 * handing every frame it reads to every subscriber that wants it.
 *
 * <p>The TX window is one slot, and reading a frame ACKs it so the VM may
 * send the next. With several bridges and several connections each polling
 * the window themselves, whoever polled first consumed (and acked) a frame
 * that was meant for somebody else, and the VM's next frame then overwrote it
 * before the intended reader looked - frames silently lost as soon as there
 * were two connections. One reader that fans out removes the race: a frame is
 * read once and offered to all matching subscriptions.
 *
 * <p>The reader thread runs only while somebody is subscribed (a per-
 * connection subscription closes when the connection ends), and remembers the
 * last sequence number it handled so a restart does not replay a stale frame.
 */
final class TxDispatcher {
    private static final Map<Bl0jv2_jVM, TxDispatcher> INSTANCES = new WeakHashMap<>();

    static TxDispatcher of(Bl0jv2_jVM vm) {
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(vm, TxDispatcher::new);
        }
    }

    private static final int QUEUE_LIMIT = 1024;

    private final Bl0jv2_jVM vm;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    // 0 is the window's initial value (nothing sent yet), not a frame
    private long lastSeq = 0;
    private Thread reader; // guarded by this

    private TxDispatcher(Bl0jv2_jVM vm) {
        this.vm = vm;
        vm.hostPortWrite(NicFrame.HOST_BRIDGE_PRESENT_PORT, 2, 1);
    }

    /** receives every TX frame for which filter returns true, in the order the VM sent them */
    synchronized Subscription subscribe(Predicate<byte[]> filter) {
        Subscription s = new Subscription(filter);
        subscriptions.add(s);
        if (reader == null) {
            reader = new Thread(this::run, "tx-dispatcher");
            reader.setDaemon(true);
            reader.start();
        }
        return s;
    }

    private void run() {
        while (true) {
            synchronized (this) {
                if (subscriptions.isEmpty()) {
                    reader = null;
                    return;
                }
            }

            NicFrame.TxPoll polled = NicFrame.pollTx(vm, lastSeq);
            if (polled == null) {
                TcpRelay.sleep(1);
                continue;
            }
            lastSeq = polled.seq;

            for (Subscription s : subscriptions) {
                try {
                    if (s.filter.test(polled.frame))
                        s.queue.offer(polled.frame); // a subscriber that never drains loses its newest frames, not the VM's progress
                } catch (RuntimeException e) {
                    // a filter that chokes on an odd frame must not take the reader down with it
                }
            }
        }
    }

    final class Subscription implements AutoCloseable {
        private final Predicate<byte[]> filter;
        private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(QUEUE_LIMIT);

        private Subscription(Predicate<byte[]> filter) {
            this.filter = filter;
        }

        /** the next matching frame, or null if none is waiting */
        byte[] poll() {
            return queue.poll();
        }

        /** the next matching frame, waiting up to timeoutMs for one; null on timeout */
        byte[] poll(long timeoutMs) {
            try {
                return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void close() {
            subscriptions.remove(this);
        }
    }
}
