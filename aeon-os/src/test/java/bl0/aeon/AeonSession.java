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
    /** the console is a text-mode display (output then goes to the screen, not to output()) */
    public boolean display;
    public Thread thread;

    public void start(byte[] bytecode, int cores) {
        start(bytecode, cores, v -> { });
    }

    /** afterFeed runs once the program is loaded and before it runs - where host bridges attach (loading resets the port space) */
    public void start(byte[] bytecode, int cores, Consumer<Bl0jv2_jVM> afterFeed) {
        vm.set_core_count(cores);
        if (display) vm.attach_display();
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

    /** a shell on a copy of the OS disk, in 'dir' (the image file is dir/d.img) */
    static AeonSession shellOnOsDisk(java.nio.file.Path dir) throws Exception {
        return shellOn(dir.resolve("d.img"), true);
    }

    /** the same on a machine with 'cores' cores: the shell on core 0, background jobs and pipeline stages on the others */
    static AeonSession shellOnOsDisk(java.nio.file.Path dir, int cores) throws Exception {
        return shellOn(dir.resolve("d.img"), true, null, cores);
    }

    /** a shell on the image at 'image'; fresh = start from a new copy of the OS disk, else keep what is there */
    static AeonSession shellOn(java.nio.file.Path image, boolean fresh) throws Exception {
        return shellOn(image, fresh, null);
    }

    /** the same, with a host folder shared (--bridge-fs) when 'share' is not null */
    static AeonSession shellOn(java.nio.file.Path image, boolean fresh, java.nio.file.Path share) throws Exception {
        return shellOn(image, fresh, share, 1);
    }

    static AeonSession shellOn(java.nio.file.Path image, boolean fresh, java.nio.file.Path share, int cores) throws Exception {
        var s = new AeonSession();
        s.start(compile("init.bl0"), cores, vm -> {
            try {
                if (share != null) vm.attach_share(new bl0.bl0jv2.cli.DirShare(share));
                vm.attach_disk(fresh ? AeonImage.os(image) : new bl0.bl0jv2.cli.FileDisk(image, AeonImage.SECTORS));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        if (!s.waitFor("aeon-shell ready", 20_000)) throw new AssertionError(s.output() + " failure=" + s.failure);
        return s;
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

    /** what the terminal sends when the user types: these characters as UTF-8, escape sequences included */
    public void type(String text) throws InterruptedException {
        vm.uart_receive(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Thread.sleep(30);
    }

    /** the screen as an 80x24 terminal would show it now */
    VirtualTerminal screen() {
        if (display) {
            var frame = vm.display_frame();
            return frame == null ? new VirtualTerminal(80, 24) : VirtualTerminal.fromFrame(frame);   // null until the guest has set one up
        }
        return VirtualTerminal.render(output());
    }

    /** a shell whose console is a text-mode display */
    static AeonSession shellOnDisplay(java.nio.file.Path dir) throws Exception {
        var s = new AeonSession();
        s.display = true;
        s.start(compile("init.bl0"), 1, vm -> {
            try {
                vm.attach_disk(AeonImage.os(dir.resolve("d.img")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && !s.screen().screenText().contains("aeon-shell ready")) Thread.sleep(20);
        if (!s.screen().screenText().contains("aeon-shell ready")) throw new AssertionError(s.screen().screenText() + " failure=" + s.failure);
        return s;
    }

    /** runs kernel-level code that may import aeon-os files, on a machine with a display; returns the screen afterwards */
    static bl0.bl0jv2.runtime.device.DisplayController.Frame runOnDisplay(String source, int columns, int rows) throws Exception {
        var parser = new Bl0jv2_Parser();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        byte[] bytecode = new Bl0jv2_Compiler().compile(Bl0jv2_Linker.resolveImports(ast, AEON.resolve("snippet.bl0")));
        var vm = new Bl0jv2_jVM();
        vm.set_out_writer(new PrintWriter(new StringWriter()));
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
        vm.set_console_size(columns, rows);
        vm.attach_display();
        vm.run_instructions();
        return vm.display_frame();
    }

    /** compiles and runs a snippet that may import aeon-os files (relative to aeon-os/); returns what it printed */
    static String runSnippet(String source) throws Exception {
        var parser = new Bl0jv2_Parser();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        byte[] bytecode = new Bl0jv2_Compiler().compile(Bl0jv2_Linker.resolveImports(ast, AEON.resolve("snippet.bl0")));
        var vm = new Bl0jv2_jVM();
        StringWriter out = new StringWriter();
        vm.set_out_writer(new PrintWriter(out));
        vm.feed_compiled_file(ByteBuffer.wrap(bytecode));
        vm.run_instructions();
        return out.toString();
    }
}
