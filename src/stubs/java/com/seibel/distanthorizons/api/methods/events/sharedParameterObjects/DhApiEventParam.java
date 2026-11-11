package com.seibel.distanthorizons.api.methods.events.sharedParameterObjects;

/** Compile-only stand-in for Distant Horizons' API class (see DhApiBeforeRenderEvent). */
public class DhApiEventParam<T> {
    public final T value;

    public DhApiEventParam(T value) {
        this.value = value;
    }
}
