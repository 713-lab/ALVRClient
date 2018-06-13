#ifndef ALVRCLIENT_NAL_H
#define ALVRCLIENT_NAL_H
#include <jni.h>

void initNAL(JNIEnv *env);
void destroyNAL(JNIEnv *env);

bool processPacket(JNIEnv *env, char *buf, int len);
jobject waitNal(JNIEnv *env);
jobject getNal(JNIEnv *env);
void recycleNal(JNIEnv *env, jobject nal);
int getNalListSize(JNIEnv *env);
void flushNalList(JNIEnv *env);
void notifyNALWaitingThread(JNIEnv *env);

#endif //ALVRCLIENT_NAL_H
