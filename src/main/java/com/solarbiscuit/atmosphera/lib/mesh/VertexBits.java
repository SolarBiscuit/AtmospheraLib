package com.solarbiscuit.atmosphera.lib.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Hands out the spare bits of Xenon's compact chunk vertex, so the Atmosphera mods never collide.
 * <ul>
 *   <li>{@link BitField.Kind#MATERIAL}: material byte bits 3-7 (Sodium uses 0-2). 5 bits, per quad.</li>
 *   <li>{@link BitField.Kind#LIGHT}: the high byte of the block-light and of the sky-light u16. 16 bits, per vertex.
 *       Logical bits 0-7 sit in the block-light high byte, 8-15 in the sky-light high byte.</li>
 * </ul>
 * Allocate during mod construction. Allocation fails loudly when the bits run out.
 */
public final class VertexBits {
    public static final int MATERIAL_BITS = 5;
    public static final int LIGHT_BITS = 16;
    /** Sodium's own material bits; Atmosphera's start above them. */
    public static final int MATERIAL_SHIFT = 3;

    private static final List<BitField> FIELDS = new ArrayList<>();
    private static int materialUsed;
    private static int lightUsed;
    private static boolean frozen;

    private VertexBits() {
    }

    public static synchronized BitField material(String owner, String name, int width) {
        return allocate(BitField.Kind.MATERIAL, owner, name, width);
    }

    public static synchronized BitField light(String owner, String name, int width) {
        return allocate(BitField.Kind.LIGHT, owner, name, width);
    }

    private static BitField allocate(BitField.Kind kind, String owner, String name, int width) {
        if (frozen) {
            throw new IllegalStateException("Atmosphera vertex bits must be allocated during mod construction");
        }
        int used = kind == BitField.Kind.MATERIAL ? materialUsed : lightUsed;
        int limit = kind == BitField.Kind.MATERIAL ? MATERIAL_BITS : LIGHT_BITS;
        if (width < 1 || used + width > limit) {
            throw new IllegalStateException("Out of Atmosphera " + kind + " vertex bits: " + owner + ":" + name
                    + " wants " + width + ", " + (limit - used) + " left. Allocated: " + FIELDS);
        }
        BitField field = new BitField(kind, owner, name, used, width);
        if (kind == BitField.Kind.MATERIAL) {
            materialUsed += width;
        } else {
            lightUsed += width;
        }
        FIELDS.add(field);
        return field;
    }

    public static synchronized void freeze() {
        frozen = true;
    }

    public static synchronized List<BitField> fields() {
        return List.copyOf(FIELDS);
    }

    /** {@code #define} lines for every field, for the generated shader header. */
    public static synchronized String glslDefines() {
        StringBuilder out = new StringBuilder();
        for (BitField field : FIELDS) {
            out.append(field.glslDefine());
        }
        return out.toString();
    }

    /** Packs 16 logical light bits into the spare bytes of a Sodium vertex light value. */
    public static int packLight(int light, int bits) {
        return (light & 0x00FF00FF) | ((bits & 0xFF) << 8) | (((bits >>> 8) & 0xFF) << 24);
    }
}
