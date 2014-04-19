/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.camera2.impl;

import static android.hardware.camera2.CameraAccessException.CAMERA_IN_USE;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CaptureResultExtras;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.LongParcelable;
import android.hardware.camera2.utils.CameraBinderDecorator;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * HAL2.1+ implementation of CameraDevice. Use CameraManager#open to instantiate
 */
public class CameraDevice implements android.hardware.camera2.CameraDevice {

    private final String TAG;
    private final boolean DEBUG;

    private static final int REQUEST_ID_NONE = -1;

    // TODO: guard every function with if (!mRemoteDevice) check (if it was closed)
    private ICameraDeviceUser mRemoteDevice;

    private final Object mLock = new Object();
    private final CameraDeviceCallbacks mCallbacks = new CameraDeviceCallbacks();

    private final StateListener mDeviceListener;
    private final Handler mDeviceHandler;

    private boolean mIdle = true;

    private final SparseArray<CaptureListenerHolder> mCaptureListenerMap =
            new SparseArray<CaptureListenerHolder>();

    private int mRepeatingRequestId = REQUEST_ID_NONE;
    private final ArrayList<Integer> mRepeatingRequestIdDeletedList = new ArrayList<Integer>();
    // Map stream IDs to Surfaces
    private final SparseArray<Surface> mConfiguredOutputs = new SparseArray<Surface>();

    private final String mCameraId;

    /**
     * A list tracking request and its expected last frame.
     * Updated when calling ICameraDeviceUser methods.
     */
    private final List<SimpleEntry</*frameNumber*/Long, /*requestId*/Integer>>
            mFrameNumberRequestPairs = new ArrayList<SimpleEntry<Long, Integer>>();

    /**
     * An object tracking received frame numbers.
     * Updated when receiving callbacks from ICameraDeviceCallbacks.
     */
    private final FrameNumberTracker mFrameNumberTracker = new FrameNumberTracker();

    // Runnables for all state transitions, except error, which needs the
    // error code argument

    private final Runnable mCallOnOpened = new Runnable() {
        @Override
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onOpened(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnUnconfigured = new Runnable() {
        @Override
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onUnconfigured(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnActive = new Runnable() {
        @Override
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onActive(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnBusy = new Runnable() {
        @Override
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onBusy(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnClosed = new Runnable() {
        @Override
        public void run() {
            mDeviceListener.onClosed(CameraDevice.this);
        }
    };

    private final Runnable mCallOnIdle = new Runnable() {
        @Override
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onIdle(CameraDevice.this);
            }
        }
    };

    private final Runnable mCallOnDisconnected = new Runnable() {
        @Override
        public void run() {
            if (!CameraDevice.this.isClosed()) {
                mDeviceListener.onDisconnected(CameraDevice.this);
            }
        }
    };

    public CameraDevice(String cameraId, StateListener listener, Handler handler) {
        if (cameraId == null || listener == null || handler == null) {
            throw new IllegalArgumentException("Null argument given");
        }
        mCameraId = cameraId;
        mDeviceListener = listener;
        mDeviceHandler = handler;
        TAG = String.format("CameraDevice-%s-JV", mCameraId);
        DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    }

    public CameraDeviceCallbacks getCallbacks() {
        return mCallbacks;
    }

    public void setRemoteDevice(ICameraDeviceUser remoteDevice) {
        // TODO: Move from decorator to direct binder-mediated exceptions
        synchronized(mLock) {
            mRemoteDevice = CameraBinderDecorator.newInstance(remoteDevice);

            mDeviceHandler.post(mCallOnOpened);
            mDeviceHandler.post(mCallOnUnconfigured);
        }
    }

    @Override
    public String getId() {
        return mCameraId;
    }

    @Override
    public void configureOutputs(List<Surface> outputs) throws CameraAccessException {
        // Treat a null input the same an empty list
        if (outputs == null) {
            outputs = new ArrayList<Surface>();
        }
        synchronized (mLock) {
            checkIfCameraClosed();

            HashSet<Surface> addSet = new HashSet<Surface>(outputs);    // Streams to create
            List<Integer> deleteList = new ArrayList<Integer>();        // Streams to delete

            // Determine which streams need to be created, which to be deleted
            for (int i = 0; i < mConfiguredOutputs.size(); ++i) {
                int streamId = mConfiguredOutputs.keyAt(i);
                Surface s = mConfiguredOutputs.valueAt(i);

                if (!outputs.contains(s)) {
                    deleteList.add(streamId);
                } else {
                    addSet.remove(s);  // Don't create a stream previously created
                }
            }

            mDeviceHandler.post(mCallOnBusy);
            stopRepeating();

            try {
                waitUntilIdle();

                // TODO: mRemoteDevice.beginConfigure
                // Delete all streams first (to free up HW resources)
                for (Integer streamId : deleteList) {
                    mRemoteDevice.deleteStream(streamId);
                    mConfiguredOutputs.delete(streamId);
                }

                // Add all new streams
                for (Surface s : addSet) {
                    // TODO: remove width,height,format since we are ignoring
                    // it.
                    int streamId = mRemoteDevice.createStream(0, 0, 0, s);
                    mConfiguredOutputs.put(streamId, s);
                }

                // TODO: mRemoteDevice.endConfigure
            } catch (CameraRuntimeException e) {
                if (e.getReason() == CAMERA_IN_USE) {
                    throw new IllegalStateException("The camera is currently busy." +
                            " You must wait until the previous operation completes.");
                }

                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }

            if (outputs.size() > 0) {
                mDeviceHandler.post(mCallOnIdle);
            } else {
                mDeviceHandler.post(mCallOnUnconfigured);
            }
        }
    }

    @Override
    public CaptureRequest.Builder createCaptureRequest(int templateType)
            throws CameraAccessException {
        synchronized (mLock) {
            checkIfCameraClosed();

            CameraMetadataNative templatedRequest = new CameraMetadataNative();

            try {
                mRemoteDevice.createDefaultRequest(templateType, /* out */templatedRequest);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return null;
            }

            CaptureRequest.Builder builder =
                    new CaptureRequest.Builder(templatedRequest);

            return builder;
        }
    }

    @Override
    public int capture(CaptureRequest request, CaptureListener listener, Handler handler)
            throws CameraAccessException {
        if (DEBUG) {
            Log.d(TAG, "calling capture");
        }
        List<CaptureRequest> requestList = new ArrayList<CaptureRequest>();
        requestList.add(request);
        return submitCaptureRequest(requestList, listener, handler, /*streaming*/false);
    }

    @Override
    public int captureBurst(List<CaptureRequest> requests, CaptureListener listener,
            Handler handler) throws CameraAccessException {
        // TODO: remove this. Throw IAE if the request is null or empty. Need to update API doc.
        if (requests.isEmpty()) {
            Log.w(TAG, "Capture burst request list is empty, do nothing!");
            return -1;
        }
        return submitCaptureRequest(requests, listener, handler, /*streaming*/false);
    }

    /**
     * This method checks lastFrameNumber returned from ICameraDeviceUser methods for
     * starting and stopping repeating request and flushing.
     *
     * <p>If lastFrameNumber is NO_FRAMES_CAPTURED, it means that the request was never
     * sent to HAL. Then onCaptureSequenceCompleted is immediately triggered.
     * If lastFrameNumber is non-negative, then the requestId and lastFrameNumber pair
     * is added to the list mFrameNumberRequestPairs.</p>
     *
     * @param requestId the request ID of the current repeating request.
     *
     * @param lastFrameNumber last frame number returned from binder.
     */
    private void checkEarlyTriggerSequenceComplete(
            final int requestId, final long lastFrameNumber) {
        // lastFrameNumber being equal to NO_FRAMES_CAPTURED means that the request
        // was never sent to HAL. Should trigger onCaptureSequenceCompleted immediately.
        if (lastFrameNumber == CaptureListener.NO_FRAMES_CAPTURED) {
            final CaptureListenerHolder holder;
            int index = mCaptureListenerMap.indexOfKey(requestId);
            holder = (index >= 0) ? mCaptureListenerMap.valueAt(index) : null;
            if (holder != null) {
                mCaptureListenerMap.removeAt(index);
                if (DEBUG) {
                    Log.v(TAG, String.format(
                            "remove holder for requestId %d, "
                            + "because lastFrame is %d.",
                            requestId, lastFrameNumber));
                }
            }

            if (holder != null) {
                if (DEBUG) {
                    Log.v(TAG, "immediately trigger onCaptureSequenceCompleted because"
                            + " request did not reach HAL");
                }

                Runnable resultDispatch = new Runnable() {
                    @Override
                    public void run() {
                        if (!CameraDevice.this.isClosed()) {
                            if (DEBUG) {
                                Log.d(TAG, String.format(
                                        "early trigger sequence complete for request %d",
                                        requestId));
                            }
                            if (lastFrameNumber < Integer.MIN_VALUE
                                    || lastFrameNumber > Integer.MAX_VALUE) {
                                throw new AssertionError(lastFrameNumber + " cannot be cast to int");
                            }
                            holder.getListener().onCaptureSequenceCompleted(
                                    CameraDevice.this,
                                    requestId,
                                    (int)lastFrameNumber);
                        }
                    }
                };
                holder.getHandler().post(resultDispatch);
            } else {
                Log.w(TAG, String.format(
                        "did not register listener to request %d",
                        requestId));
            }
        } else {
            mFrameNumberRequestPairs.add(
                    new SimpleEntry<Long, Integer>(lastFrameNumber,
                            requestId));
        }
    }

    private int submitCaptureRequest(List<CaptureRequest> requestList, CaptureListener listener,
            Handler handler, boolean repeating) throws CameraAccessException {

        // Need a valid handler, or current thread needs to have a looper, if
        // listener is valid
        if (listener != null) {
            handler = checkHandler(handler);
        }

        synchronized (mLock) {
            checkIfCameraClosed();
            int requestId;

            if (repeating) {
                stopRepeating();
            }

            LongParcelable lastFrameNumberRef = new LongParcelable();
            try {
                requestId = mRemoteDevice.submitRequestList(requestList, repeating,
                        /*out*/lastFrameNumberRef);
                if (DEBUG) {
                    Log.v(TAG, "last frame number " + lastFrameNumberRef.getNumber());
                }
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return -1;
            }

            if (listener != null) {
                mCaptureListenerMap.put(requestId, new CaptureListenerHolder(listener,
                        requestList, handler, repeating));
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Listen for request " + requestId + " is null");
                }
            }

            long lastFrameNumber = lastFrameNumberRef.getNumber();

            if (repeating) {
                if (mRepeatingRequestId != REQUEST_ID_NONE) {
                    checkEarlyTriggerSequenceComplete(mRepeatingRequestId, lastFrameNumber);
                }
                mRepeatingRequestId = requestId;
            } else {
                mFrameNumberRequestPairs.add(
                        new SimpleEntry<Long, Integer>(lastFrameNumber, requestId));
            }

            if (mIdle) {
                mDeviceHandler.post(mCallOnActive);
            }
            mIdle = false;

            return requestId;
        }
    }

    @Override
    public int setRepeatingRequest(CaptureRequest request, CaptureListener listener,
            Handler handler) throws CameraAccessException {
        List<CaptureRequest> requestList = new ArrayList<CaptureRequest>();
        requestList.add(request);
        return submitCaptureRequest(requestList, listener, handler, /*streaming*/true);
    }

    @Override
    public int setRepeatingBurst(List<CaptureRequest> requests, CaptureListener listener,
            Handler handler) throws CameraAccessException {
        // TODO: remove this. Throw IAE if the request is null or empty. Need to update API doc.
        if (requests.isEmpty()) {
            Log.w(TAG, "Set Repeating burst request list is empty, do nothing!");
            return -1;
        }
        return submitCaptureRequest(requests, listener, handler, /*streaming*/true);
    }

    @Override
    public void stopRepeating() throws CameraAccessException {

        synchronized (mLock) {
            checkIfCameraClosed();
            if (mRepeatingRequestId != REQUEST_ID_NONE) {

                int requestId = mRepeatingRequestId;
                mRepeatingRequestId = REQUEST_ID_NONE;

                // Queue for deletion after in-flight requests finish
                if (mCaptureListenerMap.get(requestId) != null) {
                    mRepeatingRequestIdDeletedList.add(requestId);
                }

                try {
                    LongParcelable lastFrameNumberRef = new LongParcelable();
                    mRemoteDevice.cancelRequest(requestId, /*out*/lastFrameNumberRef);
                    long lastFrameNumber = lastFrameNumberRef.getNumber();

                    checkEarlyTriggerSequenceComplete(requestId, lastFrameNumber);

                } catch (CameraRuntimeException e) {
                    throw e.asChecked();
                } catch (RemoteException e) {
                    // impossible
                    return;
                }
            }
        }
    }

    private void waitUntilIdle() throws CameraAccessException {

        synchronized (mLock) {
            checkIfCameraClosed();
            if (mRepeatingRequestId != REQUEST_ID_NONE) {
                throw new IllegalStateException("Active repeating request ongoing");
            }

            try {
                mRemoteDevice.waitUntilIdle();
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }

            mRepeatingRequestId = REQUEST_ID_NONE;
        }
    }

    @Override
    public void flush() throws CameraAccessException {
        synchronized (mLock) {
            checkIfCameraClosed();

            mDeviceHandler.post(mCallOnBusy);
            try {
                LongParcelable lastFrameNumberRef = new LongParcelable();
                mRemoteDevice.flush(/*out*/lastFrameNumberRef);
                if (mRepeatingRequestId != REQUEST_ID_NONE) {
                    long lastFrameNumber = lastFrameNumberRef.getNumber();
                    checkEarlyTriggerSequenceComplete(mRepeatingRequestId, lastFrameNumber);
                    mRepeatingRequestId = REQUEST_ID_NONE;
                }
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
        }
    }

    @Override
    public void close() {
        synchronized (mLock) {

            try {
                if (mRemoteDevice != null) {
                    mRemoteDevice.disconnect();
                }
            } catch (CameraRuntimeException e) {
                Log.e(TAG, "Exception while closing: ", e.asChecked());
            } catch (RemoteException e) {
                // impossible
            }

            if (mRemoteDevice != null) {
                mDeviceHandler.post(mCallOnClosed);
            }

            mRemoteDevice = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        }
        finally {
            super.finalize();
        }
    }

    static class CaptureListenerHolder {

        private final boolean mRepeating;
        private final CaptureListener mListener;
        private final List<CaptureRequest> mRequestList;
        private final Handler mHandler;

        CaptureListenerHolder(CaptureListener listener, List<CaptureRequest> requestList,
                Handler handler, boolean repeating) {
            if (listener == null || handler == null) {
                throw new UnsupportedOperationException(
                    "Must have a valid handler and a valid listener");
            }
            mRepeating = repeating;
            mHandler = handler;
            mRequestList = new ArrayList<CaptureRequest>(requestList);
            mListener = listener;
        }

        public boolean isRepeating() {
            return mRepeating;
        }

        public CaptureListener getListener() {
            return mListener;
        }

        public CaptureRequest getRequest(int subsequenceId) {
            if (subsequenceId >= mRequestList.size()) {
                throw new IllegalArgumentException(
                        String.format(
                                "Requested subsequenceId %d is larger than request list size %d.",
                                subsequenceId, mRequestList.size()));
            } else {
                if (subsequenceId < 0) {
                    throw new IllegalArgumentException(String.format(
                            "Requested subsequenceId %d is negative", subsequenceId));
                } else {
                    return mRequestList.get(subsequenceId);
                }
            }
        }

        public CaptureRequest getRequest() {
            return getRequest(0);
        }

        public Handler getHandler() {
            return mHandler;
        }

    }

    /**
     * This class tracks the last frame number for submitted requests.
     */
    public class FrameNumberTracker {

        private long mCompletedFrameNumber = -1;
        private final TreeSet<Long> mFutureErrorSet = new TreeSet<Long>();

        private void update() {
            Iterator<Long> iter = mFutureErrorSet.iterator();
            while (iter.hasNext()) {
                long errorFrameNumber = iter.next();
                if (errorFrameNumber == mCompletedFrameNumber + 1) {
                    mCompletedFrameNumber++;
                    iter.remove();
                } else {
                    break;
                }
            }
        }

        /**
         * This function is called every time when a result or an error is received.
         * @param frameNumber: the frame number corresponding to the result or error
         * @param isError: true if it is an error, false if it is not an error
         */
        public void updateTracker(long frameNumber, boolean isError) {
            if (isError) {
                mFutureErrorSet.add(frameNumber);
            } else {
                /**
                 * HAL cannot send an OnResultReceived for frame N unless it knows for
                 * sure that all frames prior to N have either errored out or completed.
                 * So if the current frame is not an error, then all previous frames
                 * should have arrived. The following line checks whether this holds.
                 */
                if (frameNumber != mCompletedFrameNumber + 1) {
                    throw new AssertionError(String.format(
                            "result frame number %d comes out of order, should be %d + 1",
                            frameNumber, mCompletedFrameNumber));
                }
                mCompletedFrameNumber++;
            }
            update();
        }

        public long getCompletedFrameNumber() {
            return mCompletedFrameNumber;
        }

    }

    private void checkAndFireSequenceComplete() {
        long completedFrameNumber = mFrameNumberTracker.getCompletedFrameNumber();
        Iterator<SimpleEntry<Long, Integer> > iter = mFrameNumberRequestPairs.iterator();
        while (iter.hasNext()) {
            final SimpleEntry<Long, Integer> frameNumberRequestPair = iter.next();
            if (frameNumberRequestPair.getKey() <= completedFrameNumber) {

                // remove request from mCaptureListenerMap
                final int requestId = frameNumberRequestPair.getValue();
                final CaptureListenerHolder holder;
                synchronized (mLock) {
                    int index = mCaptureListenerMap.indexOfKey(requestId);
                    holder = (index >= 0) ? mCaptureListenerMap.valueAt(index)
                            : null;
                    if (holder != null) {
                        mCaptureListenerMap.removeAt(index);
                        if (DEBUG) {
                            Log.v(TAG, String.format(
                                    "remove holder for requestId %d, "
                                    + "because lastFrame %d is <= %d",
                                    requestId, frameNumberRequestPair.getKey(),
                                    completedFrameNumber));
                        }
                    }
                }
                iter.remove();

                // Call onCaptureSequenceCompleted
                if (holder != null) {
                    Runnable resultDispatch = new Runnable() {
                        @Override
                        public void run() {
                            if (!CameraDevice.this.isClosed()){
                                if (DEBUG) {
                                    Log.d(TAG, String.format(
                                            "fire sequence complete for request %d",
                                            requestId));
                                }

                                long lastFrameNumber = frameNumberRequestPair.getKey();
                                if (lastFrameNumber < Integer.MIN_VALUE
                                        || lastFrameNumber > Integer.MAX_VALUE) {
                                    throw new AssertionError(lastFrameNumber
                                            + " cannot be cast to int");
                                }
                                holder.getListener().onCaptureSequenceCompleted(
                                    CameraDevice.this,
                                    requestId,
                                    (int)lastFrameNumber);
                            }
                        }
                    };
                    holder.getHandler().post(resultDispatch);
                }

            }
        }
    }

    public class CameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {

        //
        // Constants below need to be kept up-to-date with
        // frameworks/av/include/camera/camera2/ICameraDeviceCallbacks.h
        //

        //
        // Error codes for onCameraError
        //

        /**
         * Camera has been disconnected
         */
        static final int ERROR_CAMERA_DISCONNECTED = 0;

        /**
         * Camera has encountered a device-level error
         * Matches CameraDevice.StateListener#ERROR_CAMERA_DEVICE
         */
        static final int ERROR_CAMERA_DEVICE = 1;

        /**
         * Camera has encountered a service-level error
         * Matches CameraDevice.StateListener#ERROR_CAMERA_SERVICE
         */
        static final int ERROR_CAMERA_SERVICE = 2;

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void onCameraError(final int errorCode, CaptureResultExtras resultExtras) {
            Runnable r = null;
            if (isClosed()) return;

            synchronized(mLock) {
                switch (errorCode) {
                    case ERROR_CAMERA_DISCONNECTED:
                        r = mCallOnDisconnected;
                        break;
                    default:
                        Log.e(TAG, "Unknown error from camera device: " + errorCode);
                        // no break
                    case ERROR_CAMERA_DEVICE:
                    case ERROR_CAMERA_SERVICE:
                        r = new Runnable() {
                            @Override
                            public void run() {
                                if (!CameraDevice.this.isClosed()) {
                                    mDeviceListener.onError(CameraDevice.this, errorCode);
                                }
                            }
                        };
                        break;
                }
                CameraDevice.this.mDeviceHandler.post(r);
            }

            // Fire onCaptureSequenceCompleted
            if (DEBUG) {
                Log.v(TAG, String.format("got error frame %d", resultExtras.getFrameNumber()));
            }
            mFrameNumberTracker.updateTracker(resultExtras.getFrameNumber(), /*error*/true);
            checkAndFireSequenceComplete();

        }

        @Override
        public void onCameraIdle() {
            if (isClosed()) return;

            if (DEBUG) {
                Log.d(TAG, "Camera now idle");
            }
            synchronized (mLock) {
                if (!CameraDevice.this.mIdle) {
                    CameraDevice.this.mDeviceHandler.post(mCallOnIdle);
                }
                CameraDevice.this.mIdle = true;
            }
        }

        @Override
        public void onCaptureStarted(final CaptureResultExtras resultExtras, final long timestamp) {
            int requestId = resultExtras.getRequestId();
            if (DEBUG) {
                Log.d(TAG, "Capture started for id " + requestId);
            }
            final CaptureListenerHolder holder;

            // Get the listener for this frame ID, if there is one
            synchronized (mLock) {
                holder = CameraDevice.this.mCaptureListenerMap.get(requestId);
            }

            if (holder == null) {
                return;
            }

            if (isClosed()) return;

            // Dispatch capture start notice
            holder.getHandler().post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!CameraDevice.this.isClosed()) {
                            holder.getListener().onCaptureStarted(
                                CameraDevice.this,
                                holder.getRequest(resultExtras.getSubsequenceId()),
                                timestamp);
                        }
                    }
                });
        }

        @Override
        public void onResultReceived(CameraMetadataNative result,
                CaptureResultExtras resultExtras) throws RemoteException {
            int requestId = resultExtras.getRequestId();
            if (DEBUG) {
                Log.v(TAG, "Received result frame " + resultExtras.getFrameNumber() + " for id "
                        + requestId);
            }
            final CaptureListenerHolder holder;
            synchronized (mLock) {
                holder = CameraDevice.this.mCaptureListenerMap.get(requestId);
            }

            Boolean quirkPartial = result.get(CaptureResult.QUIRKS_PARTIAL_RESULT);
            boolean quirkIsPartialResult = (quirkPartial != null && quirkPartial);

            // Update tracker (increment counter) when it's not a partial result.
            if (!quirkIsPartialResult) {
                mFrameNumberTracker.updateTracker(resultExtras.getFrameNumber(), /*error*/false);
            }

            // Check if we have a listener for this
            if (holder == null) {
                if (DEBUG) {
                    Log.d(TAG,
                            "holder is null, early return at frame "
                                    + resultExtras.getFrameNumber());
                }
                return;
            }

            if (isClosed()) {
                if (DEBUG) {
                    Log.d(TAG,
                            "camera is closed, early return at frame "
                                    + resultExtras.getFrameNumber());
                }
                return;
            }

            final CaptureRequest request = holder.getRequest(resultExtras.getSubsequenceId());
            final CaptureResult resultAsCapture = new CaptureResult(result, request, requestId);

            Runnable resultDispatch = null;

            // Either send a partial result or the final capture completed result
            if (quirkIsPartialResult) {
                // Partial result
                resultDispatch = new Runnable() {
                    @Override
                    public void run() {
                        if (!CameraDevice.this.isClosed()){
                            holder.getListener().onCapturePartial(
                                CameraDevice.this,
                                request,
                                resultAsCapture);
                        }
                    }
                };
            } else {
                // Final capture result
                resultDispatch = new Runnable() {
                    @Override
                    public void run() {
                        if (!CameraDevice.this.isClosed()){
                            holder.getListener().onCaptureCompleted(
                                CameraDevice.this,
                                request,
                                resultAsCapture);
                        }
                    }
                };
            }

            holder.getHandler().post(resultDispatch);

            // Fire onCaptureSequenceCompleted
            if (!quirkIsPartialResult) {
                checkAndFireSequenceComplete();
            }
        }

    }

    /**
     * Default handler management. If handler is null, get the current thread's
     * Looper to create a Handler with. If no looper exists, throw exception.
     */
    private Handler checkHandler(Handler handler) {
        if (handler == null) {
            Looper looper = Looper.myLooper();
            if (looper == null) {
                throw new IllegalArgumentException(
                    "No handler given, and current thread has no looper!");
            }
            handler = new Handler(looper);
        }
        return handler;
    }

    private void checkIfCameraClosed() {
        if (mRemoteDevice == null) {
            throw new IllegalStateException("CameraDevice was already closed");
        }
    }

    private boolean isClosed() {
        synchronized(mLock) {
            return (mRemoteDevice == null);
        }
    }
}
