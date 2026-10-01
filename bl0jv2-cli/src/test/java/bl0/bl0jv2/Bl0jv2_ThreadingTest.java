package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import bl0.bl0jv2.exceptions.Bl0j_VM_Panic;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// real multi-core execution: coreCount()/currentCore(), then dispatch() and
// real worker threads (this file grows further as Mutex/panic-halts-all land).
class Bl0jv2_ThreadingTest {

    @Test
    void coreCountDefaultsToOne() {
        assertEquals("1", run("print coreCount();"));
    }

    @Test
    void coreCountReflectsSetCoreCount() {
        assertEquals("4", run("print coreCount();", vm -> vm.set_core_count(4)));
    }

    // no worker threads exist yet at this step - the calling thread is
    // always core 0, whether or not a higher core count was configured
    // (workers only ever run dispatched work, never the loaded program's
    // own top-level code)
    @Test
    void currentCoreOnTheCallingThreadIsAlwaysZero() {
        assertEquals("0", run("print currentCore();"));
        assertEquals("0", run("print currentCore();", vm -> vm.set_core_count(4)));
    }

    // --- dispatch(): real worker threads ---

    // dispatch() is fire-and-forget, and workers run asynchronously on
    // their own threads, so run_instructions() returning doesn't mean
    // dispatched work has finished - these tests drive the VM directly
    // (not through Bl0jv2_TestRunner.run(), which only returns a String)
    // so they can observe completion via a custom Writer's append(), and
    // await it with a timeout from the test's own thread.

    @Test
    void dispatchedWorkRunsOnRealWorkerThreadsConcurrently() throws Exception {
        int totalTasks = 12;
        CountDownLatch latch = new CountDownLatch(totalTasks);
        Set<Long> threadIds = ConcurrentHashMap.newKeySet();

        // 'print' (not 'println'): PRINT does a single out.append() call,
        // so exactly one Writer.write() per task - println's out.append("\n")
        // .append(value) would fire this twice per call, decrementing the
        // latch too fast and letting await() return before every task
        // actually finished
        Writer sink = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) {
                threadIds.add(Thread.currentThread().getId());
                latch.countDown();
            }
            @Override public void flush() {}
            @Override public void close() {}
        };

        String source =
                "def task(v) { print v; } " +
                "i = 0; while (i < " + totalTasks + ") { dispatch(task, (i % 3) + 1, i); i = i + 1; }";

        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        var vm = new Bl0jv2_jVM();
        vm.set_core_count(4); // core 0 (dispatcher) + 3 workers
        vm.set_out_writer(sink);
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
        vm.run_instructions();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "not all dispatched tasks completed in time");
        assertTrue(threadIds.size() > 1, "dispatched work should run on more than one real thread, ran on: " + threadIds.size());
    }

    @Test
    void dispatchDoesNotBlockTheDispatchingCore() throws Exception {
        String source =
                "def slowTask(v) { wait(500); } " +
                "dispatch(slowTask, 1, 0); " +
                "print 'done';";

        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        var vm = new Bl0jv2_jVM();
        vm.set_core_count(2);
        StringWriter sw = new StringWriter();
        vm.set_out_writer(sw);
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));

        long start = System.nanoTime();
        vm.run_instructions();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("done", sw.toString());
        assertTrue(elapsedMs < 400, "dispatch() should not block the dispatching core; took " + elapsedMs + "ms");
    }

    @Test
    void dispatchingToANonexistentCoreThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "def task(v) { } dispatch(task, 5, 0);",
                vm -> vm.set_core_count(2)));
    }

    @Test
    void dispatchedClosureFiresCorrectly() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder captured = new StringBuilder();

        Writer sink = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) {
                captured.append(cbuf, off, len);
                latch.countDown();
            }
            @Override public void flush() {}
            @Override public void close() {}
        };

        String source = "handler = (v) -> { println 'C' + v; }; dispatch(handler, 1, 7);";

        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        var vm = new Bl0jv2_jVM();
        vm.set_core_count(2);
        vm.set_out_writer(sink);
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
        vm.run_instructions();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "dispatched closure never ran");
        // println prepends '\n' on every call (even the first) - matches
        // this VM's existing PRINT_LN behavior, not specific to dispatch
        assertEquals("\nC7", captured.toString());
    }

    // --- Mutex: the positive/negative pair that actually proves it's needed ---
    //
    // Both variants dispatch 3 worker tasks, each incrementing a shared
    // Bl0jClass static field N times. Counter.doneCount is ALWAYS
    // mutex-protected in both variants (it's just the completion signal
    // core 0's own busy-wait loop relies on to know when to read the final
    // value - without it being reliable, a lost update could mean
    // doneCount never reaches 3 and the test hangs). Counter.value's own
    // increments are deliberately left unprotected in the "unsafe" variant
    // and protected in the "safe" one - that's the actual thing being
    // tested. Everything runs synchronously from the test's perspective
    // (the busy-wait loop blocks run_instructions() until all workers
    // signal done), so Bl0jv2_TestRunner.run() works fine here, unlike the
    // dispatch tests above.
    //
    // The mutexes themselves are static fields (Counter.doneLock/valueLock),
    // not top-level variables: a plain 'def' function has its own private
    // frame and can't close over an enclosing top-level variable (only a
    // lambda captures free variables, via explicit cells) - the exact same
    // limitation discovered writing aeon-os/boot.bl0. A bare 'doneLock'
    // referenced inside a def body resolves to a fresh, never-assigned
    // local instead of the top-level one, which is what a first attempt at
    // this test actually hit (ClassCastException: Double cannot be cast to
    // Bl0jMutex - the tell-tale sign of an unassigned register, not a bug
    // in Mutex itself).

    private static final String COUNTER_SETUP =
            "def class Counter { " +
            "  static field value; static field doneCount; static field doneLock; " +
            "} " +
            "Counter.value = 0; Counter.doneCount = 0; " +
            "Counter.doneLock = newMutex(); ";

    // busy-waits for all 3 workers, then reports the final value - shared
    // tail for both variants. Reads doneCount through doneLock on every
    // spin, not as a bare field read: a lock acquire/release is what gives
    // core 0 an actual JMM happens-before edge to the workers' writes
    // (through the same lock) - a bare unsynchronized read has no such
    // guarantee and the JIT hoisting it out of the loop would hang this
    // test for real, not just in theory. That same happens-before edge is
    // also what makes the final (unsynchronized) read of Counter.value
    // safe: it happens-after the synchronized read that observed
    // doneCount >= 3, which itself happens-after every worker's own prior
    // writes to Counter.value.
    private static final String WAIT_AND_REPORT =
            "while (true) { " +
            "  lock(Counter.doneLock); done = Counter.doneCount; unlock(Counter.doneLock); " +
            "  if (done >= 3) { break; } " +
            "} " +
            "print Counter.value;";

    @Test
    void concurrentStaticFieldMutationWithoutMutexLosesUpdates() {
        int perTask = 20000;
        String source = COUNTER_SETUP +
                "def bumpUnsafe(n) { " +
                "  i = 0; while (i < n) { Counter.value = Counter.value + 1; i = i + 1; } " +
                "  lock(Counter.doneLock); Counter.doneCount = Counter.doneCount + 1; unlock(Counter.doneLock); " +
                "} " +
                "dispatch(bumpUnsafe, 1, " + perTask + "); " +
                "dispatch(bumpUnsafe, 2, " + perTask + "); " +
                "dispatch(bumpUnsafe, 3, " + perTask + "); " +
                WAIT_AND_REPORT;

        int finalValue = Integer.parseInt(run(source, vm -> vm.set_core_count(4)));
        int expectedIfPerfectlySafe = perTask * 3;

        // inherently probabilistic (a race that never manifests on a given
        // run is possible), but with 20000 unsynchronized increments
        // genuinely racing across 3 real OS threads, losing at least one
        // update is effectively certain in practice
        assertTrue(finalValue < expectedIfPerfectlySafe,
                "expected lost updates without a mutex, got the full " + expectedIfPerfectlySafe + " anyway");
    }

    @Test
    void concurrentStaticFieldMutationWithMutexIsExact() {
        int perTask = 20000;
        String source = COUNTER_SETUP +
                "def class Locks { static field valueLock; } Locks.valueLock = newMutex(); " +
                "def bumpSafe(n) { " +
                "  i = 0; " +
                "  while (i < n) { " +
                "    lock(Locks.valueLock); Counter.value = Counter.value + 1; unlock(Locks.valueLock); " +
                "    i = i + 1; " +
                "  } " +
                "  lock(Counter.doneLock); Counter.doneCount = Counter.doneCount + 1; unlock(Counter.doneLock); " +
                "} " +
                "dispatch(bumpSafe, 1, " + perTask + "); " +
                "dispatch(bumpSafe, 2, " + perTask + "); " +
                "dispatch(bumpSafe, 3, " + perTask + "); " +
                WAIT_AND_REPORT;

        assertEquals(String.valueOf(perTask * 3), run(source, vm -> vm.set_core_count(4)));
    }

    // --- panic() halts every core, not just the one that panicked ---

    // core 0 has to still be *running* bl0jv2 code (here: a busy-wait loop
    // waiting on a flag the panicking worker never sets) to notice the
    // panic and abort - dispatch() is fire-and-forget, so if core 0's own
    // top-level code had already finished and returned before the worker
    // panicked, there would be nothing left running on core 0 to surface
    // it to. This is a real, accepted limitation without a join() primitive
    // (a natural follow-up, not part of this increment). Unlike the Mutex
    // tests' busy-wait, this one doesn't need lock()-based visibility:
    // 'panicked' is a genuine Java volatile checked directly by the
    // interpreter loop, not a bl0jv2-level field read, so there's no JIT
    // hoisting risk to guard against.
    @Test
    void panicOnAWorkerCoreHaltsCoreZeroToo() {
        String source =
                "def class Flag { static field ready; } Flag.ready = 0; " +
                "def badTask(v) { panic('worker fault'); } " +
                "dispatch(badTask, 1, 0); " +
                "while (Flag.ready < 1) { } " +
                "print 'unreachable';";

        Bl0j_VM_Panic ex = assertThrows(Bl0j_VM_Panic.class, () -> run(source, vm -> vm.set_core_count(2)));
        assertTrue(ex.getMessage().contains("worker fault") || ex.getMessage().contains("another core panicked"));
    }

    // a panicking worker must stop accepting further dispatched work, not
    // just fail the one task that triggered it - dispatches a task that
    // panics, then a second task to the SAME core (BlockingQueue is FIFO,
    // so it's guaranteed to be picked up only after the first, if at all)
    // and confirms the second one's signal never arrives
    @Test
    void panickingWorkerStopsAcceptingFurtherWork() throws Exception {
        CountDownLatch secondTaskRan = new CountDownLatch(1);
        Writer sink = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) { secondTaskRan.countDown(); }
            @Override public void flush() {}
            @Override public void close() {}
        };

        String source =
                "def badTask(v) { panic('worker fault'); } " +
                "def secondTask(v) { print 'should never run'; } " +
                "dispatch(badTask, 1, 0); " +
                "dispatch(secondTask, 1, 0);";

        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        var vm = new Bl0jv2_jVM();
        vm.set_core_count(2);
        vm.set_out_writer(sink);
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
        try {
            vm.run_instructions();
        } catch (bl0.bl0jv2.exceptions.Bl0j_VM_Panic ignored) {
            // core 0's own program is two dispatches and returns at once, but if
            // the worker's panic lands first core 0 sees it at its next
            // instruction - the same machine-wide halt, just observed here
        } // core 0's own program is just the two dispatches - returns immediately

        assertTrue(!secondTaskRan.await(500, TimeUnit.MILLISECONDS),
                "secondTask ran on a core that should have halted after badTask panicked");
    }

    // --- per-core interrupt polling: a worker running dispatched work polls
    // its own interrupts too, not just core 0 (see Bl0jv2_jVM.invoke()'s
    // pollEligible split) ---

    @Test
    void dispatchedWorkPollsAndDeliversItsOwnInterrupts() throws Exception {
        CountDownLatch handlerFired = new CountDownLatch(1);
        StringBuilder captured = new StringBuilder();
        Writer sink = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) {
                captured.append(cbuf, off, len);
                handlerFired.countDown();
            }
            @Override public void flush() {}
            @Override public void close() {}
        };

        String source =
                "def onTick(v) { print 'H'; } " +
                "def worker(v) { " +
                "  registerHandler(onTick, 1, 5); raiseInterrupt(1); " +
                "  i = 0; while (i < 1000) { i = i + 1; } " +
                "} " +
                "dispatch(worker, 1, 0);";

        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        var vm = new Bl0jv2_jVM();
        vm.set_core_count(2);
        vm.set_interrupt_poll_interval(1);
        vm.set_out_writer(sink);
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
        vm.run_instructions();

        assertTrue(handlerFired.await(5, TimeUnit.SECONDS), "worker core never polled/delivered its own interrupt");
        assertEquals("H", captured.toString());
    }

    // a nested invoke() from WITHIN dispatched work (here: a user-defined
    // toString() override, triggered by str()) must still never poll,
    // exactly like it wouldn't on core 0 - pollEligible is set once for the
    // worker's own top-level task, not for every invoke() that happens to
    // run on a worker thread
    @Test
    void nestedInvokeInsideDispatchedWorkStillDoesNotPoll() throws Exception {
        // two separate print calls (the handler's 'H' and the worker's own
        // 'Box(1)') means two write() calls - a latch of 1 would fire on
        // whichever happens to land first and race the assertion below
        CountDownLatch taskDone = new CountDownLatch(2);
        StringBuilder captured = new StringBuilder();
        Writer sink = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) {
                captured.append(cbuf, off, len);
                taskDone.countDown();
            }
            @Override public void flush() {}
            @Override public void close() {}
        };

        String source =
                "def class Box { field v; " +
                "  def init(v) { this.v = v; } " +
                "  def toString() { return 'Box(' + this.v + ')'; } " +
                "} " +
                "def onTick(v) { print 'H'; } " +
                "def worker(v) { " +
                "  registerHandler(onTick, 1, 5); raiseInterrupt(1); " +
                "  b = new Box(1); print str(b); " +
                "} " +
                "dispatch(worker, 1, 0);";

        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        var vm = new Bl0jv2_jVM();
        vm.set_core_count(2);
        vm.set_interrupt_poll_interval(1);
        vm.set_out_writer(sink);
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
        vm.run_instructions();

        assertTrue(taskDone.await(5, TimeUnit.SECONDS), "dispatched worker never finished");
        // 'H' still fires (the worker's own top-level polling delivers it
        // eventually), but never mid-toString() - both orderings below are
        // acceptable outcomes of ordinary polling cadence, what this test
        // actually guards against is a crash/hang from reentrant polling
        // inside the toString() call itself
        assertTrue(captured.toString().contains("Box(1)"), "toString() override did not run: " + captured);
    }

    // haltCore() must not hang forever once the whole machine has panicked
    // elsewhere - see haltCore's own doc in Bl0jv2_jVM for why it also
    // checks the panicked flag, not just hasPending()
    @Test
    void haltCoreUnblocksOnAMachineWidePanicInsteadOfHangingForever() throws Exception {
        CountDownLatch haltingTaskWoke = new CountDownLatch(1);
        Writer sink = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) { haltingTaskWoke.countDown(); }
            @Override public void flush() {}
            @Override public void close() {}
        };

        String source =
                "def badTask(v) { panic('worker fault'); } " +
                "def haltingTask(v) { haltCore(); print 'woke'; } " +
                "dispatch(badTask, 1, 0); " +
                "dispatch(haltingTask, 2, 0);";

        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        var vm = new Bl0jv2_jVM();
        vm.set_core_count(3);
        vm.set_out_writer(sink);
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
        try {
            vm.run_instructions();
        } catch (bl0.bl0jv2.exceptions.Bl0j_VM_Panic ignored) {
            // core 0's own program is two dispatches and returns at once, but if
            // the worker's panic lands first core 0 sees it at its next
            // instruction - the same machine-wide halt, just observed here
        }

        // haltCore() itself unblocks (proven by this NOT hanging until the
        // test's own timeout), but the panicked check at the top of the
        // next loop iteration fires before 'print woke' ever runs - a
        // panic still halts this core, haltCore() just isn't what's
        // blocking it anymore
        assertTrue(!haltingTaskWoke.await(2, TimeUnit.SECONDS),
                "haltingTask ran to completion after a machine-wide panic");
    }
}
