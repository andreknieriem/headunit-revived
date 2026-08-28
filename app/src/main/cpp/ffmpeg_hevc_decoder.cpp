#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>

#define LOG_TAG "HurSoftHevc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if HUR_HAVE_FFMPEG
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
}
#endif

namespace {

#if HUR_HAVE_FFMPEG
class HevcDecoder {
public:
    HevcDecoder(JNIEnv* env, ANativeWindow* outputWindow, jobject yuvCallback, int fallbackWidth, int fallbackHeight, int threadCount)
        : window(outputWindow), requestedWidth(fallbackWidth), requestedHeight(fallbackHeight), threads(threadCount) {
        env->GetJavaVM(&javaVm);
        if (yuvCallback != nullptr) {
            callback = env->NewGlobalRef(yuvCallback);
            jclass callbackClass = env->GetObjectClass(yuvCallback);
            onYuvFrameMethod = env->GetMethodID(
                callbackClass,
                "onNativeYuv420Frame",
                "(IILjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;I)Z");
            env->DeleteLocalRef(callbackClass);
            if (env->ExceptionCheck() || onYuvFrameMethod == nullptr) {
                env->ExceptionClear();
                LOGE("YUV callback method onNativeYuv420Frame not found");
                env->DeleteGlobalRef(callback);
                callback = nullptr;
            }
        }
    }

    ~HevcDecoder() {
        release(nullptr);
    }

    bool start() {
        if (window == nullptr && callback == nullptr) {
            LOGE("No output window or YUV callback configured");
            return false;
        }

        const AVCodec* decoder = avcodec_find_decoder_by_name("hevc");
        if (decoder == nullptr) {
            decoder = avcodec_find_decoder(AV_CODEC_ID_HEVC);
        }
        if (decoder == nullptr) {
            LOGE("FFmpeg HEVC decoder not found");
            return false;
        }

        codecContext = avcodec_alloc_context3(decoder);
        if (codecContext == nullptr) {
            LOGE("Failed to allocate AVCodecContext");
            return false;
        }

        codecContext->thread_count = std::max(1, threads);
        codecContext->thread_type = FF_THREAD_SLICE;
        codecContext->flags2 |= AV_CODEC_FLAG2_FAST;
        codecContext->lowres = 0;

        if (avcodec_open2(codecContext, decoder, nullptr) < 0) {
            LOGE("Failed to open FFmpeg HEVC decoder");
            return false;
        }

        packet = av_packet_alloc();
        frame = av_frame_alloc();
        rgbaFrame = av_frame_alloc();
        if (packet == nullptr || frame == nullptr || rgbaFrame == nullptr) {
            LOGE("Failed to allocate FFmpeg packet/frame");
            return false;
        }

        if (window != nullptr && requestedWidth > 0 && requestedHeight > 0) {
            ANativeWindow_setBuffersGeometry(window, requestedWidth, requestedHeight, WINDOW_FORMAT_RGBA_8888);
        }
        return true;
    }

    int decode(JNIEnv* env, jbyteArray input, jint offset, jint size, jlong ptsUs) {
        if (codecContext == nullptr || packet == nullptr || frame == nullptr || size <= 0) {
            return -1;
        }

        if (av_new_packet(packet, size) < 0) {
            return -2;
        }

        env->GetByteArrayRegion(input, offset, size, reinterpret_cast<jbyte*>(packet->data));
        if (env->ExceptionCheck()) {
            av_packet_unref(packet);
            return -3;
        }
        packet->pts = ptsUs;
        packet->dts = ptsUs;

        int result = avcodec_send_packet(codecContext, packet);
        av_packet_unref(packet);
        if (result == AVERROR(EAGAIN)) {
            return receiveFrames(env);
        }
        if (result < 0) {
            LOGE("avcodec_send_packet failed: %d", result);
            return -4;
        }

        return receiveFrames(env);
    }

    void release(JNIEnv* env) {
        JNIEnv* effectiveEnv = env;
        if (effectiveEnv == nullptr && javaVm != nullptr) {
            javaVm->GetEnv(reinterpret_cast<void**>(&effectiveEnv), JNI_VERSION_1_6);
        }
        if (swsContext != nullptr) {
            sws_freeContext(swsContext);
            swsContext = nullptr;
        }
        if (yuvSwsContext != nullptr) {
            sws_freeContext(yuvSwsContext);
            yuvSwsContext = nullptr;
        }
        if (rgbaBuffer != nullptr) {
            av_freep(&rgbaBuffer);
        }
        if (yuvBuffer != nullptr) {
            av_freep(&yuvBuffer);
        }
        if (rgbaFrame != nullptr) {
            av_frame_free(&rgbaFrame);
        }
        if (yuvFrame != nullptr) {
            av_frame_free(&yuvFrame);
        }
        if (frame != nullptr) {
            av_frame_free(&frame);
        }
        if (packet != nullptr) {
            av_packet_free(&packet);
        }
        if (codecContext != nullptr) {
            avcodec_free_context(&codecContext);
        }
        if (window != nullptr) {
            ANativeWindow_release(window);
            window = nullptr;
        }
        if (callback != nullptr && effectiveEnv != nullptr) {
            effectiveEnv->DeleteGlobalRef(callback);
            callback = nullptr;
        }
    }

private:
    int receiveFrames(JNIEnv* env) {
        int rendered = 0;
        while (true) {
            const int result = avcodec_receive_frame(codecContext, frame);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
                break;
            }
            if (result < 0) {
                LOGE("avcodec_receive_frame failed: %d", result);
                return -5;
            }
            if (!renderFrame(env)) {
                av_frame_unref(frame);
                return -6;
            }
            rendered++;
            av_frame_unref(frame);
        }
        return rendered;
    }

    bool ensureRgbaBuffer(int width, int height) {
        if (width <= 0 || height <= 0) return false;
        if (rgbaBuffer != nullptr && rgbaWidth == width && rgbaHeight == height) return true;

        if (rgbaBuffer != nullptr) {
            av_freep(&rgbaBuffer);
        }

        const int bufferSize = av_image_get_buffer_size(AV_PIX_FMT_RGBA, width, height, 1);
        if (bufferSize <= 0) {
            return false;
        }
        rgbaBuffer = static_cast<uint8_t*>(av_malloc(bufferSize));
        if (rgbaBuffer == nullptr) {
            return false;
        }

        if (av_image_fill_arrays(
                rgbaFrame->data,
                rgbaFrame->linesize,
                rgbaBuffer,
                AV_PIX_FMT_RGBA,
                width,
                height,
                1) < 0) {
            return false;
        }

        rgbaWidth = width;
        rgbaHeight = height;
        return true;
    }

    bool renderFrame(JNIEnv* env) {
        const int width = frame->width > 0 ? frame->width : codecContext->width;
        const int height = frame->height > 0 ? frame->height : codecContext->height;

        if (callback != nullptr) {
            logYuvFrameFormat(width, height);
            return renderYuvFrame(env, width, height);
        }

        if (window == nullptr) {
            LOGE("No output window configured");
            return false;
        }

        if (!ensureRgbaBuffer(width, height)) {
            LOGE("Failed to prepare RGBA buffer for %dx%d", width, height);
            return false;
        }

        swsContext = sws_getCachedContext(
            swsContext,
            width,
            height,
            static_cast<AVPixelFormat>(frame->format),
            width,
            height,
            AV_PIX_FMT_RGBA,
            SWS_FAST_BILINEAR,
            nullptr,
            nullptr,
            nullptr);
        if (swsContext == nullptr) {
            LOGE("Failed to create swscale context");
            return false;
        }

        sws_scale(
            swsContext,
            frame->data,
            frame->linesize,
            0,
            height,
            rgbaFrame->data,
            rgbaFrame->linesize);

        ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888);

        ANativeWindow_Buffer windowBuffer;
        if (ANativeWindow_lock(window, &windowBuffer, nullptr) != 0) {
            LOGE("ANativeWindow_lock failed");
            return false;
        }

        const int copyWidth = std::min(width, windowBuffer.width);
        const int copyHeight = std::min(height, windowBuffer.height);
        auto* dst = static_cast<uint8_t*>(windowBuffer.bits);
        const auto* src = rgbaFrame->data[0];
        const int dstStride = windowBuffer.stride * 4;
        const int srcStride = rgbaFrame->linesize[0];
        const int bytesPerRow = copyWidth * 4;

        for (int y = 0; y < copyHeight; ++y) {
            std::memcpy(dst + y * dstStride, src + y * srcStride, bytesPerRow);
        }

        ANativeWindow_unlockAndPost(window);
        return true;
    }

    bool renderYuvFrame(JNIEnv* env, int width, int height) {
        if (env == nullptr || callback == nullptr || onYuvFrameMethod == nullptr) {
            return false;
        }

        AVFrame* outputFrame = frame;
        if (frame->format != AV_PIX_FMT_YUV420P && frame->format != AV_PIX_FMT_YUVJ420P) {
            if (!ensureYuv420Frame(width, height)) {
                LOGE("Failed to prepare YUV420 conversion frame for format %d", frame->format);
                return false;
            }
            yuvSwsContext = sws_getCachedContext(
                yuvSwsContext,
                width,
                height,
                static_cast<AVPixelFormat>(frame->format),
                width,
                height,
                AV_PIX_FMT_YUV420P,
                SWS_FAST_BILINEAR,
                nullptr,
                nullptr,
                nullptr);
            if (yuvSwsContext == nullptr) {
                LOGE("Failed to create YUV swscale context");
                return false;
            }
            sws_scale(
                yuvSwsContext,
                frame->data,
                frame->linesize,
                0,
                height,
                yuvFrame->data,
                yuvFrame->linesize);
            outputFrame = yuvFrame;
        }

        jobject yBuffer = env->NewDirectByteBuffer(outputFrame->data[0], outputFrame->linesize[0] * height);
        jobject uBuffer = env->NewDirectByteBuffer(outputFrame->data[1], outputFrame->linesize[1] * (height / 2));
        jobject vBuffer = env->NewDirectByteBuffer(outputFrame->data[2], outputFrame->linesize[2] * (height / 2));
        if (yBuffer == nullptr || uBuffer == nullptr || vBuffer == nullptr) {
            if (yBuffer != nullptr) env->DeleteLocalRef(yBuffer);
            if (uBuffer != nullptr) env->DeleteLocalRef(uBuffer);
            if (vBuffer != nullptr) env->DeleteLocalRef(vBuffer);
            return false;
        }

        const jboolean accepted = env->CallBooleanMethod(
            callback,
            onYuvFrameMethod,
            width,
            height,
            yBuffer,
            outputFrame->linesize[0],
            uBuffer,
            outputFrame->linesize[1],
            vBuffer,
            outputFrame->linesize[2]);
        env->DeleteLocalRef(yBuffer);
        env->DeleteLocalRef(uBuffer);
        env->DeleteLocalRef(vBuffer);
        return accepted == JNI_TRUE && !env->ExceptionCheck();
    }

    void logYuvFrameFormat(int width, int height) {
        if (loggedYuvFrameFormat) return;
        loggedYuvFrameFormat = true;
        const char* name = av_get_pix_fmt_name(static_cast<AVPixelFormat>(frame->format));
        LOGI("FFmpeg output frame format=%s(%d) size=%dx%d linesizes=%d/%d/%d color_range=%d colorspace=%d",
             name != nullptr ? name : "unknown",
             frame->format,
             width,
             height,
             frame->linesize[0],
             frame->linesize[1],
             frame->linesize[2],
             frame->color_range,
             frame->colorspace);
    }

    bool ensureYuv420Frame(int width, int height) {
        if (yuvFrame != nullptr && yuvBuffer != nullptr && yuvWidth == width && yuvHeight == height) {
            return true;
        }
        if (yuvBuffer != nullptr) {
            av_freep(&yuvBuffer);
        }
        if (yuvFrame == nullptr) {
            yuvFrame = av_frame_alloc();
            if (yuvFrame == nullptr) {
                return false;
            }
        }
        const int bufferSize = av_image_get_buffer_size(AV_PIX_FMT_YUV420P, width, height, 1);
        if (bufferSize <= 0) {
            return false;
        }
        yuvBuffer = static_cast<uint8_t*>(av_malloc(bufferSize));
        if (yuvBuffer == nullptr) {
            return false;
        }
        if (av_image_fill_arrays(
                yuvFrame->data,
                yuvFrame->linesize,
                yuvBuffer,
                AV_PIX_FMT_YUV420P,
                width,
                height,
                1) < 0) {
            return false;
        }
        yuvWidth = width;
        yuvHeight = height;
        return true;
    }

    ANativeWindow* window = nullptr;
    JavaVM* javaVm = nullptr;
    jobject callback = nullptr;
    jmethodID onYuvFrameMethod = nullptr;
    AVCodecContext* codecContext = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    AVFrame* rgbaFrame = nullptr;
    AVFrame* yuvFrame = nullptr;
    SwsContext* swsContext = nullptr;
    SwsContext* yuvSwsContext = nullptr;
    uint8_t* rgbaBuffer = nullptr;
    uint8_t* yuvBuffer = nullptr;
    int rgbaWidth = 0;
    int rgbaHeight = 0;
    int yuvWidth = 0;
    int yuvHeight = 0;
    int requestedWidth = 0;
    int requestedHeight = 0;
    int threads = 2;
    bool loggedYuvFrameFormat = false;
};
#endif

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_andrerinas_openheadunit_decoder_video_FfmpegHevcDecoder_00024Companion_nativeIsAvailable(
    JNIEnv*, jobject) {
#if HUR_HAVE_FFMPEG
    return avcodec_find_decoder_by_name("hevc") != nullptr ||
           avcodec_find_decoder(AV_CODEC_ID_HEVC) != nullptr;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_andrerinas_openheadunit_decoder_video_FfmpegHevcDecoder_00024Companion_nativeCreate(
    JNIEnv* env, jobject, jobject surface, jobject callback, jboolean useYuvCallback, jint width, jint height, jint threadCount) {
#if HUR_HAVE_FFMPEG
    ANativeWindow* window = nullptr;
    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
    }
    if (window == nullptr && useYuvCallback != JNI_TRUE) {
        LOGE("ANativeWindow_fromSurface failed");
        return 0;
    }

    auto decoder = std::make_unique<HevcDecoder>(
        env,
        window,
        useYuvCallback == JNI_TRUE ? callback : nullptr,
        width,
        height,
        threadCount);
    if (!decoder->start()) {
        return 0;
    }
    return reinterpret_cast<jlong>(decoder.release());
#else
    (void)env;
    (void)surface;
    (void)callback;
    (void)useYuvCallback;
    (void)width;
    (void)height;
    (void)threadCount;
    return 0;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_video_FfmpegHevcDecoder_00024Companion_nativeDecode(
    JNIEnv* env, jobject, jlong handle, jbyteArray buffer, jint offset, jint size, jlong presentationTimeUs) {
#if HUR_HAVE_FFMPEG
    auto* decoder = reinterpret_cast<HevcDecoder*>(handle);
    if (decoder == nullptr) return -1;
    return decoder->decode(env, buffer, offset, size, presentationTimeUs);
#else
    (void)env;
    (void)handle;
    (void)buffer;
    (void)offset;
    (void)size;
    (void)presentationTimeUs;
    return -1;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_decoder_video_FfmpegHevcDecoder_00024Companion_nativeRelease(
    JNIEnv* env, jobject, jlong handle) {
#if HUR_HAVE_FFMPEG
    auto* decoder = reinterpret_cast<HevcDecoder*>(handle);
    if (decoder != nullptr) {
        decoder->release(env);
    }
    delete decoder;
#else
    (void)env;
    (void)handle;
#endif
}
