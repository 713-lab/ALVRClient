#ifndef REMOTEGLASS_NAL_H
#define REMOTEGLASS_NAL_H
#include <jni.h>

void initNAL();
void destroyNAL(JNIEnv *env);

bool processPacket(JNIEnv *env, char *buf, int len);
jobject waitNal(JNIEnv *env);
jobject getNal(JNIEnv *env);
jobject peekNal(JNIEnv *env);
int getNalListSize();
void flushNalList(JNIEnv *env);
void notifyNALWaitingThread(JNIEnv *env);

#endif //REMOTEGLASS_NAL_H
