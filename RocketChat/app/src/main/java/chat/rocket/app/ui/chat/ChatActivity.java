package chat.rocket.app.ui.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ListView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import chat.rocket.app.R;
import chat.rocket.app.db.collections.StreamNotifyRoom;
import chat.rocket.app.db.dao.MessageDAO;
import chat.rocket.app.ui.adapters.MessagesAdapter;
import chat.rocket.app.ui.base.AudioRecordActivity;
import chat.rocket.app.ui.base.BaseActivity;
import chat.rocket.app.ui.home.menu.FileListFragment;
import chat.rocket.app.ui.home.menu.MembersListFragment;
import chat.rocket.app.ui.home.menu.PinnedMessagesFragment;
import chat.rocket.app.ui.home.menu.RoomSettingsFragment;
import chat.rocket.app.ui.home.menu.SearchFragment;
import chat.rocket.app.ui.home.menu.StaredMessagesFragment;
import chat.rocket.app.ui.widgets.FabMenuLayout;
import chat.rocket.models.Message;
import chat.rocket.models.Messages;
import chat.rocket.models.NotifyRoom;
import chat.rocket.models.RCSubscription;
import chat.rocket.operations.Subscription;
import chat.rocket.operations.meteor.SubscribeListener;
import chat.rocket.operations.methods.listeners.FileUploadListener;
import chat.rocket.operations.methods.listeners.LoadHistoryListener;
import chat.rocket.operations.methods.listeners.LogSubscribeListener;
import chat.rocket.operations.methods.listeners.ReadMessagesListener;
import chat.rocket.operations.methods.listeners.SendMessageListener;
import io.fabric.sdk.android.services.network.HttpRequest;

/**
 * Created by julio on 29/11/15.
 */
public class ChatActivity extends BaseActivity implements FabMenuLayout.MenuClickListener, LoaderManager.LoaderCallbacks<Cursor> {
    public static final String RC_SUB = "sub";
    private static final int LOADER_ID = 3;
    private static final int RECORD_AUDIO_REQUEST_CODE = 123;
    private RCSubscription mRcSubscription;
    private FabMenuLayout mFabMenu;
    private LoadHistoryListener mLoadHistoryListener = new LoadHistoryListener() {
        @Override
        public void onResult(Messages result) {
            mRocketMethods.readMessages(mRcSubscription.getRid(), mReadMessagesListener);
            for (Message m : result.getMessages()) {
                MessageDAO msg = new MessageDAO(m);
                msg.insert();
            }
        }

        @Override
        public void onError(String error, String reason, String details) {

        }
    };

    private Subscription mRoomSubscription;
    private SubscribeListener mRoomListener = new LogSubscribeListener();

    private ReadMessagesListener mReadMessagesListener = new ReadMessagesListener() {
        @Override
        public void onResult(Integer result) {

        }

        @Override
        public void onError(String error, String reason, String details) {

        }
    };
    private ListView mListView;
    private MessagesAdapter mAdapter;
    private SendMessageListener mSendMessageListener = new SendMessageListener() {
        @Override
        public void onResult(Message result) {
            mSendEditText.getText().clear();
            mListView.smoothScrollToPosition(mAdapter.getCount() - 1);
        }

        @Override
        public void onError(String error, String reason, String details) {
        }
    };
    private EditText mSendEditText;
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            NotifyRoom notif = (NotifyRoom) intent.getSerializableExtra(StreamNotifyRoom.COLLECTION_NAME);
            if (mRcSubscription.getRid().equals(notif.getRid())) {
                if (notif.isHappening()) {
                    getSupportActionBar().setSubtitle(notif.getUsername() + " is " + notif.getAction());
                } else {
                    getSupportActionBar().setSubtitle("");
                }
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(StreamNotifyRoom.COLLECTION_NAME + mRcSubscription.getRid());
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        mRcSubscription = (RCSubscription) getIntent().getSerializableExtra(RC_SUB);
        mFabMenu = (FabMenuLayout) findViewById(R.id.FabMenu);
        mListView = (ListView) findViewById(R.id.listview);
        mSendEditText = (EditText) findViewById(R.id.submitEditText);
        mFabMenu.setTopView(mListView);
        mFabMenu.setContentView(mListView);
        mFabMenu.setMenuClickListener(this);
        mSendEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                mRocketMethods.sendMessage(mRcSubscription.getRid(), mSendEditText.getText().toString(), mSendMessageListener);
                return true;
            }
            return false;
        });

        mAdapter = new MessagesAdapter(this);
        mListView.setAdapter(mAdapter);
        int unread = mRcSubscription.getUnread();
        if (unread == 0) {
            unread = 25;
        }
        mRocketMethods.loadHistory(mRcSubscription.getRid(), null, unread, mRcSubscription.getLs(), mLoadHistoryListener);
        mRoomSubscription = mRocketSubscriptions.room(mRcSubscription.getName(), mRcSubscription.getType(), mRoomListener);

        getSupportLoaderManager().initLoader(LOADER_ID, null, this);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(mRcSubscription.getFormattedName());

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RECORD_AUDIO_REQUEST_CODE && resultCode == RESULT_OK) {
            String filePath = data.getStringExtra(AudioRecordActivity.FILE_PATH);
            File file = new File(filePath);
            if (file.exists()) {
                String str = decodeFile(file);
                if (str != null) {
                    String[] parts = str.split("(?<=\\G.{4})");
                    processUpload(file.getName(), file.length(), parts);
                }
            }
        }
    }

    private void processUpload(String name, long size, String[] parts) {
        mRocketMethods.uploadFile(mMeteor.getUserId(), mRcSubscription.getRid(), name, parts, "audio/3gp", "3gp", size, new FileUploadListener() {
            @Override
            public void onProgress(float progress) {
                Log.d("upload - onProgress", progress + "%");
            }

            @Override
            public void onSuccess(String result) {
                Log.d("upload - onSuccess", result);
            }

            @Override
            public void onError(String error, String reason, String details) {
                Log.d("upload - onError", error + ", " + reason + ", " + details);
            }
        });
    }

    private String decodeFile(File file) {
        String fileStr = null;
        FileInputStream fis = null;
        ByteArrayOutputStream baos = null;
        try {
            baos = new ByteArrayOutputStream();
            fis = new FileInputStream(file);

            byte[] buf = new byte[1024];
            int n;
            while (-1 != (n = fis.read(buf))) {
                baos.write(buf, 0, n);
            }
            fileStr = HttpRequest.Base64.encodeBytes(baos.toByteArray());

        } catch (Exception e) {
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                    if (baos != null) {
                        baos.close();
                    }
                } catch (IOException e) {
                }
            }
        }
        return fileStr;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRoomSubscription != null) {
            mRoomSubscription.unSubscribe();
        }
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return MessageDAO.getLoader(mRcSubscription.getRid());
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onBackPressed() {
        if (!mFabMenu.onBackPressed()) {
            super.onBackPressed();
        }
    }

    @Override
    public void onMenuItemClick(int id) {
        switch (id) {
            case R.id.SettingsButton:
                getSupportFragmentManager().beginTransaction().replace(R.id.MenuContentLayout, new RoomSettingsFragment()).commit();
                break;
            case R.id.SearchButton:
                getSupportFragmentManager().beginTransaction().replace(R.id.MenuContentLayout, new SearchFragment()).commit();
                break;
            case R.id.MembersButton:
                getSupportFragmentManager().beginTransaction().replace(R.id.MenuContentLayout, new MembersListFragment()).commit();
                break;
            case R.id.FilesButton:
                getSupportFragmentManager().beginTransaction().replace(R.id.MenuContentLayout, new FileListFragment()).commit();
                break;
            case R.id.StaredButton:
                getSupportFragmentManager().beginTransaction().replace(R.id.MenuContentLayout, new StaredMessagesFragment()).commit();
                break;
            case R.id.PinnedButton:
                getSupportFragmentManager().beginTransaction().replace(R.id.MenuContentLayout, new PinnedMessagesFragment()).commit();
                break;
            case R.id.MicButton:
                //TODO: migrate code to fragment and insert below the submitEditText..
                Intent intent = new Intent(ChatActivity.this, AudioRecordActivity.class);
                startActivityForResult(intent, RECORD_AUDIO_REQUEST_CODE);
                break;

        }
    }

}