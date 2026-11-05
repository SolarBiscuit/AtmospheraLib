package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import com.solarbiscuit.atmosphera.lib.mesh.AtmosMaterial;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Material variants that carry Atmosphera bits in material bits 3-7. Sodium only uses a Material's
 * {@code pass} to pick buffers and its {@code bits()} when encoding vertices, so a variant behaves exactly like
 * its base apart from the extra bits. The 32 variants are built at once and published through a volatile field,
 * so chunk-builder threads never see a half-initialised one.
 */
@Mixin(value = Material.class, remap = false)
public abstract class MaterialMixin implements AtmosMaterial {
    @Shadow @Final public TerrainRenderPass pass;
    @Shadow @Final public AlphaCutoffParameter alphaCutoff;
    @Shadow @Final public boolean mipped;
    @Shadow @Final @Mutable public int packed;

    @Unique
    private volatile Material[] atmos$variants;

    @Override
    public Material atmos$withExtraBits(int extraBits) {
        Material[] variants = this.atmos$variants;
        if (variants == null) {
            variants = new Material[32];
            for (int i = 0; i < variants.length; i++) {
                Material variant = new Material(this.pass, this.alphaCutoff, this.mipped);
                ((AtmosMaterial) variant).atmos$setPacked(this.packed | (i << 3));
                variants[i] = variant;
            }
            this.atmos$variants = variants;
        }
        return variants[extraBits & 31];
    }

    @Override
    public void atmos$setPacked(int packed) {
        this.packed = packed;
    }
}
