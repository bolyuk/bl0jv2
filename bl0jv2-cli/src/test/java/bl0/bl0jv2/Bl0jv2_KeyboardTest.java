package bl0.bl0jv2;

import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// a minimal HID (keyboard) driver stack, built entirely from primitives the
// VM already exposes: hostPortWrite()/raiseInterrupt() simulate a real
// keyboard controller placing a scancode on its data port and asserting an
// IRQ line; the interrupt handler drains that one byte into a ring buffer
// (built from raw memory, not a managed array - Bl0jArray's push/pop is
// LIFO, wrong order for a FIFO input stream); haltCore() blocks a consumer
// until something is actually there to read.
//
// Every test here drives the VM directly (not through Bl0jv2_TestRunner's
// String-returning run()), because delivering a keystroke means calling
// vm.hostPortWrite()/vm.raiseInterrupt() from a SEPARATE Java thread while
// the bl0jv2 program's own run_instructions() call is still blocked
// (typically inside haltCore()) on the main thread - the exact same
// two-thread shape as Bl0jv2_InterruptTest's own
// haltCoreBlocksUntilAnInterruptBecomesPendingThenDelivers test.
class Bl0jv2_KeyboardTest {

    // dataPort/vector/bufferAddr/bufferSize are plain literals here (not
    // reserve()'d or kalloc()'d) - this test is about the driver mechanism
    // itself, not aeon-os/boot.bl0's own kernel-heap-backed version of it
    private static final String DRIVER =
            "def class Keyboard { " +
            "  static field dataPort; static field bufferAddr; static field bufferSize; " +
            "  static field head; static field tail; " +
            "} " +
            "Keyboard.dataPort = 5; " +
            "Keyboard.bufferAddr = 0; " +
            "Keyboard.bufferSize = 4; " +
            "Keyboard.head = 0; " +
            "Keyboard.tail = 0; " +
            // full: one slot is always kept empty to distinguish "full" from
            // "empty" (both would otherwise read head == tail) - a classic
            // ring-buffer trade-off, not a bug
            "def onKeyboardIRQ(v) { " +
            "  nextHead = (Keyboard.head + 1) % Keyboard.bufferSize; " +
            "  if (nextHead == Keyboard.tail) { return nil; } " +
            "  poke8(Keyboard.bufferAddr + Keyboard.head, in8(Keyboard.dataPort)); " +
            "  Keyboard.head = nextHead; " +
            "} " +
            "registerHandler(onKeyboardIRQ, 9, 1); " +
            "def kbHasInput() { return Keyboard.head != Keyboard.tail; } " +
            "def kbReadChar() { " +
            "  c = peek8(Keyboard.bufferAddr + Keyboard.tail); " +
            "  Keyboard.tail = (Keyboard.tail + 1) % Keyboard.bufferSize; " +
            "  return c; " +
            "} ";

    private static void injectKey(Bl0jv2_jVM vm, int byteValue) {
        vm.hostPortWrite(5, 1, byteValue);
        vm.raiseInterrupt(9);
    }

    @Test
    void keystrokesArriveInOrderThroughTheFullStack() throws Exception {
        String source = DRIVER +
                "received = ''; " +
                "i = 0; " +
                "while (i < 3) { " +
                "  if (kbHasInput()) { received = received + str(kbReadChar()) + ','; i = i + 1; } " +
                "  else { haltCore(); } " +
                "} " +
                "print received;";

        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        var vm = new Bl0jv2_jVM();
        vm.set_interrupt_poll_interval(1);
        StringWriter sw = new StringWriter();
        vm.set_out_writer(sw);
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));

        Thread injector = new Thread(() -> {
            int[] keys = {65, 66, 67}; // 'A', 'B', 'C'
            for (int k : keys) {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ignored) {
                }
                injectKey(vm, k);
            }
        });
        injector.start();

        long start = System.nanoTime();
        vm.run_instructions();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("65,66,67,", sw.toString());
        // proves the consumer genuinely waited across all three deliveries
        // (haltCore()-based, not a lucky race) rather than having somehow
        // seen bytes that arrived before the first check
        assertTrue(elapsedMs >= 170, "finished suspiciously fast for 3x 60ms-apart deliveries: " + elapsedMs + "ms");
    }

    // fills the ring buffer without ever reading it - bufferSize=4 means 3
    // usable slots (see DRIVER's own "one slot always empty" comment), so
    // the 4th and 5th injected keys must be silently dropped, not corrupt
    // the buffer or overwrite unread input. Keys are spaced out (not fired
    // in a tight loop): hostPortWrite() is a single-byte "data register",
    // exactly like a real one - writing the next byte before the ISR has
    // read the current one would lose/corrupt data, a real hardware hazard
    // this test isn't trying to exercise. The consumer spins on a plain
    // counting loop instead of wait()/haltCore() while the keys arrive -
    // wait() blocks the whole interpreter (including its own cooperative
    // poll) for its full duration, so interrupts raised during it just pile
    // up unprocessed and get serviced late, by which point hostPortWrite()'s
    // single-byte port has already been overwritten by a later key entirely
    // (this is exactly what a first version of this test hit: a burst of
    // stale, duplicated bytes instead of a clean drop)
    @Test
    void fullBufferDropsExcessKeystrokesInsteadOfCorrupting() throws Exception {
        String source = DRIVER +
                // ordinary bytecode execution still polls every instruction
                // (pollInterval=1), so each key is serviced promptly, one at
                // a time, without ever draining the buffer meanwhile
                "n = 0; while (n < 20000000) { n = n + 1; } " +
                "out = ''; " +
                "while (kbHasInput()) { out = out + str(kbReadChar()) + ','; } " +
                "print out;";

        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        var vm = new Bl0jv2_jVM();
        vm.set_interrupt_poll_interval(1);
        StringWriter sw = new StringWriter();
        vm.set_out_writer(sw);
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));

        Thread injector = new Thread(() -> {
            // the program registers its keyboard handler as its first act; a key
            // raised before that is dropped (no handler yet), which made this test
            // lose its first key whenever the injector thread won the race
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            int[] keys = {1, 2, 3, 4, 5}; // 5 keys into a 3-usable-slot buffer
            for (int k : keys) {
                injectKey(vm, k);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                }
            }
        });
        injector.start();

        vm.run_instructions();

        assertEquals("1,2,3,", sw.toString());
    }
}
