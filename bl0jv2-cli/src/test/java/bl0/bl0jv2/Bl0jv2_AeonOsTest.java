package bl0.bl0jv2;

import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the aeon-os programs, run for real: compiled from source, executed, output
// checked. Until now nothing ran them, so a language change could break them
// unnoticed (arity checks did, in smp_boot: its dispatched tasks took no
// argument although dispatch() passes one).
class Bl0jv2_AeonOsTest {

    private static final Path AEON = Path.of("aeon-os");

    private static byte[] compile(String file) throws IOException {
        Path src = AEON.resolve(file);
        String source = Files.readString(src);
        var parser = new Bl0jv2_Parser();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        return new Bl0jv2_Compiler().compile(Bl0jv2_Linker.resolveImports(ast, src));
    }

    /** a running program: output so far, and a way to type on its keyboard */
    private static final class Session {
        final Bl0jv2_jVM vm = new Bl0jv2_jVM();
        final StringWriter out = new StringWriter();
        volatile Throwable failure;
        volatile boolean finished;
        Thread thread;

        void start(byte[] bytecode, int cores) {
            vm.set_core_count(cores);
            vm.set_out_writer(new PrintWriter(out));
            // feed and run on the SAME thread: that thread becomes core 0
            thread = new Thread(() -> {
                try {
                    vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
                    vm.run_instructions();
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    finished = true;
                }
            }, "aeon-core-0");
            thread.setDaemon(true);
            thread.start();
        }

        String output() {
            synchronized (out.getBuffer()) {
                return out.toString();
            }
        }

        boolean waitFor(String text, long ms) throws InterruptedException {
            long deadline = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < deadline) {
                if (output().contains(text)) return true;
                if (finished) return output().contains(text);
                Thread.sleep(20);
            }
            return output().contains(text);
        }

        // what the CLI's -k bridge does: a byte on port 0, then interrupt vector 2
        void type(String text) throws InterruptedException {
            for (char c : text.toCharArray()) {
                vm.hostPortWrite(0, 1, c);
                vm.raiseInterrupt(2);
                Thread.sleep(30);
            }
        }
    }

    @Test
    void childHelloRunsToCompletion() throws Exception {
        var s = new Session();
        s.start(compile("child_hello.bl0"), 1);
        assertTrue(s.waitFor("wrote and read back: 12648430", 10_000), s.output());
    }

    @Test
    void childCrashFailsWithItsDivisionByZero() throws Exception {
        var s = new Session();
        s.start(compile("child_crash.bl0"), 1);
        assertTrue(s.waitFor("about to fail", 10_000), s.output());
        s.thread.join(5000);
        assertTrue(s.failure != null && s.failure.getMessage().contains("division by zero"), String.valueOf(s.failure));
    }

    @Test
    void bootRunsTheSchedulerIsolatesTheFaultAndLaunchesChildren() throws Exception {
        var s = new Session();
        s.start(compile("boot.bl0"), 1);
        assertTrue(s.waitFor("aeon-shell ready", 20_000), s.output());
        String out = s.output();
        assertTrue(out.contains("[network] received packet: c0ffee"), out);
        assertTrue(out.contains("[fault] task faulty failed: division by zero"), out);
        assertTrue(out.contains("faults isolated by the scheduler: 1"), out);
        assertTrue(out.contains("pid 1 child_hello: ok"), out);
        assertTrue(out.contains("pid 2 child_crash: crashed"), out);
        // the shell answers on the keyboard, then exits
        s.type("help\r");
        assertTrue(s.waitFor("available commands: help", 10_000), s.output());
        s.type("exit\r");
        s.thread.join(10_000);
        assertTrue(s.finished, s.output());
    }

    @Test
    void smpBootDispatchesEverySixTaskAcrossThreeWorkerCores() throws Exception {
        var s = new Session();
        s.start(compile("smp_boot.bl0"), 4);
        assertTrue(s.waitFor("all 6 tasks completed across 3 worker cores", 20_000), s.output());
        String out = s.output();
        assertTrue(out.contains("[rogue] blocked: privileged instruction 'out' requires kernel mode"), out);
        assertTrue(out.contains("[network] running on core"), out);
        assertTrue(out.contains("[console] driver wrote 0xee to port 0"), out);
        assertEquals(3, out.split("\\[compute\\] running on core").length - 1, out);
        assertTrue(s.waitFor("aeon-shell ready", 10_000), out);
        s.type("exit\r");
        s.thread.join(10_000);
        assertTrue(s.finished, s.output());
    }

    @Test
    void theShellReactsToTypingWithoutPolling() throws Exception {
        var s = new Session();
        s.start(compile("shell.bl0"), 1);
        assertTrue(s.waitFor("aeon-shell ready", 10_000), s.output());
        Thread.sleep(300);                     // idle at the prompt: asleep in kbWait()
        s.type("echo hi there\r");
        assertTrue(s.waitFor("hi there", 5_000), s.output());
        s.type("exit\r");
        s.thread.join(10_000);
        assertTrue(s.finished && s.output().contains("aeon-shell exiting after 2 commands"), s.output());
    }
}
