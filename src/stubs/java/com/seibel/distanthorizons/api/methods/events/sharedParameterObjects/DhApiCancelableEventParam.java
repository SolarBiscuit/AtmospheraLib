package com.seibel.distanthorizons.api.methods.events.sharedParameterObjects;

/** Compile-only stand-in for Distant Horizons' API class (see DhApiBeforeRenderEvent). */
public class DhApiCancelableEventParam<T> extends DhApiEventParam<T> {
    public DhApiCancelableEventParam(T value) {
        super(value);
    }
}
