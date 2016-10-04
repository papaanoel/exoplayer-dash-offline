package fr.artestudio.offlinedrm.helper.widewine;

/**
 * Created by greg on 10/08/16.
 */

import android.annotation.SuppressLint;
import android.media.MediaCrypto;
import android.media.MediaDrm;
import android.media.NotProvisionedException;
import android.media.UnsupportedSchemeException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.google.android.exoplayer.drm.DrmInitData;
import com.google.android.exoplayer.drm.DrmSessionManager;
import com.google.android.exoplayer.drm.KeysExpiredException;
import com.google.android.exoplayer.drm.MediaDrmCallback;
import com.google.android.exoplayer.drm.StreamingDrmSessionManager;
import com.google.android.exoplayer.drm.UnsupportedDrmException;

import java.util.HashMap;
import java.util.UUID;

/**
 * Created by amritpalsingh on 16/12/15.
 */
public class OfflineDRMSessionManager implements DrmSessionManager
{

    /**
     * UUID for the Widevine DRM scheme.
     */
    public static final UUID WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed");

    private final Handler eventHandler;
    private final StreamingDrmSessionManager.EventListener eventListener;
    private final MediaDrm mediaDrm;
    private final HashMap<String, String> optionalKeyRequestParameters;

    /* package */ final MediaDrmHandler mediaDrmHandler;
    /* package */ final MediaDrmCallback callback;
    /* package */ final UUID uuid;

    private int openCount;
    private int state;
    private MediaCrypto mediaCrypto;
    private Exception lastException;
    private byte[] sessionId;
    private byte[] keySetID;
    private static final String TAG = OfflineDRMSessionManager.class.getSimpleName();


    /**
     * Instantiates a new instance using the Widevine scheme.
     *
     * @param playbackLooper               The looper associated with the media playback thread. Should usually be
     *                                     obtained using {@link com.google.android.exoplayer.ExoPlayer#getPlaybackLooper()}.
     * @param callback                     Performs key and provisioning requests.
     * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
     *                                     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
     * @param eventHandler                 A handler to use when delivering events to {@code eventListener}. May be
     *                                     null if delivery of events is not required.
     * @param eventListener                A listener of events. May be null if delivery of events is not required.
     * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
     */
    public static OfflineDRMSessionManager newWidevineInstance(Looper playbackLooper,
                                                               MediaDrmCallback callback, HashMap<String, String> optionalKeyRequestParameters,
                                                               Handler eventHandler, StreamingDrmSessionManager.EventListener eventListener, byte[] keySetId) throws UnsupportedDrmException
    {
        return new OfflineDRMSessionManager(WIDEVINE_UUID, playbackLooper, callback,
                optionalKeyRequestParameters, eventHandler, eventListener,keySetId);
    }

    /**
     * @param uuid                         The UUID of the drm scheme.
     * @param playbackLooper               The looper associated with the media playback thread. Should usually be
     *                                     obtained using {@link com.google.android.exoplayer.ExoPlayer#getPlaybackLooper()}.
     * @param callback                     Performs key and provisioning requests.
     * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
     *                                     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
     * @param eventHandler                 A handler to use when delivering events to {@code eventListener}. May be
     *                                     null if delivery of events is not required.
     * @param eventListener                A listener of events. May be null if delivery of events is not required.
     * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
     */
    private OfflineDRMSessionManager(UUID uuid, Looper playbackLooper, MediaDrmCallback callback,
                                     HashMap<String, String> optionalKeyRequestParameters, Handler eventHandler,
                                     StreamingDrmSessionManager.EventListener eventListener, byte[] keySetId) throws UnsupportedDrmException
    {
        this.uuid = uuid;
        this.callback = callback;
        this.optionalKeyRequestParameters = optionalKeyRequestParameters;
        this.eventHandler = eventHandler;
        this.eventListener = eventListener;
        this.keySetID =keySetId;
        try
        {
            mediaDrm = new MediaDrm(uuid);
        }
        catch (UnsupportedSchemeException e)
        {
            throw new UnsupportedDrmException(UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME, e);
        }
        catch (Exception e)
        {
            throw new UnsupportedDrmException(UnsupportedDrmException.REASON_INSTANTIATION_ERROR, e);
        }
        mediaDrm.setOnEventListener(new MediaDrmEventListener());
        mediaDrmHandler = new MediaDrmHandler(playbackLooper);
        state = STATE_CLOSED;
    }


    /**
     * Opens the session, possibly asynchronously.
     *
     * @param drmInitData DRM initialization data.
     */
    @Override
    public void open(DrmInitData drmInitData)
    {
        if (++openCount != 1)
        {
            return;
        }
        state = STATE_OPENING;
        openInternal(true);
    }


    private void openInternal(boolean allowProvisioning)
    {
        try
        {
            sessionId = mediaDrm.openSession();
            Log.i(TAG, "+++Creating MediaCrypto");
            mediaCrypto = new MediaCrypto(uuid, sessionId);
            state = STATE_OPENED;
            restoreKeys();
        }
        catch (NotProvisionedException e)
        {
            if (allowProvisioning)
            {
                //postProvisionRequest();
            }
            else
            {
                onError(e);
            }
        }
        catch (Exception e)
        {
            onError(e);
        }
    }

    /**
     * Closes the session.
     */
    @Override
    public void close()
    {
        if (--openCount != 0)
        {
            return;
        }
        state = STATE_CLOSED;
        mediaDrmHandler.removeCallbacksAndMessages(null);
        mediaCrypto = null;
        lastException = null;
        if (sessionId != null)
        {
            mediaDrm.closeSession(sessionId);
            sessionId = null;
        }
    }

    /**
     * Gets the current state of the session.
     *
     * @return One of {@link #STATE_ERROR}, {@link #STATE_CLOSED}, {@link #STATE_OPENING},
     * {@link #STATE_OPENED} and {@link #STATE_OPENED_WITH_KEYS}.
     */
    @Override
    public int getState()
    {
        return state;
    }

    /**
     * Gets a {@link MediaCrypto} for the open session.
     * <p/>
     * This method may be called when the manager is in the following states:
     * {@link #STATE_OPENED}, {@link #STATE_OPENED_WITH_KEYS}
     *
     * @return A {@link MediaCrypto} for the open session.
     * @throws IllegalStateException If called when a session isn't opened.
     */
    @Override
    public MediaCrypto getMediaCrypto()
    {
        if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS)
        {
            throw new IllegalStateException();
        }
        return mediaCrypto;
    }

    /**
     * Whether the session requires a secure decoder for the specified mime type.
     * <p/>
     * Normally this method should return {@link MediaCrypto#requiresSecureDecoderComponent(String)},
     * however in some cases implementations  may wish to modify the return value (i.e. to force a
     * secure decoder even when one is not required).
     * <p/>
     * This method may be called when the manager is in the following states:
     * {@link #STATE_OPENED}, {@link #STATE_OPENED_WITH_KEYS}
     *
     * @param mimeType
     * @return Whether the open session requires a secure decoder for the specified mime type.
     * @throws IllegalStateException If called when a session isn't opened.
     */
    @Override
    public boolean requiresSecureDecoderComponent(String mimeType)
    {
        if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS)
        {
            throw new IllegalStateException();
        }
        return mediaCrypto.requiresSecureDecoderComponent(mimeType);
    }

    /**
     * Gets the cause of the error state.
     * <p/>
     * This method may be called when the manager is in any state.
     *
     * @return An exception if the state is {@link #STATE_ERROR}. Null otherwise.
     */
    @Override
    public Exception getError()
    {
        return state == STATE_ERROR ? lastException : null;
    }


    @SuppressLint("HandlerLeak")
    private class MediaDrmHandler extends Handler
    {

        public MediaDrmHandler(Looper looper)
        {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg)
        {
            if (openCount == 0 || (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS))
            {
                return;
            }
            switch (msg.what)
            {
                case MediaDrm.EVENT_KEY_REQUIRED:
                    restoreKeys();
                    return;
                case MediaDrm.EVENT_KEY_EXPIRED:
                    state = STATE_OPENED;
                    onError(new KeysExpiredException());
                    return;
            }
        }

    }

    private class MediaDrmEventListener implements MediaDrm.OnEventListener
    {

        @Override
        public void onEvent(MediaDrm md, byte[] sessionId, int event, int extra, byte[] data)
        {
            mediaDrmHandler.sendEmptyMessage(event);
        }

    }

    private void onError(final Exception e)
    {
        lastException = e;
        if (eventHandler != null && eventListener != null)
        {
            eventHandler.post(new Runnable()
            {
                @Override
                public void run()
                {
                    eventListener.onDrmSessionManagerError(e);
                }
            });
        }
        if (state != STATE_OPENED_WITH_KEYS)
        {
            state = STATE_ERROR;
        }
    }

    private void restoreKeys()
    {
        if(keySetID ==null)
        {
            return;
        }

        mediaDrm.restoreKeys(sessionId, keySetID);
        state = STATE_OPENED_WITH_KEYS;
    }

}
