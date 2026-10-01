package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// the terminal as two devices: bytes out on one port (UTF-8 decoded on the host),
// a FIFO in with a status port, and the screen size
class Bl0jv2_ConsoleDeviceTest {

    @Test
    void bytesWrittenToTheConsolePortComeOutAsUtf8Text() {
        // 'я' is d1 8f, '€' is e2 82 ac: characters split over several bytes
        assertEquals("hi я €!", Bl0jv2_TestRunner.run(
                "def put(b) { out8(0x0F40, b); } put(104); put(105); put(32); put(0xD1); put(0x8F); put(32); " +
                "put(0xE2); put(0x82); put(0xAC); put(33);"));
    }

    @Test
    void aMalformedByteBecomesAReplacementCharacterNotAnError() {
        assertEquals("a�b", Bl0jv2_TestRunner.run("out8(0x0F40, 97); out8(0x0F40, 0xFF); out8(0x0F40, 98);"));
    }

    @Test
    void theKeyboardFifoHoldsBytesUntilTheGuestReadsThem() {
        // three bytes arrive before the guest looks; it drains them in order, then sees empty
        String out = Bl0jv2_TestRunner.run(
                "r = ''; while (in8(0x0F49) == 1) { r = r + str(in8(0x0F48)) + ','; } print r + str(in8(0x0F49)) + ',' + str(in8(0x0F48));",
                vm -> vm.key_input(new byte[]{104, (byte) 0xD1, (byte) 0x8F}));
        assertEquals("104,209,143,0,0", out);
    }

    @Test
    void theScreenSizeIsReportedAndSettable() {
        String read = "print str(in16(0x0F44)) + 'x' + str(in16(0x0F46));";
        assertEquals("80x24", Bl0jv2_TestRunner.run(read));
        assertEquals("100x40", Bl0jv2_TestRunner.run(read, vm -> vm.set_console_size(100, 40)));
    }
}
