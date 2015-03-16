package de.ph1b.audiobook.service;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;

import com.squareup.picasso.Picasso;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import de.ph1b.audiobook.R;
import de.ph1b.audiobook.activity.BookPlayActivity;
import de.ph1b.audiobook.mediaplayer.MediaPlayerController;
import de.ph1b.audiobook.model.Book;
import de.ph1b.audiobook.model.Chapter;
import de.ph1b.audiobook.receiver.RemoteControlReceiver;
import de.ph1b.audiobook.utils.BaseApplication;
import de.ph1b.audiobook.utils.BaseApplication.PlayState;
import de.ph1b.audiobook.utils.L;
import de.ph1b.audiobook.utils.PrefsManager;

public class AudioService extends Service implements AudioManager.OnAudioFocusChangeListener, BaseApplication.OnPlayStateChangedListener, BaseApplication.OnCurrentBookChangedListener, BaseApplication.OnPositionChangedListener {

    private static final String TAG = AudioService.class.getSimpleName();
    private static final int NOTIFICATION_ID = 42;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ExecutorService mediaPlayerExecutor = Executors.newSingleThreadExecutor();
    private NotificationManager notificationManager;
    private PrefsManager prefs;
    private MediaPlayerController controller = null;
    private AudioManager audioManager;
    private final ReentrantLock controllerLock = new ReentrantLock();
    private BaseApplication baseApplication;
    @SuppressWarnings("deprecation")
    private RemoteControlClient remoteControlClient = null;
    private volatile boolean pauseBecauseLossTransient = false;
    private volatile boolean pauseBecauseHeadset = false;
    private final BroadcastReceiver audioBecomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (baseApplication.getPlayState() == PlayState.PLAYING) {
                pauseBecauseHeadset = true;
                controller.pause();
            }
        }
    };
    private final BroadcastReceiver headsetPlugReceiver = new BroadcastReceiver() {
        private static final int PLUGGED = 1;
        private static final int UNPLUGGED = 0;

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.equals(Intent.ACTION_HEADSET_PLUG)) {
                if (intent.getIntExtra("state", UNPLUGGED) == PLUGGED) {
                    if (pauseBecauseHeadset) {
                        if (prefs.resumeOnReplug()) {
                            controller.play();
                        }
                        pauseBecauseHeadset = false;
                    }
                }
            }
        }
    };

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new PrefsManager(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        baseApplication = (BaseApplication) getApplication();

        if (Build.VERSION.SDK_INT >= 14) {
            ComponentName eventReceiver = new ComponentName(AudioService.this.getPackageName(), RemoteControlReceiver.class.getName());
            //noinspection deprecation
            audioManager.registerMediaButtonEventReceiver(eventReceiver);
            Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mediaButtonIntent.setComponent(eventReceiver);
            PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, mediaButtonIntent, 0);
            //noinspection deprecation
            remoteControlClient = new RemoteControlClient(mediaPendingIntent);
            //noinspection deprecation
            remoteControlClient.setTransportControlFlags(
                    RemoteControlClient.FLAG_KEY_MEDIA_PAUSE |
                            RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE |
                            RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS |
                            RemoteControlClient.FLAG_KEY_MEDIA_NEXT |
                            RemoteControlClient.FLAG_KEY_MEDIA_REWIND |
                            RemoteControlClient.FLAG_KEY_MEDIA_FAST_FORWARD);
            //noinspection deprecation
            audioManager.registerRemoteControlClient(remoteControlClient);
        }
        registerReceiver(audioBecomingNoisyReceiver, new IntentFilter(
                AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        registerReceiver(headsetPlugReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        baseApplication.addOnPlayStateChangedListener(this);
        baseApplication.addOnCurrentBookChangedListener(this);
        baseApplication.addOnPositionChangedListener(this);

        Book book = baseApplication.getCurrentBook();
        if (book != null) {
            L.d(TAG, "onCreated initialized book=" + book);
            controller = new MediaPlayerController(baseApplication, book);
        }
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void onDestroy() {
        L.v(TAG, "onDestroy called");
        releaseController();

        baseApplication.removeOnPlayStateChangedListener(this);
        baseApplication.removeOnCurrentBookChangedListener(this);
        baseApplication.removeOnPositionChangedListener(this);

        try {
            unregisterReceiver(audioBecomingNoisyReceiver);
            unregisterReceiver(headsetPlugReceiver);
        } catch (IllegalArgumentException ignored) {
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void handleIntent(final Intent intent) {
        if (intent != null && intent.getAction() != null) {
            mediaPlayerExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    L.v(TAG, "handling intent action:" + intent.getAction());
                    switch (intent.getAction()) {
                        case Intent.ACTION_MEDIA_BUTTON:
                            int keyCode = intent.getIntExtra(Intent.EXTRA_KEY_EVENT, -1);
                            L.v(TAG, "handling keyCode: " + keyCode);
                            switch (keyCode) {
                                case KeyEvent.KEYCODE_MEDIA_PLAY:
                                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                                case KeyEvent.KEYCODE_HEADSETHOOK:
                                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                                    if (baseApplication.getPlayState() == PlayState.PLAYING) {
                                        controller.pause();
                                    } else {
                                        controller.play();
                                    }
                                    break;
                                case KeyEvent.KEYCODE_MEDIA_STOP:
                                    releaseController();
                                    break;
                                case KeyEvent.KEYCODE_MEDIA_NEXT:
                                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                                    controller.skip(MediaPlayerController.Direction.FORWARD);
                                    break;
                                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                                case KeyEvent.KEYCODE_MEDIA_REWIND:
                                    controller.skip(MediaPlayerController.Direction.BACKWARD);
                                    break;
                            }
                            break;
                        case ServiceController.CONTROL_SET_PLAYBACK_SPEED:
                            float speed = intent.getFloatExtra(ServiceController.CONTROL_SET_PLAYBACK_SPEED_EXTRA_SPEED, 1);
                            controller.setPlaybackSpeed(speed);
                            break;
                        case ServiceController.CONTROL_TOGGLE_SLEEP_SAND:
                            controller.toggleSleepSand();
                            break;
                        case ServiceController.CONTROL_CHANGE_TIME:
                            int time1 = intent.getIntExtra(ServiceController.CONTROL_CHANGE_TIME_EXTRA_TIME, 0);
                            String relativePath = intent.getStringExtra(ServiceController.CONTROL_CHANGE_TIME_EXTRA_PATH_RELATIVE);
                            controller.changePosition(time1, relativePath);
                            break;
                        case ServiceController.CONTROL_NEXT:
                            controller.next();
                            break;
                        case ServiceController.CONTROL_PREVIOUS:
                            controller.previous();
                            break;
                    }
                }
            });
        }
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {

        if (controller == null && baseApplication.getCurrentBook() != null) {
            controller = new MediaPlayerController(baseApplication, baseApplication.getCurrentBook());
        }

        if (controller != null) {
            handleIntent(intent);
        }
        return Service.START_STICKY;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private Notification getNotification() {
        Notification.Builder notificationBuilder = new Notification.Builder(this);
        Book book = controller.getBook();
        Chapter chapter = book.getCurrentChapter();

        // content click
        Intent bookPlayIntent = new Intent(AudioService.this, BookPlayActivity.class);
        PendingIntent contentIntent = android.support.v4.app.TaskStackBuilder.create(AudioService.this)
                .addNextIntentWithParentStack(bookPlayIntent)
                .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);

        // cover
        File coverFile = new File(book.getCover());
        if (coverFile.exists()) {
            try {
                int width = getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width);
                int height = getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height);
                Bitmap cover = Picasso.with(AudioService.this).load(new File(book.getCover()))
                        .resize(width, height).get();
                if (cover != null) {
                    notificationBuilder.setLargeIcon(cover);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // stop intent
        Intent stopIntent = ServiceController.getStopIntent(this);
        PendingIntent stopPI = PendingIntent.getService(this, KeyEvent.KEYCODE_MEDIA_STOP, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        notificationBuilder
                .setContentIntent(contentIntent)
                .setContentTitle(book.getName())
                .setSmallIcon(R.drawable.ic_notification)
                .setWhen(0)
                .setDeleteIntent(stopPI)
                .setAutoCancel(true);

        // we need the current chapter title only if there is more than one chapter.
        if (book.getChapters().size() > 1) {
            notificationBuilder.setContentText(chapter.getName());
        }

        if (Build.VERSION.SDK_INT >= 16) {
            notificationBuilder.setPriority(NotificationCompat.PRIORITY_HIGH);

            // rewind
            Intent rewindIntent = ServiceController.getRewindIntent(this);
            PendingIntent rewindPI = PendingIntent.getService(getApplicationContext(), KeyEvent.KEYCODE_MEDIA_REWIND, rewindIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            notificationBuilder.addAction(R.drawable.ic_fast_rewind_white_36dp, getString(R.string.rewind), rewindPI);

            // play/pause
            Intent playPauseIntent = ServiceController.getPlayPauseIntent(this);
            PendingIntent playPausePI = PendingIntent.getService(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            if (baseApplication.getPlayState() == PlayState.PLAYING) {
                notificationBuilder.addAction(R.drawable.ic_pause_white_36dp, getString(R.string.pause), playPausePI);
            } else {
                notificationBuilder.addAction(R.drawable.ic_play_arrow_white_36dp, getString(R.string.play), playPausePI);
            }

            // have stop action only on api < 21 because from then on, media style is dismissible
            if (Build.VERSION.SDK_INT < 21) {
                notificationBuilder.addAction(R.drawable.ic_close_white_36dp, getString(R.string.stop), stopPI);
            }
        }

        if (Build.VERSION.SDK_INT >= 17) {
            notificationBuilder.setShowWhen(false);
        }

        if (Build.VERSION.SDK_INT >= 21) {
            notificationBuilder
                    .setStyle(new Notification.MediaStyle()
                            .setShowActionsInCompactView(0, 1))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

            // fast forward. Since we don't need a stop button, we have space for a fast forward button on api >= 21
            Intent fastForwardIntent = ServiceController.getFastForwardIntent(this);
            PendingIntent fastForwardPI = PendingIntent.getService(getApplicationContext(), KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, fastForwardIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            notificationBuilder.addAction(R.drawable.ic_fast_forward_white_36dp, getString(R.string.fast_forward), fastForwardPI);
        }

        //noinspection deprecation
        return notificationBuilder.getNotification();
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void updateRemoteControlClient() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Book book = controller.getBook();
                Bitmap bitmap = null;
                try {
                    bitmap = Picasso.with(AudioService.this).load(new File(book.getCover())).get();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                @SuppressWarnings("deprecation") RemoteControlClient.MetadataEditor editor = remoteControlClient.editMetadata(true);
                Chapter c = book.getCurrentChapter();
                editor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, c.getName());
                editor.putString(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, book.getName());

                if (bitmap != null) {
                    //noinspection deprecation
                    editor.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, bitmap.copy(bitmap.getConfig(), true));
                }
                editor.apply();
            }
        });
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        final int callState = (tm != null) ? tm.getCallState() : TelephonyManager.CALL_STATE_IDLE;
        L.d(TAG, "Call state is: " + callState);
        if (callState != TelephonyManager.CALL_STATE_IDLE) {
            focusChange = AudioManager.AUDIOFOCUS_LOSS;
            // if there is an incoming call, we pause permanently. (tricking switch condition)
        }
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                L.d(TAG, "started by audioFocus gained");
                if (pauseBecauseLossTransient) {
                    controller.play();
                    pauseBecauseLossTransient = false;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                L.d(TAG, "paused by audioFocus loss");
                releaseController();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (baseApplication.getPlayState() == PlayState.PLAYING) {
                    L.d(TAG, "Paused by audio-focus loss transient.");
                    controller.pause();
                    pauseBecauseLossTransient = true;
                }
                break;
        }
    }

    @Override
    public void onPlayStateChanged(final PlayState state) {
        L.d(TAG, "onPlayStateChanged:" + state);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                L.d(TAG, "onPlayStateChanged executed:" + state);
                switch (state) {
                    case PLAYING:
                        if (controller != null) {
                            audioManager.requestAudioFocus(AudioService.this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                            startForeground(NOTIFICATION_ID, getNotification());
                            if (Build.VERSION.SDK_INT >= 14) {
                                //noinspection deprecation
                                remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
                                updateRemoteControlClient();
                            }
                        }
                        break;
                    case PAUSED:
                        if (controller != null) {
                            stopForeground(false);
                            notificationManager.notify(NOTIFICATION_ID, getNotification());
                            if (Build.VERSION.SDK_INT >= 14) {
                                //noinspection deprecation
                                remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
                            }
                        }
                        break;
                    case STOPPED:
                        audioManager.abandonAudioFocus(AudioService.this);
                        notificationManager.cancel(NOTIFICATION_ID);
                        stopForeground(true);
                        if (Build.VERSION.SDK_INT >= 14) {
                            //noinspection deprecation
                            remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
                        }
                        break;
                }
            }
        });
    }

    private void releaseController() {
        if (controller != null) {
            controller.release();
            pauseBecauseHeadset = false;
            pauseBecauseLossTransient = false;
            controller = null;
            baseApplication.setPlayState(PlayState.STOPPED);
        }
    }

    @Override
    public void onCurrentBookChanged(Book book) {
        releaseController();
        if (book != null) {
            L.d(TAG, "init new book=" + book);
            controller = new MediaPlayerController(baseApplication, book);
        }
    }

    @Override
    public void onPositionChanged() {
        if (Build.VERSION.SDK_INT >= 14) {
            updateRemoteControlClient();
        }
    }
}
