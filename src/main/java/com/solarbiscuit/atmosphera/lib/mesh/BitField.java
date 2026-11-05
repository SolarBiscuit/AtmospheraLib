package com.solarbiscuit.atmosphera.lib.mesh;

import java.util.Locale;

/**
 * A named range of Atmosphera vertex bits, handed out by {@link VertexBits}.
 * Material fields live in the 5 spare material bits (values 0-31 overall); light fields live in the 16 spare
 * light-coordinate bits. Use {@link #set} on the CPU side and the {@link #glslMacro()} in shaders.
 */
public final class BitField {
    public enum Kind { MATERIAL, LIGHT }

    private final Kind kind;
    private final String owner;
    private final String name;
    private final int offset;
    private final int width;
    private final int mask;

    BitField(Kind kind, String owner, String name, int offset, int width) {
        this.kind = kind;
        this.owner = owner;
        this.name = name;
        this.offset = offset;
        this.width = width;
        this.mask = (1 << width) - 1;
    }

    public Kind kind() {
        return kind;
    }

    public String owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public int offset() {
        return offset;
    }

    public int width() {
        return width;
    }

    /** Writes {@code value} into this field of {@code bits}. */
    public int set(int bits, int value) {
        return (bits & ~(mask << offset)) | ((value & mask) << offset);
    }

    public int get(int bits) {
        return (bits >>> offset) & mask;
    }

    /**
     * GLSL macro reading this field, for example {@code ATMOS_MAT_GALE_WAVE(atmos_MaterialBits)}.
     * Material fields read {@code atmos_MaterialBits} / {@code v_AtmosMaterialBits}, light fields read
     * {@code atmos_LightBits} / {@code v_AtmosLightBits}.
     */
    public String glslMacro() {
        return (kind == Kind.MATERIAL ? "ATMOS_MAT_" : "ATMOS_LIGHT_")
                + (owner + "_" + name).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    String glslDefine() {
        return "#define " + glslMacro() + "(bits) (((bits) >> " + offset + "u) & " + mask + "u)\n";
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + " " + owner + ":" + name + " [" + offset + ".." + (offset + width - 1) + "]";
    }
}
