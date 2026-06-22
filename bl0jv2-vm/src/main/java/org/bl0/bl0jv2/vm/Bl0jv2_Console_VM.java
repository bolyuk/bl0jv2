package org.bl0.bl0jv2.vm;

import java.nio.ByteBuffer;
import java.util.Scanner;

import static org.bl0.bl0jv2.vm.Bl0jv2_jVM.mock_header;

public class Bl0jv2_Console_VM {

    private static final Bl0jv2_jVM vm = new Bl0jv2_jVM();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("> ");

        vm.feed_compiled_file(mock_header());

        while (true) {
            String line = scanner.nextLine().trim();
            vm.set_instructions(parse_instruction(line));
            vm.run_instructions();

            System.out.print("> ");
        }
    }



    private static byte[] parse_instruction(String line) {
        if (line.startsWith("reg ")) {
            String[] parts = line.split("\\s+");
            int index = Integer.parseInt(parts[1], 16);
            Object value = Integer.parseInt(parts[2], 16);
            vm.set_register(index, value);
            System.out.println("R" + index + " = " + value);
            return new byte[0];
        } else {
        String[] parts = line.trim().split("\\s+");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
        }
    }
}
