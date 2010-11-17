LOCAL_PATH := $(call my-dir)/..

include $(CLEAR_VARS)

LOCAL_MODULE := bluetooth
LOCAL_SRC_FILES := lib/bluetooth.c lib/hci.c lib/sdp.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/lib

include $(BUILD_STATIC_LIBRARY)
