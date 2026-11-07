# Android.mk for the userspace USB Audio Class isochronous engine
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := artemis-usbaudio
LOCAL_SRC_FILES := usb_audio.c
LOCAL_CFLAGS    := -Wall -Werror
LOCAL_LDLIBS    := -llog

LOCAL_BRANCH_PROTECTION := standard

include $(BUILD_SHARED_LIBRARY)
