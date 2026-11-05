package com.solarbiscuit.atmosphera.lib.mesh;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;

/** Duck interface mixed into Sodium's {@code Material}: same pass and parameters, extra bits in the material byte. */
public interface AtmosMaterial {
    /** @param extraBits value for material bits 3-7 (0-31) */
    Material atmos$withExtraBits(int extraBits);

    /** Internal: sets a freshly built variant's packed bits before it is published. */
    void atmos$setPacked(int packed);
}
