package bl0.bl0jv2;

import bl0.bl0jv2.generation.Bl0jv2_Compiler;
import bl0.bl0jv2.generation.Bl0jv2_Lexer;
import bl0.bl0jv2.generation.Bl0jv2_Linker;
import bl0.bl0jv2.generation.Bl0jv2_Parser;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.runtime.device.MemoryDisk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// the disk controller ports, and the CLI's -I import search path
class Bl0jv2_DiskTest {

    private static final String DRIVER =
            "reserve(4096, 512); " +
            "def diskCmd(cmd, sector) { out32(0x0F04, sector); out32(0x0F08, 4096); out8(0x0F0C, cmd); return in8(0x0F0D); } ";

    @Test
    void aSectorWrittenIsReadBackThroughDma() {
        var disk = new MemoryDisk(8);
        String out = Bl0jv2_TestRunner.run(DRIVER +
                "poke32(4096, 0xCAFEBABE); poke8(4607, 77); " +
                "print str(in32(0x0F00)) + '|' + str(diskCmd(2, 5)) + '|'; " +
                "poke32(4096, 0); poke8(4607, 0); " +
                "print str(diskCmd(1, 5)) + '|' + str(peek32(4096)) + '|' + str(peek8(4607));",
                vm -> vm.attach_disk(disk));
        assertEquals("8|0|0|-889275714|77", out);
    }

    @Test
    void aSectorPastTheEndAndAnUnknownCommandFail() {
        String out = Bl0jv2_TestRunner.run(DRIVER + "print str(diskCmd(1, 8)) + '|' + str(diskCmd(9, 0)) + '|' + str(diskCmd(1, 7));",
                vm -> vm.attach_disk(new MemoryDisk(8)));
        assertEquals("1|1|0", out);
    }

    @Test
    void withNoDiskTheCountIsZeroAndCommandsFail() {
        assertEquals("0|1", Bl0jv2_TestRunner.run(DRIVER + "print str(in32(0x0F00)) + '|' + str(diskCmd(1, 0));"));
    }

    @Test
    void aDmaOutsideRawMemoryFailsInsteadOfCrashing() {
        String out = Bl0jv2_TestRunner.run(
                "out32(0x0F04, 0); out32(0x0F08, 0x7FFFFFF0); out8(0x0F0C, 1); print in8(0x0F0D);",
                vm -> vm.attach_disk(new MemoryDisk(8)));
        assertEquals("1", out);
    }

    @Test
    void anImportMissingNextToTheFileIsFoundInAnIncludeDirectory(@TempDir Path dir) throws IOException {
        Path lib = Files.createDirectories(dir.resolve("libs"));
        Files.writeString(lib.resolve("greet.bl0"), "def greet() { return 'hi from libs'; }");
        Path entry = Files.createDirectories(dir.resolve("app")).resolve("main.bl0");
        String source = "import 'greet.bl0'; print greet();";
        Files.writeString(entry, source);

        var parser = new Bl0jv2_Parser();
        parser.setSourceCode(source);
        var ast = (PROGRAM_N) parser.getAST(new Bl0jv2_Lexer().getTokens(source));
        var linked = Bl0jv2_Linker.resolveImports(ast, entry, List.of(lib));
        byte[] bytes = new Bl0jv2_Compiler().compile(linked);
        assertEquals("hi from libs", Bl0jv2_TestRunner.runInstructions(bytes, null));
    }

    @Test
    void execMemRunsAProgramFromRawMemoryWithoutPrivilege() throws Exception {
        byte[] child = Bl0jv2_TestRunner.compile("println 'child, privileged: ' + str(isPrivileged());");
        var disk = new MemoryDisk(8);
        StringBuilder poke = new StringBuilder();
        for (int i = 0; i < child.length; i++) poke.append("poke8(").append(20000 + i).append(", ").append(child[i] & 0xFF).append("); ");
        String out = Bl0jv2_TestRunner.run(poke + "execMem(20000, " + child.length + "); print 'back, privileged: ' + str(isPrivileged());",
                vm -> vm.attach_disk(disk));
        assertEquals("child, privileged: falseback, privileged: true", out.replace("\r", "").replace("\n", ""));
    }

    @Test
    void execMemRejectsGarbageWithACatchableError() {
        assertEquals("exec: cannot load 'memory at 20000': Wrong magic number", Bl0jv2_TestRunner.run(
                "poke8(20000, 1); try { execMem(20000, 16); } catch (e) { print e; }"));
    }
}
