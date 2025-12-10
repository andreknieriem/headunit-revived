package com.andrerinas.headunitrevived.extractor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import com.andrerinas.headunitrevived.utils.AppLog
import java.io.IOException
import java.nio.ByteBuffer
import java.security.InvalidParameterException


/**
 * @author algavris
 * *
 * @date 30/04/2016.
 */
class StreamVideoExtractor : MediaExtractorInterface {

    private var mFormat: MediaFormat? = null
    private var mContentData: ByteArray? = null
    private var mSampleOffset = -1

    override var sampleFlags: Int = 0
        private set

    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        if (mSampleOffset >= 0) {
            var nextSample = findNextNAL(mSampleOffset + 4)
            if (nextSample == -1) {
                nextSample = mContentData!!.size - 1
            }
            val size = nextSample - mSampleOffset
            buffer.clear()
            buffer.put(mContentData!!)
            AppLog.i("readSampleData (offset: %d,next: %d,size: %d,length: %d, flags: 0x%08x)", mSampleOffset, nextSample, size, mContentData!!.size, sampleFlags)
            return size
        }
        return 0
    }

    override fun getSampleCryptoInfo(cryptoInfo: MediaCodec.CryptoInfo) {

    }

    override fun release() {
        mContentData = null
    }

    override fun setDataSource(content: ByteArray, width: Int, height: Int) {
        mContentData = content
        sampleFlags = 0
        mFormat = MediaFormat.createVideoFormat("video/avc", width, height)

        mSampleOffset = findSPS()
        if (mSampleOffset == -1) {
            throw InvalidParameterException("Cannot find SPS in content")
        }
    }

    @Throws(IOException::class)
    override fun setDataSource(context: Context, uri: Uri, headers: Map<String, String>) {

    }

    override val trackCount: Int
        get() = 1

    override fun unselectTrack(index: Int) {

    }

    override fun getTrackFormat(index: Int): MediaFormat {
        return mFormat!!
    }

    override fun selectTrack(index: Int) {

    }

    override val sampleTime: Long
        get() = 0

    override fun advance() {
        sampleFlags = sampleFlags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG.inv()
        mSampleOffset = findNextNAL(mSampleOffset + 4)
        if (mSampleOffset == -1) {
            sampleFlags = sampleFlags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
        }
    }

    private fun findNextNAL(offset: Int): Int {
        var naloffset = offset
        while (naloffset < mContentData!!.size) {
            if (mContentData!![naloffset].toInt() == 0 && mContentData!![naloffset + 1].toInt() == 0 && mContentData!![naloffset + 2].toInt() == 0 && mContentData!![naloffset + 3].toInt() == 1) {
                AppLog.i("Found sequence at %d: 0x0 0x0 0x0 0x1 0x%01x (%d)", mSampleOffset, mContentData!![naloffset + 4], mContentData!![naloffset + 4])
                return naloffset
            }
            naloffset++
        }
        return -1
    }

    // SPS (Sequence Parameter Set) NAL Unit first
    private fun findSPS(): Int {
        var offset = 0
        while (offset >= 0) {
            offset = findNextNAL(offset)
            if (offset == -1) {
                return -1
            }

            if (mContentData!![offset + 4] == SPS_BIT) {
                sampleFlags = sampleFlags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                return offset
            }
        }
        return -1
    }

    companion object {
        private const val SPS_BIT: Byte = 0x67
    }
}
