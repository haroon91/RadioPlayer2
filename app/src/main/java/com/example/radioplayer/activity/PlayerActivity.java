package com.example.radioplayer.activity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.design.widget.CoordinatorLayout;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.example.radioplayer.R;
import com.example.radioplayer.RadioPlayerApplication;
import com.example.radioplayer.data.StationDataCache;
import com.example.radioplayer.event.PlaybackServiceEvent;
import com.example.radioplayer.model.Station;
import com.example.radioplayer.model.Stream;
import com.example.radioplayer.service.PlaybackService;
import com.example.radioplayer.util.Utils;
import com.squareup.otto.Subscribe;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

public class PlayerActivity extends AppCompatActivity implements
        View.OnClickListener, ServiceConnection{


    private static final String BUNDLE_STATE = "state";
    private static final String BUNDLE_CURRENT_STATION = "current_station";

    public static final String BUNDLE_QUEUE_POSITION = "queue_position";
    public static final String BUNDLE_STATION_QUEUE = "station_queue"; // station list

    private TextView mStationTitle;
    private ImageButton mPlayStopBtn;
    private ImageButton mNextBtn;
    private ImageButton mPrevBtn;
    private ProgressBar mProgressBar;
    private CoordinatorLayout mCoordinatorLayout;
    private MediaControllerCompat mMediaController;
    private Station mStation;
    private boolean mFirstTimeIn = true;
    private List<Station> mQueue;
    private int mQueuePosition;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        mCoordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinator_layout);
        mProgressBar = (ProgressBar) findViewById(R.id.progress_bar);
        mStationTitle = (TextView) findViewById(R.id.station_title);

        // set up the toolbar
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if(getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // setup player controls elements
        mPlayStopBtn = (ImageButton) findViewById(R.id.play_stop_button);
        mPlayStopBtn.setOnClickListener(this);
        mPrevBtn = (ImageButton) findViewById(R.id.previous_button);
        mPrevBtn.setOnClickListener(this);
        mNextBtn = (ImageButton) findViewById(R.id.next_button);
        mNextBtn.setOnClickListener(this);

        // retrieve the queue from the data cache
        mQueue = StationDataCache.getStationDataCache().getStationList();

        if(savedInstanceState != null) {
            mFirstTimeIn = false;

            // restore player state
            mQueuePosition = savedInstanceState.getInt(BUNDLE_QUEUE_POSITION, 0);
            int state = savedInstanceState.getInt(BUNDLE_STATE);

            if(state == PlaybackStateCompat.STATE_BUFFERING) {
                mPlayStopBtn.setImageResource(R.drawable.action_stop);
                mProgressBar.setVisibility(View.VISIBLE);
            }
            if(state == PlaybackStateCompat.STATE_PLAYING)
                mPlayStopBtn.setImageResource(R.drawable.action_stop);

        } else {
            mQueuePosition = getIntent().getIntExtra(BUNDLE_QUEUE_POSITION, 0);
        }

        // set the station  and title
        mStation = mQueue.get(mQueuePosition);
        mStationTitle.setText(mStation.getName());

        // hide prev/next btns to prevent use if starting from the first or last station
        if(mQueuePosition == 0) {
            mPrevBtn.setVisibility(View.GONE);
        }
        if(mQueuePosition == mQueue.size() - 1) {
            mNextBtn.setVisibility(View.GONE);
        }

        // create the intent used to both start & bind to the service
        Intent intent = new Intent(this, PlaybackService.class);
        this.getApplicationContext().bindService(intent, this, 0);
        Timber.i("Binding Playback Service");
        startService(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        int state = mMediaController.getPlaybackState().getState();
        outState.putInt(BUNDLE_STATE, state);
        outState.putInt(BUNDLE_QUEUE_POSITION, mQueuePosition);
        outState.putParcelable(BUNDLE_CURRENT_STATION, mStation);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.getApplicationContext().unbindService(this);
        Timber.i("Unbinding Playback Service");
    }

    @Override
    protected void onResume() {
        super.onResume();
        RadioPlayerApplication.getInstance().getBus().register(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        RadioPlayerApplication.getInstance().getBus().unregister(this);
    }


    @Override
    public void onClick(View view) {
        int state = mMediaController.getPlaybackState().getState();
        Timber.i("Current state onClick: %d", state);
        switch(view.getId()) {

            case R.id.play_stop_button:
                if(state == PlaybackStateCompat.STATE_NONE
                        || state == PlaybackStateCompat.STATE_STOPPED) {

                    // start playback
                    Timber.i("Clicked play");
                    playFromStationUri();
                } else if(state == PlaybackStateCompat.STATE_BUFFERING || state == PlaybackStateCompat.STATE_PLAYING){
                    // stop playback
                    mMediaController.getTransportControls().stop();
                    Timber.i("Clicked stop");
                }
                break;

            // TODO use mediaController skipToNExt() and skipToPrevious()
            case R.id.previous_button:
                if(state == PlaybackStateCompat.STATE_BUFFERING || state == PlaybackStateCompat.STATE_PLAYING) {
                    mMediaController.getTransportControls().stop();
                }
                --mQueuePosition;
                updatePlayer();
                break;

            case R.id.next_button:
                if(state == PlaybackStateCompat.STATE_BUFFERING || state == PlaybackStateCompat.STATE_PLAYING) {
                    mMediaController.getTransportControls().stop();
                }
                ++mQueuePosition;
                updatePlayer();
                break;
        }
    }


    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if(service instanceof PlaybackService.ServiceBinder) {
            try {
                mMediaController = new MediaControllerCompat(PlayerActivity.this,
                        ((PlaybackService.ServiceBinder) service).getService().getMediaSessionToken());

                mMediaController.registerCallback(mMediaControllerCallback);

                int state = mMediaController.getPlaybackState().getState();
                Timber.i("Launching PlayerActivity, connecting to PlaybackService, current state: %d", state);

                if(mFirstTimeIn) { // stop restarting every time the device rotates
                    // start playback as soon as player activity launches
                    Timber.i("First time in");
                    if(state == PlaybackStateCompat.STATE_NONE || state == PlaybackStateCompat.STATE_STOPPED) {

                        playFromStationUri();

                    } else if(state == PlaybackStateCompat.STATE_BUFFERING || state == PlaybackStateCompat.STATE_PLAYING){

                        // if already playing, stop and start the new selected stn
                        mMediaController.getTransportControls().stop();
                        playFromStationUri();
                    }
                } else {
                    Timber.i("Device rotated");
                }

            } catch (RemoteException e) {
                Timber.e("Error instantiating the media controller: %s", e.getMessage());
            }
        }
    }


    @Override
    public void onServiceDisconnected(ComponentName name) {
        Timber.i("Disconnected from Playback Service");
    }


    private MediaControllerCompat.Callback mMediaControllerCallback =
        new MediaControllerCompat.Callback() {

            @Override
            public void onPlaybackStateChanged(PlaybackStateCompat state) {
                switch (state.getState()) {
                    case PlaybackStateCompat.STATE_NONE:
                    case PlaybackStateCompat.STATE_STOPPED:
                        mPlayStopBtn.setImageResource(R.drawable.action_play);
                        break;
                    case PlaybackStateCompat.STATE_BUFFERING:
                    case PlaybackStateCompat.STATE_PLAYING:
                        mPlayStopBtn.setImageResource(R.drawable.action_stop);
                        break;
                }
            }

            // IMPL onMetaDataChanged ot update the station title
    };

    // Helper methods ///////////////////////////////////////////////////////

    // show snackbar to the user to display important system events
    @Subscribe
    public void getMessageEvent(PlaybackServiceEvent event) {
        String message = event.getMessage();
        switch (message) {
            case PlaybackServiceEvent.ON_BUFFERING_COMPLETE:
            case PlaybackServiceEvent.ON_PLAYBACK_ERROR:
            case PlaybackServiceEvent.ON_PLAYBACK_COMPLETION:
            case PlaybackServiceEvent.ON_AUDIO_FOCUS_LOSS:
            case PlaybackServiceEvent.ON_BECOMING_NOISY:
                mProgressBar.setVisibility(View.GONE);
                displayMessage(message);
                break;

            case PlaybackServiceEvent.ON_STOP:
                // ??
                break;
        }
    }


    private void displayMessage(String message) {
        Utils.showSnackbar(mCoordinatorLayout, message);
    }


    // retrieve the stream url from the station object with a status of 1 or greater
    private String getStream(Station stn) {

        String url = null;
        ArrayList<Stream> streams = (ArrayList<Stream>) stn.getStreams();
        int status;
        for (Stream stream : streams) {
            status = stream.getStatus();
            if(status >= 0) {
                url = stream.getStream();
                if(url != null && !url.isEmpty())
                    break;
            }
        }
        return url;
    }


    private void updatePlayer() {
        // ensure index not out of bounds
        if(mQueuePosition >=0 && mQueuePosition < mQueue.size()) {

            mPrevBtn.setVisibility(View.VISIBLE);
            mNextBtn.setVisibility(View.VISIBLE);
            mStation = mQueue.get(mQueuePosition);
            mStationTitle.setText(mStation.getName());
            playFromStationUri();

            // update prev/next btns if req'd
            if(mQueuePosition == 0) {
                mPrevBtn.setVisibility(View.GONE);
            }
            if(mQueuePosition == mQueue.size() - 1) {
                mNextBtn.setVisibility(View.GONE);
            }
        }

    }


    private void playFromStationUri() {
        if(mStation != null) {

            String name = mStation.getName() != null? mStation.getName() : "";
            String slug = mStation.getSlug() != null? mStation.getSlug() : "";
            String country = mStation.getCountry() != null? mStation.getCountry() : "";
            String imageUrl = mStation.getImage().getUrl() != null? mStation.getImage().getUrl() : "";
            String thumbUrl = mStation.getImage().getThumb().getUrl() != null? mStation.getImage().getThumb().getUrl() : "";
            String url = getStream(mStation);

            if(url != null) {
                Timber.i("Url: %s, station: %s", url, mStation.getName());
                Uri uri = Uri.parse(url);
                Bundle extras = new Bundle();
                //extras.putParcelable(PlaybackService.EXTRA_STATION, mStation); // PlaybackService can't unmarshall the station object
                extras.putParcelable(PlaybackService.EXTRA_STATION_URI, uri);
                extras.putString(PlaybackService.EXTRA_STATION_NAME, name);
                extras.putString(PlaybackService.EXTRA_STATION_SLUG, slug);
                extras.putString(PlaybackService.EXTRA_STATION_COUNTRY, country);
                extras.putString(PlaybackService.EXTRA_STATION_IMAGE_URL, imageUrl);
                extras.putString(PlaybackService.EXTRA_STATION_THUMB_URL, thumbUrl);

                // playFromUri() works on emulators api 16-19, not on api 21+
                //mMediaController.getTransportControls().playFromUri(uri, extras);
                mMediaController.getTransportControls().playFromSearch("", extras);

                // show the progress bar while buffering the audio stream
                mProgressBar.setVisibility(View.VISIBLE);

            } else {
                displayMessage("No stream found, try a different station");
            }
        }
    }

}
