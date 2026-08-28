package com.andrerinas.openheadunit.decoder.video

import java.nio.ByteBuffer

interface SoftwareYuvFrameSink {
    fun renderYuv420Frame(
        width: Int,
        height: Int,
        yPlane: ByteBuffer,
        yStride: Int,
        uPlane: ByteBuffer,
        uStride: Int,
        vPlane: ByteBuffer,
        vStride: Int
    ): Boolean
}
