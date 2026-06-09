package cloud.openlog.replay.graph

import android.view.View
import cloud.openlog.replay.mask.MaskPolicy
import cloud.openlog.replay.wire.Wireframe

/** Produces a wireframe tree for a window's decor view. */
interface ScreenGraphProvider {
    /**
     * @param root the decor/root view to walk.
     * @param density the display density used to normalize all geometry.
     * @param policy the masking policy applied at the source.
     * @return the root [Wireframe], or null if nothing capturable.
     */
    fun snapshot(root: View, density: Float, policy: MaskPolicy): Wireframe?
}
