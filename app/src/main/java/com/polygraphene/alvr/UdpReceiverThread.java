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
    private static final int REFRESH_RATE_COUNT = 4;

    private TrackingThread mTrackingThread;
    private int mPort = PORT;
    private int[] mRefreshRates = new int[REFRESH_RATE_COUNT];
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

    public boolean start(EGLContext mEGLContext, Activity activity, int[] refreshRates, int cameraTexture) {
        mTrackingThread = new TrackingThread();
        mTrackingThread.setCallback(this);

        System.arraycopy(refreshRates, 0, mRefreshRates, 0, REFRESH_RATE_COUNT);

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
        mTrackingThread.stopAndWait();
        interruptNative();
        super.stopAndWait();
    }

    @Override
    public void run() {
        try {
            String[] broadcastList = getBroadcastAddressList();

            int ret = initializeSocket(mPort, getDeviceName(), broadcastList, mRefreshRates);
            if (ret != 0) {
                Log.e(TAG, "Error on initializing socket. Code=" + ret + ".");
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

            runLoop(mPreviousServerAddress, mPreviousServerPort);
        } finally {
            mCallback.onShutdown(getServerAddress(), getServerPort());
            closeSocket();
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
        if (isConnected()) {
            mCallback.onTracking(position, orientation);
        }
    }

    public String getErrorMessage() {
        return mTrackingThread.getErrorMessage();
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

    private native int initializeSocket(int port, String deviceName, String[] broadcastAddrList, int[] refreshRates);
    private native void closeSocket();
    private native void runLoop(String serverAddress, int serverPort);
    private native void interruptNative();

    public native boolean isConnected();

    public native long getPointer();
    private native String getServerAddress();
    private native int getServerPort();

    //
    // NALParser interface
    //

    @Override
    public native int getNalListSize();

    @Override
    public native NAL waitNal();

    @Override
    public native NAL getNal();

    @Override
    public native void recycleNal(NAL nal);

    @Override
    public native void flushNALList();

    @Override
    public native void notifyWaitingThread();

    @Override
    public native void clearStopped();
}