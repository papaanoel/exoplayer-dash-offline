package fr.artestudio.offlinedrm.helper.widewine;

/**
 * Created by greg on 10/08/16.
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaDrm;
import android.media.NotProvisionedException;
import android.media.UnsupportedSchemeException;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.ParserException;
import com.google.android.exoplayer.drm.DrmInitData;
import com.google.android.exoplayer.extractor.DefaultExtractorInput;
import com.google.android.exoplayer.extractor.Extractor;
import com.google.android.exoplayer.extractor.ExtractorInput;
import com.google.android.exoplayer.extractor.ExtractorOutput;
import com.google.android.exoplayer.extractor.PositionHolder;
import com.google.android.exoplayer.extractor.SeekMap;
import com.google.android.exoplayer.extractor.TrackOutput;
import com.google.android.exoplayer.extractor.mp4.PsshAtomUtil;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSpec;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.Util;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import fr.artestudio.offlinedrm.R;

/**
 * Created by amritpalsingh on 16/12/15.
 * Adapter by gdebord on 10/09/15
 */
public class WidevineKeysFetcher implements ExtractorOutput
{

    PositionHolder _positionHolder;
    PostResponseHandler _postResponseHandler;

    private HandlerThread _requestHandlerThread;
    private Handler _postRequestHandler;
    private Extractor _extractor;
    private String _mimeType;
    private byte[] _schemeData;
    private byte[] _sessionId;
    private static final int MSG_KEYS = 1;
    private MediaDrm _mediaDrm;
    private static final String TAG = WidevineKeysFetcher.class.getSimpleName();
    private String _assetID;
    private Context _context;


    public WidevineKeysFetcher()
    {
        _positionHolder = new PositionHolder();
    }

    public void fetchKeys(Context context, String assetID, String userAgent, String filePath, Extractor... extractors)
    {

        _assetID = assetID;
        _context = context;

        _positionHolder = new PositionHolder();

        int result = Extractor.RESULT_CONTINUE;
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter(null, null);
        DataSource dataSource = new DefaultUriDataSource(_context, bandwidthMeter, userAgent);

        ExtractorInput input = null;
        try
        {
            long position = _positionHolder.position;
            long length = 0;

            length = dataSource.open(new DataSpec(Uri.parse(filePath), position, C.LENGTH_UNBOUNDED, null));

            if (length != C.LENGTH_UNBOUNDED)
            {
                length += position;
            }
            input = new DefaultExtractorInput(dataSource, position, length);
            if (extractors == null || extractors.length == 0)
            {
                extractors = new Extractor[DEFAULT_EXTRACTOR_CLASSES.size()];
                for (int i = 0; i < extractors.length; i++)
                {
                    try
                    {
                        extractors[i] = DEFAULT_EXTRACTOR_CLASSES.get(i).newInstance();
                    }
                    catch (InstantiationException e)
                    {
                        throw new IllegalStateException("Unexpected error creating default extractor", e);
                    }
                    catch (IllegalAccessException e)
                    {
                        throw new IllegalStateException("Unexpected error creating default extractor", e);
                    }
                }
            }

            ExtractorHolder extractorHolder = new ExtractorHolder(extractors, this);
            _extractor = extractorHolder.selectExtractor(input);

            while (result == Extractor.RESULT_CONTINUE)
            {
                result = _extractor.read(input, _positionHolder);
            }
        }

        catch (IOException e)
        {
            e.printStackTrace();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            if (result == Extractor.RESULT_SEEK)
            {
                result = Extractor.RESULT_CONTINUE;
            }
            else if (input != null)
            {
                _positionHolder.position = input.getPosition();
            }

            try
            {
                dataSource.close();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    /**
     * Invoked when the {@link Extractor} identifies the existence of a track in the stream.
     * <p/>
     * Returns a {@link TrackOutput} that will receive track level data belonging to the track.
     *
     * @param trackId A track identifier.
     * @return The {@link TrackOutput} that should receive track level data belonging to the track.
     */
    @Override
    public TrackOutput track(int trackId)
    {
        return null;
    }

    /**
     * Invoked when all tracks have been identified, meaning that {@link #track(int)} will not be
     * invoked again.
     */
    @Override
    public void endTracks()
    {

    }

    /**
     * Invoked when a {@link SeekMap} has been extracted from the stream.
     *
     * @param seekMap The extracted {@link SeekMap}.
     */
    @Override
    public void seekMap(SeekMap seekMap)
    {

    }

    /**
     * Invoked when {@link DrmInitData} has been extracted from the stream.
     *
     * @param drmInitData The extracted {@link DrmInitData}.
     */
    @Override
    public void drmInitData(DrmInitData drmInitData)
    {
        createFetchKeyRequest(drmInitData);
    }

    private void initDRM()
    {
        try
        {
            _mediaDrm = new MediaDrm(OfflineDRMSessionManager.WIDEVINE_UUID);
            _sessionId = _mediaDrm.openSession();
            postKeyRequest();
        }
        catch (UnsupportedSchemeException e)
        {
            e.printStackTrace();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }


    private void postKeyRequest()
    {
        MediaDrm.KeyRequest keyRequest;
        try
        {
            keyRequest = _mediaDrm.getKeyRequest(_sessionId, _schemeData, _mimeType,
                    MediaDrm.KEY_TYPE_OFFLINE, null);
            _postRequestHandler.obtainMessage(MSG_KEYS, keyRequest).sendToTarget();
        }
        catch (NotProvisionedException e)
        {
            // onKeysError(e);
        }
    }


    private void createFetchKeyRequest(DrmInitData drmInitData)
    {
        if (_postRequestHandler == null)
        {
            _postResponseHandler = new PostResponseHandler(_context.getMainLooper());
        }
        if (_postRequestHandler == null)
        {
            _requestHandlerThread = new HandlerThread("DrmRequestHandler");
            _requestHandlerThread.start();
            _postRequestHandler = new PostRequestHandler(_requestHandlerThread.getLooper());
        }
        if (_schemeData == null)
        {
            _mimeType = drmInitData.get(OfflineDRMSessionManager.WIDEVINE_UUID).mimeType;
            _schemeData = drmInitData.get(OfflineDRMSessionManager.WIDEVINE_UUID).data;
            if (_schemeData == null)
            {
                return;
            }
            if (Util.SDK_INT < 21)
            {
                Log.i(TAG, "+++Android version<21, Reading pssh header");
                // Prior to L the Widevine CDM required data to be extracted from the PSSH atom.
                byte[] psshData = PsshAtomUtil.parseSchemeSpecificData(_schemeData, OfflineDRMSessionManager.WIDEVINE_UUID);
                if (psshData == null)
                {
                    // Extraction failed. _schemeData isn't a Widevine PSSH atom, so leave it unchanged.
                }
                else
                {
                    _schemeData = psshData;
                }
            }
        }
        initDRM();
    }

    @SuppressLint("HandlerLeak")
    private class PostRequestHandler extends Handler
    {

        public PostRequestHandler(Looper backgroundLooper)
        {
            super(backgroundLooper);
        }

        @Override
        public void handleMessage(Message msg)
        {
            Object response;
            try
            {
                switch (msg.what)
                {
                    case MSG_KEYS:
                        response = new WidevineMediaDrmCallback(true).executeKeyRequest(OfflineDRMSessionManager.WIDEVINE_UUID, (MediaDrm.KeyRequest) msg.obj);
                        break;
                    default:
                        throw new RuntimeException();
                }
            }
            catch (Exception e)
            {
                response = e;
            }
            _postResponseHandler.obtainMessage(msg.what, response).sendToTarget();
        }

    }

    @SuppressLint("HandlerLeak")
    private class PostResponseHandler extends Handler
    {

        public PostResponseHandler(Looper looper)
        {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
                case MSG_KEYS:
                    onKeyResponse(msg.obj);
                    return;
            }
        }

    }

    private void onKeyResponse(Object response)
    {
        if (response instanceof Exception)
        {
            return;
        }

        try
        {
            byte[] keySetID = _mediaDrm.provideKeyResponse(_sessionId, (byte[]) response);
            Log.i(TAG, "RESPONSE !!!!! : " + new String((byte[]) response));
            Log.d(TAG, "++++++key set id after response : " + new String(keySetID));

            // Store the licence where ever your want. Might be better to encrypt it !!!
        }

        catch (Exception e)
        {
            Log.i(TAG, "Error while retrieving licence !!!");
        }
    }

    private static final List<Class<? extends Extractor>> DEFAULT_EXTRACTOR_CLASSES;
    static {
        DEFAULT_EXTRACTOR_CLASSES = new ArrayList<>();
        // Load extractors using reflection so that they can be deleted cleanly.
        // Class.forName(<class name>) appears for each extractor so that automated tools like proguard
        // can detect the use of reflection (see http://proguard.sourceforge.net/FAQ.html#forname).
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.extractor.webm.WebmExtractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.extractor.mp4.FragmentedMp4Extractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.extractor.mp4.Mp4Extractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.extractor.mp3.Mp3Extractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.extractor.ts.AdtsExtractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.extractor.ts.TsExtractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.extractor.flv.FlvExtractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.extractor.ogg.OggExtractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.extractor.ts.PsExtractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.extractor.wav.WavExtractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
        try {
            DEFAULT_EXTRACTOR_CLASSES.add(
                    Class.forName("com.google.android.exoplayer.ext.flac.FlacExtractor")
                            .asSubclass(Extractor.class));
        } catch (ClassNotFoundException e) {
            // Extractor not found.
        }
    }

    /**
     * Stores a list of extractors and a selected extractor when the format has been detected.
     */
    private static final class ExtractorHolder {

        private final Extractor[] extractors;
        private final ExtractorOutput extractorOutput;
        private Extractor extractor;

        /**
         * Creates a holder that will select an extractor and initialize it using the specified output.
         *
         * @param extractors One or more extractors to choose from.
         * @param extractorOutput The output that will be used to initialize the selected extractor.
         */
        public ExtractorHolder(Extractor[] extractors, ExtractorOutput extractorOutput) {
            this.extractors = extractors;
            this.extractorOutput = extractorOutput;
        }

        /**
         * Returns an initialized extractor for reading {@code input}, and returns the same extractor on
         * later calls.
         *
         * @param input The {@link ExtractorInput} from which data should be read.
         * @throws UnrecognizedInputFormatException Thrown if the input format could not be detected.
         * @throws IOException Thrown if the input could not be read.
         * @throws InterruptedException Thrown if the thread was interrupted.
         */
        public Extractor selectExtractor(ExtractorInput input)
                throws UnrecognizedInputFormatException, IOException, InterruptedException {
            if (extractor != null) {
                return extractor;
            }
            for (Extractor extractor : extractors) {
                try {
                    if (extractor.sniff(input)) {
                        this.extractor = extractor;
                        break;
                    }
                } catch (EOFException e) {
                    // Do nothing.
                } finally {
                    input.resetPeekPosition();
                }
            }
            if (extractor == null) {
                throw new UnrecognizedInputFormatException(extractors);
            }
            extractor.init(extractorOutput);
            return extractor;
        }

    }

    /**
     * Thrown if the input format could not recognized.
     */
    public static final class UnrecognizedInputFormatException extends ParserException {

        public UnrecognizedInputFormatException(Extractor[] extractors) {
            super("None of the available extractors ("
                    + Util.getCommaDelimitedSimpleClassNames(extractors) + ") could read the stream.");
        }

    }

}
