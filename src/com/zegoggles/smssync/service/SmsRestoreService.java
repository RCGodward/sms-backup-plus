package com.zegoggles.smssync.service;

import android.content.Intent;
import android.util.Log;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.internet.BinaryTempFileBody;
import com.squareup.otto.Produce;
import com.squareup.otto.Subscribe;
import com.zegoggles.smssync.App;
import com.zegoggles.smssync.mail.CursorToMessage;
import com.zegoggles.smssync.preferences.PrefStore;
import com.zegoggles.smssync.service.state.RestoreStateChanged;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FilenameFilter;

import static com.zegoggles.smssync.App.LOCAL_LOGV;
import static com.zegoggles.smssync.App.TAG;
import static com.zegoggles.smssync.service.state.SmsSyncState.ERROR;
import static com.zegoggles.smssync.service.state.SmsSyncState.INITIAL;

public class SmsRestoreService extends ServiceBase {
    @NotNull private RestoreStateChanged mState = new RestoreStateChanged(INITIAL, 0, 0, 0, 0, null);
    @Nullable private static SmsRestoreService service;

    @Override @NotNull
    public RestoreStateChanged getState() {
        return mState;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        asyncClearCache();
        BinaryTempFileBody.setTempDirectory(getCacheDir());
        service = this;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (LOCAL_LOGV) Log.v(TAG, "SmsRestoreService#onDestroy(state"+getState()+")");
        service = null;
    }

    @Override
    protected void handleIntent(final Intent intent) {
        if (isWorking()) return;
        try {
            final boolean starredOnly = PrefStore.isRestoreStarredOnly(service);
            final boolean restoreCallLog = PrefStore.isRestoreCallLog(service);
            final boolean restoreSms = PrefStore.isRestoreSms(service);

            CursorToMessage converter = new CursorToMessage(service, PrefStore.getUserEmail(service));

            new RestoreTask(this,
                    getBackupImapStore(),
                    converter,
                    restoreSms, restoreCallLog, starredOnly).execute(
                    PrefStore.getMaxItemsPerRestore(this));
        } catch (MessagingException e) {
            App.bus.post(mState.transition(ERROR, e));
        }
    }

    private void asyncClearCache() {
        new Thread("clearCache") {
            @Override
            public void run() {
                clearCache();
            }
        }.start();
    }

    public synchronized void clearCache() {
        File tmp = getCacheDir();
        if (tmp == null) return; // not sure why this would return null

        Log.d(TAG, "clearing cache in " + tmp);
        for (File f : tmp.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("body");
            }
        })) {
            if (LOCAL_LOGV) Log.v(TAG, "deleting " + f);
            if (!f.delete()) Log.w(TAG, "error deleting " + f);
        }
    }

    @Subscribe public void restoreStateChanged(final RestoreStateChanged newState) {
        mState = newState;
        if (mState.isInitialState()) return;

        if (!mState.isRunning()) {
            Log.d(TAG, "stopping service, state"+mState);
            stopSelf();
        }
    }

    @Produce public RestoreStateChanged produceLastState() {
        return mState;
    }

    public static boolean isServiceWorking() {
        return service != null && service.isWorking();
    }
}