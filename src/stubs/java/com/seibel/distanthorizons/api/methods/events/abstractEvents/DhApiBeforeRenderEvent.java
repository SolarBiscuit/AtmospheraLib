package com.seibel.distanthorizons.api.methods.events.abstractEvents;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;

/**
 * Compile-only stand-in for Distant Horizons' API class of the same name (API 3/4, checked against DH 3.2.0): only the
 * members AtmospheraLib uses. Never shipped; at run time DH's own class is loaded.
 */
public abstract class DhApiBeforeRenderEvent {
    public abstract void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event);
}
