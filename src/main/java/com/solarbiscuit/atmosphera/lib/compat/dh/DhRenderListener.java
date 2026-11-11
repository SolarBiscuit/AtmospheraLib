package com.solarbiscuit.atmosphera.lib.compat.dh;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.solarbiscuit.atmosphera.lib.client.AtmosClient;

/**
 * Records DH's clip planes each frame DH renders (0.7.0). Loaded only by {@link DhBridge#init()} when Distant Horizons
 * is installed: it extends a DH API class. No allocation.
 */
final class DhRenderListener extends DhApiBeforeRenderEvent {
    @Override
    public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
        DhApiRenderParam param = event.value;
        if (param == null) {
            return;
        }
        DhBridge.near = param.nearClipPlane;
        DhBridge.far = param.farClipPlane;
        DhBridge.lastRenderFrame = AtmosClient.frame();
    }
}
