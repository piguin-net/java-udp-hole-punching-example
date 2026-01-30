package com.example;

public class Utils {
    public static int ushort2int(short value) {
        return (value < 0 ? 1 + Short.MAX_VALUE : 0) + (value & Short.MAX_VALUE);
    }

    public static void printBytes(byte[] data) {
        int i = 0;
        while (i < data.length) {
            int j = 0;
            while (i < data.length && j < 4) {
                System.out.print(String.format(" %02x", data[i]));
                i++;
                j++;
            }
            System.out.println();
        }
    }
}
