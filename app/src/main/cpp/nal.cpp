/// H.264 NAL Parser functions
// Extract NAL Units from packet by UDP/SRT socket.
////////////////////////////////////////////////////////////////////

#include <jni.h>
#include <string>
#include <stdlib.h>
#include <list>
#include <android/log.h>
#include <pthread.h>
#include "nal.h"
#include "utils.h"
#include "packet_types.h"

static const int MAXIMUM_NAL_BUFFER = 10;

static bool initialized = false;
static bool stopped = false;

// NAL buffer
jbyteArray currentBuf = NULL;
char *cbuf = NULL;
int bufferLength = 0;
int bufferPos = 0;

// Current NAL meta data from packet
uint64_t prevSequence;
uint64_t presentationTime;
uint64_t frameIndex;
uint32_t frameByteSize;

static JNIEnv *env_;

// Parsed NAL queue
struct NALBuffer {
    jbyteArray array;
    int len;
    uint64_t presentationTime;
    uint64_t frameIndex;
};
std::list<NALBuffer> nalList;
Mutex nalMutex;

static pthread_cond_t cond_nonzero = PTHREAD_COND_INITIALIZER;

static void clearNalList(JNIEnv *env) {
    for (auto it = nalList.begin();
         it != nalList.end(); ++it) {
        env->DeleteGlobalRef(it->array);
    }
    nalList.clear();
}

static void initializeBuffer(){
    currentBuf = NULL;
    cbuf = NULL;
    bufferLength = 0;
    bufferPos = 0;
}

static void allocateBuffer(int length) {
    if (currentBuf != NULL) {
        env_->ReleaseByteArrayElements(currentBuf, (jbyte *) cbuf, 0);
        env_->DeleteGlobalRef(currentBuf);
    }
    currentBuf = env_->NewByteArray(length);
    if(currentBuf == NULL){
        LOG("Error: NewByteArray return NULL. Memory is full?");
        return;
    }
    jobject tmp = currentBuf;
    currentBuf = (jbyteArray) env_->NewGlobalRef(currentBuf);
    env_->DeleteLocalRef(tmp);
    bufferLength = length;
    bufferPos = 0;

    cbuf = (char *) env_->GetByteArrayElements(currentBuf, NULL);
}

static bool push(char *buf, int len) {
    if (bufferPos + len > bufferLength) {
        // Full!
        return false;
    }
    memcpy(cbuf + bufferPos, buf, len);
    bufferPos += len;
    return true;
}

// Only release buffer. Caller can use currentBuf after release.
static void releaseBuffer(){
    if (currentBuf != NULL) {
        env_->ReleaseByteArrayElements(currentBuf, (jbyte *) cbuf, 0);
        currentBuf = NULL;
    }
}

// Release buffer and destroy object.
static void destroyBuffer(){
    if (currentBuf != NULL) {
        env_->ReleaseByteArrayElements(currentBuf, (jbyte *) cbuf, 0);
        env_->DeleteGlobalRef(currentBuf);
        currentBuf = NULL;
    }
}

void initNAL() {
    pthread_cond_init(&cond_nonzero, NULL);

    initializeBuffer();
    initialized = true;
    prevSequence = 0;
    stopped = false;
}

void destroyNAL(JNIEnv *env){
    clearNalList(env);
    destroyBuffer();
    stopped = true;
    pthread_cond_destroy(&cond_nonzero);
    initialized = false;
}

static void putNAL(int len) {
    NALBuffer buf;
    buf.len = len;
    buf.array = currentBuf;
    buf.presentationTime = presentationTime;
    buf.frameIndex = frameIndex;

    releaseBuffer();

    {
        MutexLock lock(nalMutex);

        if(nalList.size() < MAXIMUM_NAL_BUFFER) {
            nalList.push_back(buf);

            pthread_cond_broadcast(&cond_nonzero);
        }else {
            // Discard buffer
            LOG("NAL Queue is too large. Discard. Size=%lu Limit=%d", nalList.size(), MAXIMUM_NAL_BUFFER);
            env_->DeleteGlobalRef(buf.array);
        }
    }
}

bool processPacket(JNIEnv *env, char *buf, int len) {
    if (!initialized) {
        initNAL();
    }

    int pos = 0;

    env_ = env;

    uint32_t type = *(uint32_t *) buf;

    if (type == ALVR_PACKET_TYPE_VIDEO_FRAME_START) {
        VideoFrameStart *header = (VideoFrameStart *)buf;
        presentationTime = header->presentationTime;
        frameIndex = header->frameIndex;
        prevSequence = header->packetCounter;

        pos = sizeof(VideoFrameStart);

        uint8_t NALType = buf[pos + 4] & 0x1F;
        if(NALType == 7) {
            // SPS NAL

            // This frame contains SPS + PPS + IDR on NVENC H.264 stream.
            // SPS + PPS has short size (8bytes + 28bytes in some environment), so we can assume SPS + PPS is contained in first fragment.

            int zeroes = 0;
            bool parsingSPS = true;
            int SPSEnd = -1;
            int PPSEnd = -1;
            for(int i = pos + 4; i < len; i++) {
                if(buf[i] == 0) {
                    zeroes++;
                }else if(buf[i] == 1) {
                    if (zeroes == 3) {
                        if (parsingSPS) {
                            parsingSPS = false;
                            SPSEnd = i - 3;
                        }else{
                            PPSEnd = i - 3;
                            break;
                        }
                    }
                    zeroes = 0;
                }else {
                    zeroes = 0;
                }
            }
            if(SPSEnd == -1 || PPSEnd == -1) {
                // Invalid frame.
                LOG("Got invalid frame. Too large SPS or PPS?");
                return false;
            }
            allocateBuffer(SPSEnd - pos);
            push(buf + pos, SPSEnd - pos);
            putNAL(SPSEnd - pos);

            pos = SPSEnd;

            allocateBuffer(PPSEnd - pos);
            push(buf + pos, PPSEnd - pos);

            putNAL(PPSEnd - pos);

            pos = PPSEnd;

            // Allocate IDR frame buffer
            allocateBuffer(header->frameByteSize - (PPSEnd - sizeof(VideoFrameStart)));
            frameByteSize = header->frameByteSize - (PPSEnd - sizeof(VideoFrameStart));
            // Note that if previous frame packet has lost, we dispose that incomplete buffer implicitly here.
        }else {
            // Allocate P-frame buffer
            allocateBuffer(header->frameByteSize);
            frameByteSize = header->frameByteSize;
            // Note that if previous frame packet has lost, we dispose that incomplete buffer implicitly here.
        }
    }else {
        VideoFrame *header = (VideoFrame *)buf;
        pos = sizeof(VideoFrame);

        if(header->packetCounter != 1 && prevSequence + 1 != header->packetCounter) {
            // Packet loss
            LOGE("Ignore this frame because of packet loss. prevSequence=%lu currentSequence=%d"
            , prevSequence, header->packetCounter);

            // We don't update prevSequence here. This lead to ignore all packet until next VideoFrameStart packet arrives.
            return false;
        }
        prevSequence = header->packetCounter;
    }

    if(!push(buf + pos, len - pos)) {
        // Too large frame. It may be caused by packet loss.
        // We ignore this frame.
        LOGE("Error: Too large frame. Current buffer pos=%d buffer length=%d added size=%d"
        , bufferPos, bufferLength, len - pos);
        destroyBuffer();
        return false;
    }
    if(bufferPos >= frameByteSize) {
        // End of frame.
        putNAL(bufferPos);
        return true;
    }

    return false;
}


jobject waitNal(JNIEnv *env) {
    if (!initialized) {
        initNAL();
    }

    NALBuffer buf;

    while (true) {
        MutexLock lock(nalMutex);
        if (stopped) {
            return NULL;
        }
        if (nalList.size() != 0) {
            buf = nalList.front();
            nalList.pop_front();
            break;
        }
        nalMutex.CondWait(&cond_nonzero);
    }
    jclass clazz = env->FindClass("com/polygraphene/alvr/NAL");
    jmethodID ctor = env->GetMethodID(clazz, "<init>", "()V");

    jobject nal = env->NewObject(clazz, ctor);
    jfieldID len_ = env->GetFieldID(clazz, "len", "I");
    jfieldID presentationTime_ = env->GetFieldID(clazz, "presentationTime", "J");
    jfieldID frameIndex_ = env->GetFieldID(clazz, "frameIndex", "J");
    jfieldID buf_ = env->GetFieldID(clazz, "buf", "[B");

    env->SetIntField(nal, len_, buf.len);
    env->SetLongField(nal, presentationTime_, buf.presentationTime);
    env->SetLongField(nal, frameIndex_, buf.frameIndex);
    env->SetObjectField(nal, buf_, env->NewLocalRef(buf.array));
    env->DeleteGlobalRef(buf.array);

    return nal;
}

jobject getNal(JNIEnv *env) {
    if (!initialized) {
        initNAL();
    }

    NALBuffer buf;
    {
        MutexLock lock(nalMutex);
        if (nalList.size() == 0) {
            return NULL;
        }
        buf = nalList.front();
        nalList.pop_front();
    }
    jclass clazz = env->FindClass("com/polygraphene/alvr/NAL");
    jmethodID ctor = env->GetMethodID(clazz, "<init>", "()V");

    jobject nal = env->NewObject(clazz, ctor);
    jfieldID len_ = env->GetFieldID(clazz, "len", "I");
    jfieldID presentationTime_ = env->GetFieldID(clazz, "presentationTime", "J");
    jfieldID frameIndex_ = env->GetFieldID(clazz, "frameIndex", "J");
    jfieldID buf_ = env->GetFieldID(clazz, "buf", "[B");

    env->SetIntField(nal, len_, buf.len);
    env->SetLongField(nal, presentationTime_, buf.presentationTime);
    env->SetLongField(nal, frameIndex_, buf.frameIndex);
    env->SetObjectField(nal, buf_, env->NewLocalRef(buf.array));
    env->DeleteGlobalRef(buf.array);

    return nal;
}

jobject peekNal(JNIEnv *env) {
    if (!initialized) {
        initNAL();
    }

    NALBuffer buf;
    {
        MutexLock lock(nalMutex);
        if (nalList.size() == 0) {
            return NULL;
        }
        buf = nalList.front();
    }
    jclass clazz = env->FindClass("com/polygraphene/alvr/NAL");
    jmethodID ctor = env->GetMethodID(clazz, "<init>", "()V");

    jobject nal = env->NewObject(clazz, ctor);
    jfieldID len_ = env->GetFieldID(clazz, "len", "I");
    jfieldID presentationTime_ = env->GetFieldID(clazz, "presentationTime", "J");
    jfieldID frameIndex_ = env->GetFieldID(clazz, "frameIndex", "J");
    jfieldID buf_ = env->GetFieldID(clazz, "buf", "[B");

    env->SetIntField(nal, len_, buf.len);
    env->SetLongField(nal, presentationTime_, buf.presentationTime);
    env->SetLongField(nal, frameIndex_, buf.frameIndex);
    env->SetObjectField(nal, buf_, env->NewLocalRef(buf.array));

    return nal;
}


int getNalListSize() {
    if (!initialized) {
        initNAL();
    }
    MutexLock lock(nalMutex);
    return nalList.size();
}


void flushNalList(JNIEnv *env) {
    if (!initialized) {
        initNAL();
    }
    MutexLock lock(nalMutex);
    clearNalList(env);
}

void notifyNALWaitingThread(JNIEnv *env) {
    MutexLock lock(nalMutex);
    clearNalList(env);

    stopped = true;

    pthread_cond_broadcast(&cond_nonzero);
}
