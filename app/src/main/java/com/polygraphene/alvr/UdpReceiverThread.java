package com.polygraphene.alvr;

import android.app.Activity;
import android.opengl.EGLContext;
import android.util.Log;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

class UdpReceiverThread extends ThreadBase implements NALParser, TrackingThread.TrackingCallback {
    private static final String TAG = "UdpReceiverThread";

    static {
        System.loadLibrary("native-lib");
    }

    private static final String BROADCAST_ADDRESS = "255.255.255.255";
    private static final int PORT = 9944;

    private TrackingThread mTrackingThread;

    private DeviceDescriptor mDeviceDescriptor;

    private boolean mInitialized = false;
    private boolean mInitializeFailed = false;

    private String mPreviousServerAddress;
    private int mPreviousServerPort;

    interface Callback {
        void onConnected(int width, int height, int codec, int frameQueueSize, int refreshRate);

        void onChangeSettings(int enableTestMode, int suspend, int frameQueueSize);

        void onShutdown(String serverAddr, int serverPort);

        void onDisconnect();

        void onTracking(float[] position, float[] orientation);
    }

    private Callback mCallback;

    private long mNativeHandle = 0;
    private final Object mWaiter = new Object();

    UdpReceiverThread(Callback callback) {
        mCallback = callback;
    }

    private String getDeviceName() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return model;
        } else {
            return manufacturer + " " + model;
        }
    }

    public void recoverConnectionState(String serverAddress, int serverPort) {
        mPreviousServerAddress = serverAddress;
        mPreviousServerPort = serverPort;
    }

    public boolean start(EGLContext mEGLContext, Activity activity, DeviceDescriptor deviceDescriptor, int cameraTexture) {
        mTrackingThread = new TrackingThread();
        mTrackingThread.setCallback(this);

        mDeviceDescriptor = deviceDescriptor;

        super.startBase();

        synchronized (this) {
            while (!mInitialized && !mInitializeFailed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        if(!mInitializeFailed) {
            mTrackingThread.start(mEGLContext, activity, cameraTexture);
        }
        return !mInitializeFailed;
    }

    @Override
    public void stopAndWait() {
        synchronized (mWaiter) {
            mTrackingThread.stopAndWait();
            interruptNative(mNativeHandle);
            super.stopAndWait();
        }
    }

    @Override
    public void run() {
        try {
            String[] broadcastList = getBroadcastAddressList();

            mNativeHandle = initializeSocket(PORT, getDeviceName(), broadcastList,
                    mDeviceDescriptor.mRefreshRates, mDeviceDescriptor.mRenderWidth, mDeviceDescriptor.mRenderHeight, mDeviceDescriptor.mFov,
                    mDeviceDescriptor.mDeviceType, mDeviceDescriptor.mDeviceSubType, mDeviceDescriptor.mDeviceCapabilityFlags,
                    mDeviceDescriptor.mControllerCapabilityFlags
            );
            if (mNativeHandle == 0) {
                Log.e(TAG, "Error on initializing socket.");
                synchronized (this) {
                    mInitializeFailed = true;
                    notifyAll();
                }
                return;
            }
            synchronized (this) {
                mInitialized = true;
                notifyAll();
            }
            Log.v(TAG, "UdpReceiverThread initialized.");

            runLoop(mNativeHandle, mPreviousServerAddress, mPreviousServerPort);
        } finally {
            mCallback.onShutdown(getServerAddress(mNativeHandle), getServerPort(mNativeHandle));
            closeSocket(mNativeHandle);
            mNativeHandle = 0;
        }

        Log.v(TAG, "UdpReceiverThread stopped.");
    }

    // List broadcast address from all interfaces except for mobile network.
    // We should send all broadcast address to use USB tethering or VPN.
    private String[] getBroadcastAddressList() {
        List<String> ret = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();

            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();

                if (networkInterface.getName().startsWith("rmnet")) {
                    // Ignore mobile network interfaces.
                    Log.v(TAG, "Ignore interface. Name=" + networkInterface.getName());
                    continue;
                }

                List<InterfaceAddress> interfaceAddresses = networkInterface.getInterfaceAddresses();

                String address = "";
                for (InterfaceAddress interfaceAddress : interfaceAddresses) {
                    address += interfaceAddress.toString() + ", ";
                    // getBroadcast() return non-null only when ipv4.
                    if (interfaceAddress.getBroadcast() != null) {
                        ret.add(interfaceAddress.getBroadcast().getHostAddress());
                    }
                }
                Log.v(TAG, "Interface: Name=" + networkInterface.getName() + " Address=" + address + " 2=" + address);
            }
            Log.v(TAG, ret.size() + " broadcast addresses were found.");
            for (String address : ret) {
                Log.v(TAG, address);
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        if (ret.size() == 0) {
            ret.add(BROADCAST_ADDRESS);
        }
        return ret.toArray(new String[]{});
    }

    @Override
    public void onTracking(float[] position, float[] orientation) {
        if (isConnectedNative(mNativeHandle)) {
            mCallback.onTracking(position, orientation);
        }
    }

    public String getErrorMessage() {
        return mTrackingThread.getErrorMessage();
    }

    public boolean isConnected() {
        return isConnectedNative(mNativeHandle);
    }

    // called from native
    @SuppressWarnings("unused")
    public void onConnected(int width, int height, int codec, int frameQueueSize, int refreshRate) {
        Log.v(TAG, "onConnected is called.");
        mCallback.onConnected(width, height, codec, frameQueueSize, refreshRate);
        mTrackingThread.onConnect();
    }

    @SuppressWarnings("unused")
    public void onDisconnected() {
        Log.v(TAG, "onDisconnected is called.");
        mCallback.onDisconnect();
        mTrackingThread.onDisconnect();
    }

    @SuppressWarnings("unused")
    public void onChangeSettings(int EnableTestMode, int suspend, int frameQueueSize) {
        mCallback.onChangeSettings(EnableTestMode, suspend, frameQueueSize);
    }

    @SuppressWarnings("unused")
    public void send(long nativeBuffer, int bufferLength) {
        synchronized (mWaiter) {
            if (mNativeHandle == 0) {
                return;
            }
            sendNative(mNativeHandle, nativeBuffer, bufferLength);
        }
    }

    private native long initializeSocket(int port, String deviceName, String[] broadcastAddrList,
                                        int[] refreshRates, int renderWidth, int renderHeight, float[] fov,
                                        int deviceType, int deviceSubType, int deviceCapabilityFlags, int controllerCapabilityFlags);
    private native void closeSocket(long nativeHandle);
    private native void runLoop(long nativeHandle, String serverAddress, int serverPort);
    private native void interruptNative(long nativeHandle);

    private native void sendNative(long nativeHandle, long nativeBuffer, int bufferLength);

    public native boolean isConnectedNative(long nativeHandle);

    private native String getServerAddress(long nativeHandle);
    private native int getServerPort(long nativeHandle);

    //
    // NALParser interface
    //

    @Override
    public int getNalListSize() {
        return getNalListSizeNative(mNativeHandle);
    }
    @Override
    public NAL waitNal() {
        return waitNalNative(mNativeHandle);
    }
    @Override
    public NAL getNal() {
        return getNalNative(mNativeHandle);
    }
    @Override
    public void recycleNal(NAL nal) {
        recycleNalNative(mNativeHandle, nal);
    }
    @Override
    public void flushNALList() {
        flushNALListNative(mNativeHandle);
    }
    @Override
    public void notifyWaitingThread() {
        notifyWaitingThreadNative(mNativeHandle);
    }

    @Override
    public void clearStoppedAndPrepared() {
        clearStoppedAndPreparedNative(mNativeHandle);
    }

    @Override
    public void setSinkPrepared(boolean prepared) {
        synchronized (mWaiter) {
            if (mNativeHandle == 0) {
                return;
            }
            setSinkPreparedNative(mNativeHandle, prepared);
        }
    }

    private native int getNalListSizeNative(long nativeHandle);
    private native NAL waitNalNative(long nativeHandle);
    private native NAL getNalNative(long nativeHandle);
    private native void recycleNalNative(long nativeHandle, NAL nal);
    private native void flushNALListNative(long nativeHandle);
    private native void notifyWaitingThreadNative(long nativeHandle);
    private native void setSinkPreparedNative(long nativeHandle, boolean prepared);
    private native void clearStoppedAndPreparedNative(long nativeHandle);
}