/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import java.util.concurrent.Executor;

/** Internal ChunkMap bridge; queries require its owning server thread. */
public interface GenerationBridge {
    boolean vre$generationBusy(long position);
    Executor vre$generationExecutor();
}
