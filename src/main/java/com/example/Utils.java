package com.example;

public class Utils {
    public static int ushort2int(short value) {
        return (value < 0 ? 1 + Short.MAX_VALUE : 0) + (value & Short.MAX_VALUE);
    }
}
