package bl0.bl0jv2.cli;

import bl0.bl0jv2.Bl0jv2_Utils;
import bl0.bl0jv2.runtime.Bl0jv2_jVM;
import bl0.bl0jv2.data.C;
import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class Bl0jv2_CLI {

    private Path source;
    private Path dest;
    private boolean compile;
    private boolean dump;
    private boolean execute;
    private boolean help;
    private boolean version;
    private boolean terminal;
    private boolean keyboard;
    // 1 (single core, no worker threads spawned - see set_core_count's own
    // doc) matches every bl0jv2 program except aeon-os/smp_boot.bl0, which
    // needs one specific count (its own Kernel.numCores) to dispatch onto
    // real worker cores at all - there is no bl0jv2-language builtin for
    // this (set_core_count is a Java-only VM API - see its own doc on why:
    // it has to run before run_instructions() spawns the workers, earlier
    // than any bl0jv2 code gets to run at all), so it has to come from here
    private int cores = 1;
    // -1 = disabled. The real host UDP port to bind - see UdpBridge's own
    // doc for what this actually relays and the fake-IP addressing it uses
    private int bridgeUdpPort = -1;
    // -1 = disabled. The real host TCP port to bind. bridgeTcpVmPort is the
    // bl0jv2-side port a tcpListen() is bound to - TCP is connection-
    // oriented (unlike UDP), so TcpBridge has to know where to address its
    // own fake SYN, not just relay whatever arrives - see its own doc
    private int bridgeTcpPort = -1;
    private int bridgeTcpVmPort = -1;
    // lets bl0jv2 code REACH OUT for real too (tcpConnect()/udpSend(), and
    // stdlib/net/dns.bl0's own dnsResolve() on top of the latter - see
    // TcpOutboundBridge/UdpOutboundBridge's own doc), the reverse of
    // --bridge-udp/--bridge-tcp above (a real peer reaching IN). Separate
    // flag rather than always-on together with those: opening real
    // outbound connections on the VM's own say-so is a bigger trust step
    // than just answering whoever already knows to connect to a chosen
    // local port, worth requiring explicitly.
    private boolean bridgeOutbound = false;

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-t", "--terminal", "-terminal" -> terminal = true;
                case "-c", "--compile" -> compile = true;
                case "-d", "--dump"    -> dump    = true;
                case "-e", "--execute" -> execute = true;
                case "-k", "--keyboard" -> keyboard = true;
                case "-n", "--cores" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--cores requires a value");
                        printHelp();
                        System.exit(1);
                    }
                    try {
                        cores = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println("--cores value must be an integer: " + args[i]);
                        System.exit(1);
                    }
                }
                case "-b", "--bridge-udp" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--bridge-udp requires a port number");
                        printHelp();
                        System.exit(1);
                    }
                    try {
                        bridgeUdpPort = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println("--bridge-udp port must be an integer: " + args[i]);
                        System.exit(1);
                    }
                }
                case "--bridge-tcp" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--bridge-tcp requires <hostPort>:<vmPort>");
                        printHelp();
                        System.exit(1);
                    }
                    String spec = args[++i];
                    int colon = spec.indexOf(':');
                    if (colon < 0) {
                        System.err.println("--bridge-tcp value must be <hostPort>:<vmPort>, got: " + spec);
                        System.exit(1);
                    }
                    try {
                        bridgeTcpPort = Integer.parseInt(spec.substring(0, colon));
                        bridgeTcpVmPort = Integer.parseInt(spec.substring(colon + 1));
                    } catch (NumberFormatException e) {
                        System.err.println("--bridge-tcp ports must be integers: " + spec);
                        System.exit(1);
                    }
                }
                case "--bridge-outbound" -> bridgeOutbound = true;
                case "-h", "--help"    -> help    = true;
                case "-V", "--version" -> version = true;
                default -> {
                    if (args[i].startsWith("-")) {
                        System.err.println("Unknown option: " + args[i]);
                        printHelp();
                        System.exit(1);
                    }
                    if (source == null)       source = Path.of(args[i]);
                    else if (dest == null)    dest   = Path.of(args[i]);
                    else {
                        System.err.println("Unexpected argument: " + args[i]);
                        printHelp();
                        System.exit(1);
                    }
                }
            }
        }
    }

    private void printHelp() {
        System.out.println("Usage: bl0jv2 [-cdektVh] [-n <cores>] <source> [<dest>]");
        System.out.println();
        System.out.println("Parameters:");
        System.out.println("  <source>       source file");
        System.out.println("  [<dest>]       destination file (optional)");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -c, --compile   compile");
        System.out.println("  -d, --dump      shows file info");
        System.out.println("  -e, --execute   execute");
        System.out.println("  -k, --keyboard  bridge real stdin to aeon-os's simulated keyboard");
        System.out.println("                  (port 0, interrupt vector 2 - the convention");
        System.out.println("                  aeon-os/boot.bl0 and aeon-os/shell.bl0 both use);");
        System.out.println("                  only meaningful together with -e");
        System.out.println("  -n, --cores N   worker core count (core 0 + N-1 workers); default 1.");
        System.out.println("                  aeon-os/smp_boot.bl0 needs -n 4 to match its own");
        System.out.println("                  Kernel.numCores; only meaningful together with -e");
        System.out.println("  -b, --bridge-udp PORT  relay a real host UDP socket (bound to PORT)");
        System.out.println("                  into stdlib/net/nic.bl0's own host-bridge ports, so a");
        System.out.println("                  bl0jv2 program using stdlib/net/udp.bl0 can talk to a");
        System.out.println("                  real external program (curl/netcat/etc) - the bl0jv2");
        System.out.println("                  program must call Nic.initWithHostBridge(), not plain");
        System.out.println("                  Nic.init(); only meaningful together with -e");
        System.out.println("      --bridge-tcp HOSTPORT:VMPORT  relay a real host TCP socket (bound");
        System.out.println("                  to HOSTPORT) to a bl0jv2 TcpConn.listen()/Http.serve() on");
        System.out.println("                  VMPORT - e.g. curl http://localhost:HOSTPORT/ reaches a");
        System.out.println("                  bl0jv2 Http.serve(0x0A000001, VMPORT, handler). One real");
        System.out.println("                  connection at a time (see TcpConn.listen()'s own doc); the");
        System.out.println("                  bl0jv2 program must call Nic.initWithHostBridge(), not");
        System.out.println("                  plain Nic.init(); only meaningful together with -e");
        System.out.println("      --bridge-outbound  lets bl0jv2 code reach OUT for real - a program's");
        System.out.println("                  own TcpConn.connect() opens a real socket to wherever it");
        System.out.println("                  names, and stdlib/net/dns.bl0's Dns.resolve() answers for");
        System.out.println("                  real too. The reverse of -b/--bridge-tcp above (a real");
        System.out.println("                  peer reaching IN); only meaningful together with -e");
        System.out.println("  -h, --help      show this help message and exit");
        System.out.println("  -V, --version   print version information and exit");
    }

    private void printVersion() {
        System.out.println("bl0jv2 " + C.VERSION);
    }

    private static Writer consoleAutoFlushWriter() {
        return new Writer() {
            @Override public void write(char[] cbuf, int off, int len) {
                System.out.print(new String(cbuf, off, len));
                System.out.flush();
            }
            @Override public void flush() { System.out.flush(); }
            @Override public void close() {}
        };
    }

    // reads real lines from stdin on a background thread and feeds each
    // character into the VM's simulated keyboard exactly the way a real
    // keyboard controller would: hostPortWrite() places the byte on its
    // data port, raiseInterrupt() asserts the IRQ line - see
    // Bl0jv2_jVM.hostPortWrite()'s own doc. One VM instance, one bridge
    // thread, for the whole run - exec() (see its own doc) runs loaded
    // programs IN this same instance now, not a second one, so there is
    // no "which process is currently running" redirection to do: whatever
    // code registered a handler for vector 2, in this VM, sees it.
    //
    // A small pacing delay between characters avoids overwriting one byte
    // with the next before the running program's own interrupt handler has
    // read it (a real hardware hazard, not just a simulation quirk - see
    // Bl0jv2_KeyboardTest's own doc on the same race). readLine() blocks
    // on real terminal input, so this only ever makes sense against an
    // interactive stdin, not a redirected/empty one.
    private static void startKeyboardBridge(Bl0jv2_jVM vm) {
        Thread bridge = new Thread(() -> {
            var reader = new BufferedReader(new InputStreamReader(System.in));
            try {
                // gives the program a head start to reach its own
                // registerHandler() call before the first byte arrives -
                // an interrupt raised before anything is registered for
                // its vector is silently dropped (see InterruptController's
                // own doc), losing the very first keystroke. A real human
                // typing their first command takes far longer than this to
                // even start, so this delay is never actually felt in
                // interactive use - it only matters against piped input,
                // where the very first character could otherwise arrive
                // within microseconds of the program starting
                Thread.sleep(150);
                // a real human typing is always paced far looser than
                // this anyway - these delays only matter when testing
                // against piped/redirected input, where an entire line
                // (or several) is available to readLine() instantly, with
                // none of a real keyboard's natural inter-key delay
                String line;
                while ((line = reader.readLine()) != null) {
                    for (char c : line.toCharArray()) {
                        vm.hostPortWrite(0, 1, c);
                        vm.raiseInterrupt(2);
                        Thread.sleep(20);
                    }
                    vm.hostPortWrite(0, 1, 13); // Enter
                    vm.raiseInterrupt(2);
                    Thread.sleep(60);
                }
            } catch (IOException | InterruptedException ignored) {
                // stdin closed or the JVM is shutting down - nothing left
                // for this bridge to do either way
            }
        }, "keyboard-bridge");
        bridge.setDaemon(true);
        bridge.start();
    }

    private void runTerminal() {
        var lexer = new Bl0jv2_Lexer();
        var parser = new Bl0jv2_Parser();
        var compiler = new Bl0jv2_Compiler();
        var vm = new Bl0jv2_jVM();

        var writer = new PrintWriter(System.out);
        vm.set_out_writer(writer);
        writer.print("!exit - to exit");
        writer.print("> ");

        var scanner = new Scanner(System.in);

        writer.flush();
        while (true) {
            try {
                String line = scanner.nextLine().trim();
                if(line.equals("!exit")) break;

                byte[] instructions = compiler.compile(parser.getAST(lexer.getTokens(line)));

                Bl0jv2_Utils.dump_file(instructions, writer);

                writer.println();
                vm.feed_compiled_file(ByteBuffer.wrap(instructions));
                vm.run_instructions();
            } catch (Exception e) {
                writer.println(e.getMessage());
                e.printStackTrace();
            } finally {
                writer.println();
                writer.print("> ");
                writer.flush();
            }
        }
    }

    private int run() throws IOException {

        if(terminal) {
            runTerminal();
            return 0;
        }

        if (help) {
            printHelp();
            return 0;
        }

        if (version) {
            printVersion();
            return 0;
        }

        if (source == null) {
            System.err.println("Missing required parameter: <source>");
            printHelp();
            return 1;
        }

        if (Files.notExists(source)) {
            System.err.println("source not exists: " + source);
            return 1;
        }

        byte[] bytes;
        Timer timer = new Timer();
        Path pathToExec = source;

        if (compile) {
            if (dest == null)
                dest = source.getParent().resolve(source.getFileName() + ".bl0c");

            var lexer = new Bl0jv2_Lexer();
            var parser = new Bl0jv2_Parser();
            var compiler = new Bl0jv2_Compiler();

            System.out.println();
            String data = Files.readString(source);
            var tokens = lexer.getTokens(data);
            timer.mark("lexer");
            System.out.printf("lexer done: (%d tokens)%n", tokens.size());

            parser.setSourceCode(data);
            var ast = parser.getAST(tokens);
            timer.mark("parser");
            System.out.println("parser done");

            if (!(ast instanceof PROGRAM_N program))
                throw new IllegalStateException("parser did not produce a program");
            var linked = Bl0jv2_Linker.resolveImports(program, source);

            bytes = compiler.compile(linked);
            timer.mark("compiler");
            System.out.printf("compiler done: (%d bytes)%n", bytes.length);

            System.out.println();
            timer.report();
            Files.write(dest, bytes);
            System.out.println();
            System.out.println("written: " + dest);

            pathToExec = dest;
        } else {
            bytes = Files.readAllBytes(source);
        }

        if (dump || execute) {
            var writer = new PrintWriter(System.out);
            System.out.println();

            if (dump)
                Bl0jv2_Utils.dump_file(bytes, writer);

            if (execute) {
                var vm = new Bl0jv2_jVM();
                // must happen before run_instructions() spawns worker
                // threads (see set_core_count's own doc) - feed_compiled_file
                // itself doesn't care about ordering, so either side of it is
                // fine, this just keeps VM setup grouped together
                vm.set_core_count(cores);
                vm.feed_compiled_file(ByteBuffer.wrap(bytes));
                // -k needs output to appear as the program prints it, not
                // buffered until run_instructions() returns (which, for an
                // interactive keyboard-driven program, might be "never
                // until the user types 'exit'") - a plain PrintWriter over
                // System.out has no such guarantee without an explicit
                // flush after every write
                Writer outWriter = (keyboard || bridgeUdpPort >= 0 || bridgeTcpPort >= 0 || bridgeOutbound)
                        ? consoleAutoFlushWriter() : writer;
                vm.set_out_writer(outWriter);
                if (keyboard) {
                    vm.set_interrupt_poll_interval(1);
                    startKeyboardBridge(vm);
                }
                UdpBridge udpBridge = null;
                if (bridgeUdpPort >= 0) {
                    vm.set_interrupt_poll_interval(1);
                    try {
                        udpBridge = new UdpBridge(vm, bridgeUdpPort, 0x0A000001);
                        udpBridge.start();
                    } catch (java.net.SocketException e) {
                        System.err.println("--bridge-udp: cannot bind host port " + bridgeUdpPort + ": " + e.getMessage());
                        return 1;
                    }
                }
                if (bridgeTcpPort >= 0) {
                    vm.set_interrupt_poll_interval(1);
                    // 0x0A000001/0x0A000002: same local/remote fake-address
                    // convention as every stdlib/net test and demo in this
                    // project (see aeon-os/kernel.bl0 and Bl0jv2_StdlibTest)
                    try {
                        new TcpBridge(vm, bridgeTcpPort, 0x0A000001, bridgeTcpVmPort, 0x0A000002).start();
                    } catch (IOException e) {
                        System.err.println("--bridge-tcp: cannot bind host port " + bridgeTcpPort + ": " + e.getMessage());
                        return 1;
                    }
                }
                if (bridgeOutbound) {
                    vm.set_interrupt_poll_interval(1);
                    new TcpOutboundBridge(vm).start();
                    new UdpOutboundBridge(vm, udpBridge == null ? f -> false : udpBridge::claims).start();
                }
                System.out.println();
                // flush in finally: a crash mid-program must not discard
                // whatever it already printed before the exception
                try {
                    vm.run_instructions();
                } finally {
                    outWriter.flush();
                }
            }
        }

        System.out.println();
        System.out.println("done: " + pathToExec);
        System.out.println();
        return 0;
    }

    public static void main(String[] args) throws IOException {
        var cli = new Bl0jv2_CLI();
        cli.parseArgs(args);
        System.exit(cli.run());
    }
}