package bl0.aeon;

import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** a running aeon-os program: its output so far, and a way to type on its simulated keyboard */
public final class AeonSession {
    private static final Path AEON = Path.of("aeon-os");

    public static byte[] compile(String file) throws IOException {
        Path src = AEON.resolve(file);
        String source = Files.readString(src);
        var parser = new Bl0jv2_Parser();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        return new Bl0jv2_Compiler().compile(Bl0jv2_Linker.resolveImports(ast, src));
    }

    public final Bl0jv2_jVM vm = new Bl0jv2_jVM();
    private final StringWriter out = new StringWriter();
    public volatile Throwable failure;
    public volatile boolean finished;
    public Thread thread;

    public void start(byte[] bytecode, int cores) {
        start(bytecode, cores, v -> { });
    }

    /** afterFeed runs once the program is loaded and before it runs - where host bridges attach (loading resets the port space) */
    public void start(byte[] bytecode, int cores, Consumer<Bl0jv2_jVM> afterFeed) {
        vm.set_core_count(cores);
        vm.set_out_writer(new PrintWriter(out));
        // feed and run on the SAME thread: that thread becomes core 0
        thread = new Thread(() -> {
            try {
                vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
                afterFeed.accept(vm);
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

    public String output() {
        synchronized (out.getBuffer()) {
            return out.toString();
        }
    }

    public boolean waitFor(String text, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (output().contains(text)) return true;
            if (finished) return output().contains(text);
            Thread.sleep(20);
        }
        return output().contains(text);
    }

    /** what the CLI's -k bridge does: a byte on port 0, then interrupt vector 2 */
    public void type(String text) throws InterruptedException {
        for (char c : text.toCharArray()) {
            vm.hostPortWrite(0, 1, c);
            vm.raiseInterrupt(2);
            Thread.sleep(30);
        }
    }
}
