package com.solarbiscuit.atmosphera.lib.util;

/** Colour text and packed-colour helpers the Atmosphera mods share (0.11.0 "Shared"). Any thread. */
public final class Colors {
    private Colors() {
    }

    /**
     * {@code "#RRGGBB"}, {@code "RRGGBB"}, {@code "0xRRGGBB"} or the short {@code "#RGB"} as {@code 0xRRGGBB}, or -1 when
     * the text is none of these (or null).
     */
    public static int parseRgb(String text) {
        if (text == null) {
            return -1;
        }
        String t = text.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        } else if (t.startsWith("0x") || t.startsWith("0X")) {
            t = t.substring(2);
        }
        if (t.length() == 3) {
            t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        }
        if (t.length() != 6) {
            return -1;
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(t.charAt(i), 16) < 0) {
                return -1; // 0.11.1: parseInt accepted "+12345"
            }
        }
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The red channel of {@code 0xRRGGBB} as 0..1. */
    public static float red(int rgb) {
        return ((rgb >> 16) & 255) / 255f;
    }

    /** The green channel of {@code 0xRRGGBB} as 0..1. */
    public static float green(int rgb) {
        return ((rgb >> 8) & 255) / 255f;
    }

    /** The blue channel of {@code 0xRRGGBB} as 0..1. */
    public static float blue(int rgb) {
        return (rgb & 255) / 255f;
    }

    /** {@code 0xRRGGBB} as a new {r, g, b} array of 0..1. */
    public static float[] toFloats(int rgb) {
        return new float[]{red(rgb), green(rgb), blue(rgb)};
    }
}
