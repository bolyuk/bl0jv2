package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// the 16550-style UART at 0x0F40 and the display's geometry ports
class Bl0jv2_ConsoleDeviceTest {

    private static final String REGS =
            "def rbr() { return in8(0x0F40); } def ier() { return in8(0x0F41); } def iir() { return in8(0x0F42); } " +
            "def lcr() { return in8(0x0F43); } def lsr() { return in8(0x0F45); } def msr() { return in8(0x0F46); } " +
            "def send(b) { out8(0x0F40, b); } ";

    @Test
    void bytesSentAreDecodedAsUtf8Text() {
        // 'я' is d1 8f, '€' is e2 82 ac: characters split over several bytes
        assertEquals("hi я €!", Bl0jv2_TestRunner.run(REGS +
                "send(104); send(105); send(32); send(0xD1); send(0x8F); send(32); send(0xE2); send(0x82); send(0xAC); send(33);"));
    }

    @Test
    void aMalformedByteBecomesAReplacementCharacter() {
        assertEquals("a�b", Bl0jv2_TestRunner.run(REGS + "send(97); send(0xFF); send(98);"));
    }

    @Test
    void theTransmitterIsIdleAfterSendingAtFullSpeed() {
        // LSR: 0x20 transmit FIFO empty + 0x40 transmitter idle
        assertEquals("96", Bl0jv2_TestRunner.run(REGS + "print lsr();"));
        assertEquals("a96", Bl0jv2_TestRunner.run(REGS + "send(97); print lsr();"));
    }

    @Test
    void withABaudRateTheTransmitterIsBusyAndDrainsOneCharacterAtATime() {
        // 300 baud: 33 ms a character. Right after sending, the FIFO is not empty (THRE clear).
        String out = Bl0jv2_TestRunner.run(REGS +
                "send(65); send(66); busy = lsr() & 0x20; wait(150); idle = lsr() & 0x60; print '|' + str(busy) + '|' + str(idle);",
                vm -> vm.set_uart_baud(300));
        assertEquals("AB|0|96", out);
    }

    @Test
    void aFullTransmitFifoDropsWhatTheDriverDidNotWaitFor() {
        // the FIFO takes 16 bytes; the 17th..20th are lost
        String out = Bl0jv2_TestRunner.run(REGS +
                "i = 0; while (i < 20) { send(97 + (i % 26)); i += 1; } wait(1200);",
                vm -> vm.set_uart_baud(300));
        assertEquals("abcdefghijklmnop", out);
    }

    @Test
    void receivedBytesComeOutOfTheFifoInOrderAndLsrSaysWhenThereIsData() {
        String out = Bl0jv2_TestRunner.run(REGS +
                "r = ''; while ((lsr() & 1) == 1) { r = r + str(rbr()) + ','; } print r + str(lsr() & 1) + ',' + str(rbr());",
                vm -> vm.uart_receive(new byte[]{104, (byte) 0xD1, (byte) 0x8F}));
        assertEquals("104,209,143,0,0", out);
    }

    @Test
    void theReceiveFifoIsOneByteUntilTheGuestEnablesIt() {
        // flow control (the default) holds the rest back outside the chip: nothing is lost
        String out = Bl0jv2_TestRunner.run(REGS +
                "r = ''; while ((lsr() & 1) == 1) { r = r + str(rbr()) + ','; } print r;",
                vm -> vm.uart_receive(new byte[]{1, 2, 3, 4, 5}));
        assertEquals("1,2,3,4,5,", out);
    }

    // the terminal sends after the program has had time to set the UART up (the chip starts with
    // its FIFOs off, so bytes that arrive earlier meet a one-byte receiver)
    private static java.util.function.Consumer<bl0.bl0jv2.runtime.Bl0jv2_jVM> sendLater(byte[] bytes) {
        return vm -> {
            Thread t = new Thread(() -> {
                try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                vm.uart_receive(bytes);
            });
            t.setDaemon(true);
            t.start();
        };
    }

    @Test
    void withoutFlowControlAnOverfullFifoLosesBytesAndSetsOverrun() {
        String out = Bl0jv2_TestRunner.run(REGS +
                "out8(0x0F42, 1); wait(400); " +               // FCR: enable the 16-byte FIFOs, then the bytes arrive
                "o = (lsr() >> 1) & 1; r = 0; while ((lsr() & 1) == 1) { rbr(); r += 1; } " +
                "print str(o) + '|' + str(r) + '|' + str((lsr() >> 1) & 1);",
                vm -> { vm.set_uart_flow_control(false); sendLater(new byte[40]).accept(vm); });
        // the first read of LSR says overrun and clears it; 16 bytes survived
        assertEquals("1|16|0", out);
    }

    @Test
    void theInterruptCauseIsReportedInIirAndTriggerLevelsApply() {
        String out = Bl0jv2_TestRunner.run(REGS +
                "out8(0x0F42, 0x41); out8(0x0F41, 1); wait(400); " +   // FIFO on, trigger 4 bytes; interrupt on received data
                "a = iir(); " +                                          // 4 waiting: 'received data' (0x04), FIFO bits 0xC0
                "i = 0; while (i < 3) { rbr(); i += 1; } " +             // one left: below the trigger, the burst has ended: character timeout (0x0C)
                "b = iir(); rbr(); c = iir(); " +                        // empty: nothing pending (bit 0 set)
                "print str(a) + '|' + str(b) + '|' + str(c);",
                sendLater(new byte[]{1, 2, 3, 4}));
        assertEquals("196|204|193", out);
    }

    @Test
    void theLineControlRegisterSwitchesTheDivisorLatchIn() {
        String out = Bl0jv2_TestRunner.run(REGS +
                "out8(0x0F43, 0x80); out8(0x0F40, 12); out8(0x0F41, 0); " +    // DLAB: divisor 12 (9600 baud)
                "print str(in8(0x0F40)) + '|' + str(in8(0x0F41)) + '|'; " +
                "out8(0x0F43, 0x03); out8(0x0F41, 5); " +                       // DLAB off: IER
                "print str(in8(0x0F41)) + '|' + str(lcr());");
        assertEquals("12|0|5|3", out);
    }

    @Test
    void theModemStatusSaysClearToSendAndTheScratchRegisterHoldsAByte() {
        assertEquals("48|165", Bl0jv2_TestRunner.run(REGS + "out8(0x0F47, 165); print str(msr()) + '|' + str(in8(0x0F47));"));
    }

    @Test
    void theScreenSizeIsReportedAndSettable() {
        String read = "print str(in16(0x0F50)) + 'x' + str(in16(0x0F52));";
        assertEquals("80x24", Bl0jv2_TestRunner.run(read));
        assertEquals("100x40", Bl0jv2_TestRunner.run(read, vm -> vm.set_console_size(100, 40)));
    }
}
