/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.media;

import java.lang.ref.WeakReference;
import java.io.OutputStream;
import java.io.IOException;
import java.lang.IllegalArgumentException;
import java.lang.IllegalStateException;
import java.lang.Thread;
import java.nio.ByteBuffer;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

/**
 * The AudioRecord class manages the audio resources for Java applications
 * to record audio from the audio input hardware of the platform. This is
 * achieved by "pulling" (reading) the data from the AudioRecord object. The
 * application is responsible for polling the AudioRecord object in time using one of 
 * the following three methods:  {@link #read(byte[],int, int)}, {@link #read(short[], int, int)}
 * or {@link #read(ByteBuffer, int)}. The choice of which method to use will be based 
 * on the audio data storage format that is the most convenient for the user of AudioRecord.
 * <p>Upon creation, an AudioRecord object initializes its associated audio buffer that it will
 * fill with the new audio data. The size of this buffer, specified during the construction, 
 * determines how long an AudioRecord can record before "over-running" data that has not
 * been read yet. Data should be from the audio hardware in chunks of sizes inferior to
 * the total recording buffer size.
 */
public class AudioRecord
{
    //---------------------------------------------------------
    // Constants
    //--------------------
    /**
     *  State of an AudioRecord that was not successfully initialized upon creation 
     */
    public static final int STATE_UNINITIALIZED = 0;
    /**
     *  State of an AudioRecord that is ready to be used 
     */
    public static final int STATE_INITIALIZED   = 1;

    /**
     * State of an AudioRecord this is not recording 
     */
    public static final int RECORDSTATE_STOPPED = 1;  // matches SL_RECORDSTATE_STOPPED
    /**
     * State of an AudioRecord this is recording 
     */
    public static final int RECORDSTATE_RECORDING = 3;// matches SL_RECORDSTATE_RECORDING

    // Error codes:
    // to keep in sync with frameworks/base/core/jni/android_media_AudioRecord.cpp
    /**
     * Denotes a successful operation.
     */
    public static final int SUCCESS                 = 0;
    /**
     * Denotes a generic operation failure.
     */
    public static final int ERROR                   = -1;
    /**
     * Denotes a failure due to the use of an invalid value.
     */
    public static final int ERROR_BAD_VALUE         = -2;
    /**
     * Denotes a failure due to the improper use of a method.
     */
    public static final int ERROR_INVALID_OPERATION = -3;
    
    private static final int AUDIORECORD_ERROR_SETUP_ZEROFRAMECOUNT      = -16;
    private static final int AUDIORECORD_ERROR_SETUP_INVALIDCHANNELCOUNT = -17;
    private static final int AUDIORECORD_ERROR_SETUP_INVALIDFORMAT       = -18;
    private static final int AUDIORECORD_ERROR_SETUP_INVALIDSTREAMTYPE   = -19;
    private static final int AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED    = -20;
    
    // Events:
    // to keep in sync with frameworks/base/include/media/AudioRecord.h 
    /**
     * Event id for when the recording head has reached a previously set marker.
     */
    private static final int NATIVE_EVENT_MARKER  = 2;
    /**
     * Event id for when the previously set update period has passed during recording.
     */
    private static final int NATIVE_EVENT_NEW_POS = 3;
    
    private final static String TAG = "AudioRecord-Java";


    //---------------------------------------------------------
    // Used exclusively by native code
    //--------------------
    /** 
     * Accessed by native methods: provides access to C++ AudioRecord object 
     */
    @SuppressWarnings("unused")
    private int mNativeRecorderInJavaObj;
    /** 
     * Accessed by native methods: provides access to record source constants 
     */
    @SuppressWarnings("unused")
    private final static int SOURCE_DEFAULT = MediaRecorder.AudioSource.DEFAULT;
    @SuppressWarnings("unused")
    private final static int SOURCE_MIC = MediaRecorder.AudioSource.MIC;
    /** 
     * Accessed by native methods: provides access to the callback data.
     */
    @SuppressWarnings("unused")
    private int mNativeCallbackCookie;
    

    //---------------------------------------------------------
    // Member variables
    //--------------------    
    /**
     * The audio data sampling rate in Hz.
     */
    private int mSampleRate = 22050;
    /**
     * The number of input audio channels (1 is mono, 2 is stereo)
     */
    private int mChannelCount = 1;
    /**
     * The current audio channel configuration
     */
    private int mChannelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    /**
     * The encoding of the audio samples.
     * @see AudioFormat#ENCODING_PCM_8BIT
     * @see AudioFormat#ENCODING_PCM_16BIT
     */
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    /**
     * Where the audio data is recorded from.
     */
    private int mRecordSource = MediaRecorder.AudioSource.DEFAULT;
    /**
     * Indicates the state of the AudioRecord instance.
     */
    private int mState = STATE_UNINITIALIZED;
    /**
     * Indicates the recording state of the AudioRecord instance.
     */
    private int mRecordingState = RECORDSTATE_STOPPED;
    /**
     * Lock to make sure mRecordingState updates are reflecting the actual state of the object.
     */
    private Object mRecordingStateLock = new Object();
    /**
     * The listener the AudioRecord notifies when the record position reaches a marker
     * or for periodic updates during the progression of the record head.
     *  @see #setRecordPositionUpdateListener(OnRecordPositionUpdateListener)
     *  @see #setRecordPositionUpdateListener(OnRecordPositionUpdateListener, Handler)
     */
    private OnRecordPositionUpdateListener mPositionListener = null;
    /**
     * Lock to protect position listener updates against event notifications
     */
    private final Object mPositionListenerLock = new Object();
    /**
     * Handler for marker events coming from the native code
     */
    private NativeEventHandler mEventHandler = null;
    /**
     * Looper associated with the thread that creates the AudioRecord instance
     */
    private Looper mInitializationLooper = null;
    /**
     * Size of the native audio buffer.
     */
    private int mNativeBufferSizeInBytes = 0;

    
    //---------------------------------------------------------
    // Constructor, Finalize
    //--------------------
    /**
     * Class constructor.
     * @param audioSource the recording source. See {@link MediaRecorder.AudioSource} for
     *    recording source definitions.
     * @param sampleRateInHz the sample rate expressed in Hertz. Examples of rates are (but
     *   not limited to) 44100, 22050 and 11025.
     * @param channelConfig describes the configuration of the audio channels. 
     *   See {@link AudioFormat#CHANNEL_CONFIGURATION_MONO} and
     *   {@link AudioFormat#CHANNEL_CONFIGURATION_STEREO}
     * @param audioFormat the format in which the audio data is represented. 
     *   See {@link AudioFormat#ENCODING_PCM_16BIT} and 
     *   {@link AudioFormat#ENCODING_PCM_8BIT}
     * @param bufferSizeInBytes the total size (in bytes) of the buffer where audio data is written
     *   to during the recording. New audio data can be read from this buffer in smaller chunks 
     *   than this size.
     * @throws java.lang.IllegalArgumentException
     */
    public AudioRecord(int audioSource, int sampleRateInHz, int channelConfig, int audioFormat, 
            int bufferSizeInBytes)
    throws IllegalArgumentException {   
        mState = STATE_UNINITIALIZED;
        mRecordingState = RECORDSTATE_STOPPED;
        
        // remember which looper is associated with the AudioRecord instanciation
        if ((mInitializationLooper = Looper.myLooper()) == null) {
            mInitializationLooper = Looper.getMainLooper();
        }

        audioParamCheck(audioSource, sampleRateInHz, channelConfig, audioFormat);

        audioBuffSizeCheck(bufferSizeInBytes);

        // native initialization
        //TODO: update native initialization when information about hardware init failure
        //      due to capture device already open is available.
        int initResult = native_setup( new WeakReference<AudioRecord>(this), 
                mRecordSource, mSampleRate, mChannelCount, mAudioFormat, mNativeBufferSizeInBytes);
        if (initResult != SUCCESS) {
            loge("Error code "+initResult+" when initializing native AudioRecord object.");
            return; // with mState == STATE_UNINITIALIZED
        }

        mState = STATE_INITIALIZED;
    }


    // Convenience method for the constructor's parameter checks.
    // This is where constructor IllegalArgumentException-s are thrown
    // postconditions:
    //    mRecordSource is valid
    //    mChannelCount is valid
    //    mAudioFormat is valid
    //    mSampleRate is valid
    private void audioParamCheck(int audioSource, int sampleRateInHz, 
                                 int channelConfig, int audioFormat) {

        //--------------
        // audio source
        if ( (audioSource != MediaRecorder.AudioSource.DEFAULT)
                && (audioSource != MediaRecorder.AudioSource.MIC) ) {
            throw (new IllegalArgumentException("Invalid audio source."));
        } else {
            mRecordSource = audioSource;
        }
        
        //--------------
        // sample rate
        if ( (sampleRateInHz < 4000) || (sampleRateInHz > 48000) ) {
            throw (new IllegalArgumentException(sampleRateInHz
                    + "Hz is not a supported sample rate."));
        } else { 
            mSampleRate = sampleRateInHz;
        }

        //--------------
        // channel config
        switch (channelConfig) {
        case AudioFormat.CHANNEL_CONFIGURATION_DEFAULT:
        case AudioFormat.CHANNEL_CONFIGURATION_MONO:
            mChannelCount = 1;
            mChannelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_MONO;
            break;
        case AudioFormat.CHANNEL_CONFIGURATION_STEREO:
            mChannelCount = 2;
            mChannelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_STEREO;
            break;
        default:
            mChannelCount = 0;
        mChannelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_INVALID;
        throw (new IllegalArgumentException("Unsupported channel configuration."));
        }

        //--------------
        // audio format
        switch (audioFormat) {
        case AudioFormat.ENCODING_DEFAULT:
            mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
            break;
        case AudioFormat.ENCODING_PCM_16BIT:
        case AudioFormat.ENCODING_PCM_8BIT:
            mAudioFormat = audioFormat;
            break;
        default:
            mAudioFormat = AudioFormat.ENCODING_INVALID;
        throw (new IllegalArgumentException("Unsupported sample encoding." 
                + " Should be ENCODING_PCM_8BIT or ENCODING_PCM_16BIT."));
        }
    }


    // Convenience method for the contructor's audio buffer size check.
    // preconditions:
    //    mChannelCount is valid
    //    mAudioFormat is AudioFormat.ENCODING_PCM_8BIT OR AudioFormat.ENCODING_PCM_16BIT
    // postcondition:
    //    mNativeBufferSizeInBytes is valid (multiple of frame size, positive)
    private void audioBuffSizeCheck(int audioBufferSize) {
        // NB: this section is only valid with PCM data. 
        // To update when supporting compressed formats
        int frameSizeInBytes = mChannelCount 
            * (mAudioFormat == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2);
        if ((audioBufferSize % frameSizeInBytes != 0) || (audioBufferSize < 1)) {
            throw (new IllegalArgumentException("Invalid audio buffer size."));
        }

        mNativeBufferSizeInBytes = audioBufferSize;
    }    
    
    

    /**
     * Releases the native AudioRecord resources.
     */
    public void release() {
        try {
            stop();
        } catch(IllegalStateException ise) { 
            // don't raise an exception, we're releasing the resources.
        }
        native_release();
        mState = STATE_UNINITIALIZED;
    }
    

    @Override
    protected void finalize() {
        native_finalize();
    } 


    //--------------------------------------------------------------------------
    // Getters
    //--------------------
    /**
     * Returns the configured audio data sample rate in Hz
     */
    public int getSampleRate() {
        return mSampleRate;
    }
    
    /**
     * Returns the audio recording source. 
     * @see MediaRecorder.AudioSource
     */
    public int getAudioSource() {
        return mRecordSource;
    }

    /**
     * Returns the configured audio data format. See {@link AudioFormat#ENCODING_PCM_16BIT}
     * and {@link AudioFormat#ENCODING_PCM_8BIT}.
     */
    public int getAudioFormat() {
        return mAudioFormat;
    }

    /**
     * Returns the configured channel configuration. 
     * See {@link AudioFormat#CHANNEL_CONFIGURATION_MONO}
     * and {@link AudioFormat#CHANNEL_CONFIGURATION_STEREO}.
     */
    public int getChannelConfiguration() {
        return mChannelConfiguration;
    }

    /**
     * Returns the configured number of channels.
     */
    public int getChannelCount() {
        return mChannelCount;
    }

    /**
     * Returns the state of the AudioRecord instance. This is useful after the
     * AudioRecord instance has been created to check if it was initialized 
     * properly. This ensures that the appropriate hardware resources have been
     * acquired.
     * @see AudioRecord#STATE_INITIALIZED
     * @see AudioRecord#STATE_UNINITIALIZED
     */
    public int getState() {
        return mState;
    }

    /**
     * Returns the recording state of the AudioRecord instance.
     * @see AudioRecord#RECORDSTATE_STOPPED
     * @see AudioRecord#RECORDSTATE_RECORDING
     */
    public int getRecordingState() {
        return mRecordingState;
    }
    
    /**
     * @return marker position in frames
     */
    public int getNotificationMarkerPosition() {
        return native_get_marker_pos();
    }

    /**
     * @return update period in frames
     */
    public int getPositionNotificationPeriod() {
        return native_get_pos_update_period();
    }
    
    /**
     * Returns the minimum buffer size required for the successful creation of an AudioRecord
     * object.
     * @param sampleRateInHz the sample rate expressed in Hertz.
     * @param channelConfig describes the configuration of the audio channels. 
     *   See {@link AudioFormat#CHANNEL_CONFIGURATION_MONO} and
     *   {@link AudioFormat#CHANNEL_CONFIGURATION_STEREO}
     * @param audioFormat the format in which the audio data is represented. 
     *   See {@link AudioFormat#ENCODING_PCM_16BIT}.
     * @return {@link #ERROR_BAD_VALUE} if the recording parameters are not supported by the 
     *  hardware, or an invalid parameter was passed,
     *  or {@link #ERROR} if the implementation was unable to query the hardware for its 
     *  output properties, 
     *   or the minimum buffer size expressed in of bytes.
     */
    static public int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) {
        int channelCount = 0;
        switch(channelConfig) {
        case AudioFormat.CHANNEL_CONFIGURATION_DEFAULT:
        case AudioFormat.CHANNEL_CONFIGURATION_MONO:
            channelCount = 1;
            break;
        case AudioFormat.CHANNEL_CONFIGURATION_STEREO:
            channelCount = 2;
            break;
        case AudioFormat.CHANNEL_CONFIGURATION_INVALID:
        default:
            loge("getMinBufferSize(): Invalid channel configuration.");
            return AudioRecord.ERROR_BAD_VALUE;
        }
        
        // PCM_8BIT is not supported at the moment
        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT) {
            loge("getMinBufferSize(): Invalid audio format.");
            return AudioRecord.ERROR_BAD_VALUE;
        }
        
        int size = native_get_min_buff_size(sampleRateInHz, channelCount, audioFormat);
        if (size == 0) {
            return AudioRecord.ERROR_BAD_VALUE;
        } 
        else if (size == -1) {
            return AudioRecord.ERROR;
        }
        else {
            return size;
        }
    }


    //---------------------------------------------------------
    // Transport control methods
    //--------------------
    /**
     * Starts recording from the AudioRecord instance. 
     * @throws IllegalStateException
     */
    public void startRecording()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw(new IllegalStateException("startRecording() called on an "
                    +"uninitialized AudioRecord."));
        }

        // start recording
        synchronized(mRecordingStateLock) {
            native_start();
            mRecordingState = RECORDSTATE_RECORDING;
        }
    }



    /**
     * Stops recording.
     * @throws IllegalStateException
     */
    public void stop()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw(new IllegalStateException("stop() called on an uninitialized AudioRecord."));
        }

        // stop recording
        synchronized(mRecordingStateLock) {
            native_stop();
            mRecordingState = RECORDSTATE_STOPPED;
        }
    }


    //---------------------------------------------------------
    // Audio data supply
    //--------------------
    /**
     * Reads audio data from the audio hardware for recording into a buffer.
     * @param audioData the array to which the recorded audio data is written.
     * @param offsetInBytes index in audioData from which the data is written.
     * @param sizeInBytes the number of requested bytes.
     * @return the number of bytes that were read or or {@link #ERROR_INVALID_OPERATION}
     *    if the object wasn't properly initialized, or {@link #ERROR_BAD_VALUE} if
     *    the parameters don't resolve to valid data and indexes.
     *    The number of bytes will not exceed sizeInBytes.
     */    
    public int read(byte[] audioData, int offsetInBytes, int sizeInBytes) {
        if (mState != STATE_INITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        
        if ( (audioData == null) || (offsetInBytes < 0 ) || (sizeInBytes < 0) 
                || (offsetInBytes + sizeInBytes > audioData.length)) {
            return ERROR_BAD_VALUE;
        }

        return native_read_in_byte_array(audioData, offsetInBytes, sizeInBytes);
    }


    /**
     * Reads audio data from the audio hardware for recording into a buffer.
     * @param audioData the array to which the recorded audio data is written.
     * @param offsetInShorts index in audioData from which the data is written.
     * @param sizeInShorts the number of requested shorts.
     * @return the number of bytes that were read or or {@link #ERROR_INVALID_OPERATION}
     *    if the object wasn't properly initialized, or {@link #ERROR_BAD_VALUE} if
     *    the parameters don't resolve to valid data and indexes.
     *    The number of shorts will not exceed sizeInShorts.
     */    
    public int read(short[] audioData, int offsetInShorts, int sizeInShorts) {
        if (mState != STATE_INITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        
        if ( (audioData == null) || (offsetInShorts < 0 ) || (sizeInShorts < 0) 
                || (offsetInShorts + sizeInShorts > audioData.length)) {
            return ERROR_BAD_VALUE;
        }

        return native_read_in_short_array(audioData, offsetInShorts, sizeInShorts);
    }


    /**
     * Reads audio data from the audio hardware for recording into a direct buffer. If this buffer
     * is not a direct buffer, this method will always return 0.
     * @param audioBuffer the direct buffer to which the recorded audio data is written.
     * @param sizeInBytes the number of requested bytes.
     * @return the number of bytes that were read or or {@link #ERROR_INVALID_OPERATION}
     *    if the object wasn't properly initialized, or {@link #ERROR_BAD_VALUE} if
     *    the parameters don't resolve to valid data and indexes.
     *    The number of bytes will not exceed sizeInBytes.
     */    
    public int read(ByteBuffer audioBuffer, int sizeInBytes) {
        if (mState != STATE_INITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        
        if ( (audioBuffer == null) || (sizeInBytes < 0) ) {
            return ERROR_BAD_VALUE;
        }

        return native_read_in_direct_buffer(audioBuffer, sizeInBytes);
    }


    //--------------------------------------------------------------------------
    // Initialization / configuration
    //--------------------  
    /**
     * Sets the listener the AudioRecord notifies when a previously set marker is reached or
     * for each periodic record head position update.
     * @param listener
     */
    public void setRecordPositionUpdateListener(OnRecordPositionUpdateListener listener) {
        setRecordPositionUpdateListener(listener, null);
    }
    

    public void setRecordPositionUpdateListener(OnRecordPositionUpdateListener listener, 
                                                    Handler handler) {
        synchronized (mPositionListenerLock) {
            
            mPositionListener = listener;
            
            if (listener != null) {
                if (handler != null) {
                    mEventHandler = new NativeEventHandler(this, handler.getLooper());
                } else {
                    // no given handler, use the looper the AudioRecord was created in
                    mEventHandler = new NativeEventHandler(this, mInitializationLooper);
                }
            } else {
                mEventHandler = null;
            }
        }
        
    }
    
    
    /**
     * Sets the marker position at which the listener is called, if set with 
     * {@link #setRecordPositionUpdateListener(OnRecordPositionUpdateListener)} or 
     * {@link #setRecordPositionUpdateListener(OnRecordPositionUpdateListener, Handler)}.
     * @param markerInFrames marker position expressed in frames
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE},
     *  {@link #ERROR_INVALID_OPERATION} 
     */
    public int setNotificationMarkerPosition(int markerInFrames) {
        return native_set_marker_pos(markerInFrames);
    }
    
    
    /**
     * Sets the period at which the listener is called, if set with
     * {@link #setRecordPositionUpdateListener(OnRecordPositionUpdateListener)} or 
     * {@link #setRecordPositionUpdateListener(OnRecordPositionUpdateListener, Handler)}.
     * @param periodInFrames update period expressed in frames
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_INVALID_OPERATION}
     */
    public int setPositionNotificationPeriod(int periodInFrames) {
        return native_set_pos_update_period(periodInFrames);
    }


    //---------------------------------------------------------
    // Interface definitions
    //--------------------
    /**
     * Interface definition for a callback to be invoked when an AudioRecord has
     * reached a notification marker set by setNotificationMarkerPosition().
     */
    public interface OnRecordPositionUpdateListener  {
        /**
         * Called on the listener to notify it that the previously set marker has been reached
         * by the recording head.
         */
        void onMarkerReached(AudioRecord recorder);
        
        /**
         * Called on the listener to periodically notify it that the record head has reached
         * a multiple of the notification period.
         */
        void onPeriodicNotification(AudioRecord recorder);
    }
    
    

    
    //---------------------------------------------------------
    // Inner classes
    //--------------------
      
    /**
     * Helper class to handle the forwarding of native events to the appropriate listener
     * (potentially) handled in a different thread
     */  
    private class NativeEventHandler extends Handler {
        
        private final AudioRecord mAudioRecord;
        
        NativeEventHandler(AudioRecord recorder, Looper looper) {
            super(looper);
            mAudioRecord = recorder;
        }
        
        @Override
        public void handleMessage(Message msg) {
            OnRecordPositionUpdateListener listener = null;
            synchronized (mPositionListenerLock) {
                listener = mAudioRecord.mPositionListener;
            }
            
            switch(msg.what) {
            case NATIVE_EVENT_MARKER:
                if (listener != null) {
                    listener.onMarkerReached(mAudioRecord);
                }
                break;
            case NATIVE_EVENT_NEW_POS:
                if (listener != null) {
                    listener.onPeriodicNotification(mAudioRecord);
                }
                break;
            default:
                Log.e(TAG, "[ android.media.AudioRecord.NativeEventHandler ] " +
                        "Unknown event type: " + msg.what);
            break;
            }
        }
    };
    
    
    //---------------------------------------------------------
    // Java methods called from the native side
    //--------------------
    @SuppressWarnings("unused")
    private static void postEventFromNative(Object audiorecord_ref,
            int what, int arg1, int arg2, Object obj) {
        //logd("Event posted from the native side: event="+ what + " args="+ arg1+" "+arg2);
        AudioRecord recorder = (AudioRecord)((WeakReference)audiorecord_ref).get();
        if (recorder == null) {
            return;
        }
        
        if (recorder.mEventHandler != null) {
            Message m = 
                recorder.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            recorder.mEventHandler.sendMessage(m);
        }

    }
    

    //---------------------------------------------------------
    // Native methods called from the Java side
    //--------------------

    private native final int native_setup(Object audiorecord_this, 
            int recordSource, int sampleRate, int nbChannels, int audioFormat, int buffSizeInBytes);

    private native final void native_finalize();
    
    private native final void native_release();

    private native final void native_start();  

    private native final void native_stop();

    private native final int native_read_in_byte_array(byte[] audioData, 
            int offsetInBytes, int sizeInBytes);

    private native final int native_read_in_short_array(short[] audioData, 
            int offsetInShorts, int sizeInShorts);

    private native final int native_read_in_direct_buffer(Object jBuffer, int sizeInBytes);
    
    private native final int native_set_marker_pos(int marker);
    private native final int native_get_marker_pos();
    
    private native final int native_set_pos_update_period(int updatePeriod);
    private native final int native_get_pos_update_period();
    
    static private native final int native_get_min_buff_size(
            int sampleRateInHz, int channelCount, int audioFormat);

    
    //---------------------------------------------------------
    // Utility methods
    //------------------

    private static void logd(String msg) {
        Log.d(TAG, "[ android.media.AudioRecord ] " + msg);
    }

    private static void loge(String msg) {
        Log.e(TAG, "[ android.media.AudioRecord ] " + msg);
    }

}





