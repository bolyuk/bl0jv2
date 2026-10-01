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
    // -I: extra directories an import is looked up in when it is not found next to the importing file
    private final java.util.List<Path> includeDirs = new java.util.ArrayList<>();
    // --disk: a host file presented to the program as a block device (see DiskController)
    private Path diskImage = null;
    // --bridge-fs: one host folder shown to the program (see DirShare)
    private Path bridgeFsDir = null;
    // --uart-baud: how fast the serial port sends (0 = instantly)
    private int uartBaud = 0;
    // --shared: a manifest of shared libraries; programs put on the disk link to them, and they are put there too
    private Path sharedManifest = null;
    // --display: the guest's text-mode display, drawn on the host terminal (see HostScreen)
    private boolean display = false;
    private final java.util.List<DiskImport.Spec> diskPuts = new java.util.ArrayList<>();
    private int diskSectors = 2048; // 1 MiB, used only when the image does not exist yet

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
                case "-I", "--include" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--include requires a directory");
                        System.exit(1);
                    }
                    includeDirs.add(Path.of(args[++i]));
                }
                case "--display" -> display = true;
                case "--shared" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--shared requires a manifest file");
                        System.exit(1);
                    }
                    sharedManifest = Path.of(args[++i]);
                }
                case "--uart-baud" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--uart-baud requires a number");
                        System.exit(1);
                    }
                    try {
                        uartBaud = Integer.parseInt(args[++i]);
                        if (uartBaud < 0) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        System.err.println("--uart-baud must be a non-negative integer: " + args[i]);
                        System.exit(1);
                    }
                }
                case "--bridge-fs" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--bridge-fs requires a folder");
                        System.exit(1);
                    }
                    bridgeFsDir = Path.of(args[++i]);
                }
                case "--disk" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--disk requires an image file");
                        System.exit(1);
                    }
                    diskImage = Path.of(args[++i]);
                }
                case "--disk-put" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--disk-put requires HOSTFILE[:NAME]");
                        System.exit(1);
                    }
                    diskPuts.add(DiskImport.Spec.parse(args[++i]));
                }
                case "--disk-sectors" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--disk-sectors requires a number");
                        System.exit(1);
                    }
                    try {
                        diskSectors = Integer.parseInt(args[++i]);
                        if (diskSectors < 16) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        System.err.println("--disk-sectors must be an integer of at least 16: " + args[i]);
                        System.exit(1);
                    }
                }
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
        System.out.println("  -I, --include DIR  look an import up in DIR when it is not found next to");
        System.out.println("                  the file that names it (repeatable)");
        System.out.println("      --disk FILE  present FILE to the program as a block device (512-byte");
        System.out.println("                  sectors, ports 0x0F00-0x0F0D); created when missing");
        System.out.println("      --disk-put HOSTFILE[:NAME]  copy a host file onto the --disk image first");
        System.out.println("                  (formats a blank image); a .bl0 file is compiled and stored as");
        System.out.println("                  .bl0c, ready for the shell's exec (repeatable)");
        System.out.println("      --display  give the program a text-mode display (80x24 or the terminal's size)");
        System.out.println("                  and draw it on this terminal; the console then goes to the screen,");
        System.out.println("                  not to the serial line. Use together with -k for the keyboard");
        System.out.println("      --shared MANIFEST  shared libraries (one source file per line, in load order):");
        System.out.println("                  programs put on the disk with --disk-put do not contain their code but");
        System.out.println("                  link to them when loaded, and the libraries are put on the disk too");
        System.out.println("                  (lib/NAME.bl0c and lib/MANIFEST)");
        System.out.println("      --uart-baud N  send on the serial port at N bits per second (default 0:");
        System.out.println("                  instantly); a driver that ignores the line status loses text");
        System.out.println("      --bridge-fs DIR  show the host folder DIR to the program (read, write,");
        System.out.println("                  list, delete inside it only; the guest's hls/hget/hput)");
        System.out.println("      --disk-sectors N  size of a newly created image (default 2048)");
        System.out.println("  -h, --help      show this help message and exit");
        System.out.println("  -V, --version   print version information and exit");
    }

    private void printVersion() {
        System.out.println("bl0jv2 " + C.VERSION);
    }

    // the guest's output is Unicode text; write it as UTF-8 whatever the host's default charset is
    private static final java.io.PrintStream UTF8_OUT =
            new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8);

    private static Writer consoleAutoFlushWriter() {
        return new Writer() {
            @Override public void write(char[] cbuf, int off, int len) {
                UTF8_OUT.print(new String(cbuf, off, len));
                UTF8_OUT.flush();
            }
            @Override public void flush() { UTF8_OUT.flush(); }
            @Override public void close() {}
        };
    }

    // Feeds what the host terminal sends into the VM's keyboard device (a FIFO with an
    // interrupt, see UartController): bytes as they come, UTF-8 and escape
    // sequences untouched. The guest turns them into keys. Only meaningful against an
    // interactive stdin.
    private static void startKeyboardBridge(Bl0jv2_jVM vm) {
        // the terminal's size is the screen's size; raw mode makes every key reach the guest as
        // the terminal sent it (see HostTerminal) - the guest's line editor does the editing
        int[] size = HostTerminal.size();
        vm.set_console_size(size[0], size[1]);
        HostTerminal.enterRawMode();
        Thread bridge = new Thread(() -> {
            var in = System.in;
            byte[] buffer = new byte[256];
            try {
                int n;
                // the bytes go into the keyboard device's FIFO as they arrive and an interrupt tells
                // the guest; the FIFO is what makes pacing unnecessary. In the terminal's normal line
                // mode the Enter key arrives as a line feed, which the guest also takes as Enter.
                while ((n = in.read(buffer)) > 0) vm.uart_receive(java.util.Arrays.copyOf(buffer, n));
            } catch (IOException ignored) {
                // stdin closed or the JVM is shutting down - nothing left for this bridge to do
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

                var ast = parser.getAST(lexer.getTokens(line));
                // imports work at the prompt too: relative to the current directory, then -I
                if (ast instanceof PROGRAM_N program)
                    ast = Bl0jv2_Linker.resolveImports(program, Path.of("repl"), includeDirs);
                byte[] instructions = compiler.compile(ast);

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
                dest = source.resolveSibling(source.getFileName() + ".bl0c");

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
            var linked = Bl0jv2_Linker.resolveImports(program, source, includeDirs);

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
                vm.set_uart_baud(uartBaud);
                if (bridgeFsDir != null) {
                    try {
                        vm.attach_share(new DirShare(bridgeFsDir));
                    } catch (IOException e) {
                        System.err.println("--bridge-fs " + bridgeFsDir + ": " + e.getMessage());
                        return 1;
                    }
                }
                if (diskImage == null && !diskPuts.isEmpty()) {
                    System.err.println("--disk-put needs --disk <image>");
                    return 1;
                }
                if (diskImage != null) {
                    try {
                        var disk = new FileDisk(diskImage, diskSectors);
                        SharedLibs shared = sharedManifest == null ? null : SharedLibs.read(sharedManifest);
                        if (!diskPuts.isEmpty() || shared != null) DiskImport.put(disk, diskPuts, includeDirs, shared);
                        vm.attach_disk(disk);
                    } catch (IOException e) {
                        System.err.println("--disk " + diskImage + ": " + e.getMessage());
                        return 1;
                    }
                }
                // -k needs output to appear as the program prints it, not
                // buffered until run_instructions() returns (which, for an
                // interactive keyboard-driven program, might be "never
                // until the user types 'exit'") - a plain PrintWriter over
                // System.out has no such guarantee without an explicit
                // flush after every write
                Writer outWriter = (keyboard || bridgeUdpPort >= 0 || bridgeTcpPort >= 0 || bridgeOutbound)
                        ? consoleAutoFlushWriter() : writer;
                // with a display the screen is the console: whatever the program print()s would only
                // scribble over it, so that output is dropped
                vm.set_out_writer(display ? Writer.nullWriter() : outWriter);
                if (display) {
                    int[] size = HostTerminal.size();
                    vm.set_console_size(size[0], size[1]);
                    vm.attach_display();
                }
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
                    new IcmpOutboundBridge(vm).start();
                    new UdpOutboundBridge(vm, udpBridge == null ? f -> false : udpBridge::claims).start();
                }
                HostScreen screen = display ? new HostScreen(vm, UTF8_OUT) : null;
                if (!display) System.out.println();
                // flush in finally: a crash mid-program must not discard
                // whatever it already printed before the exception
                try {
                    vm.run_instructions();
                } finally {
                    if (screen != null) screen.close();
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