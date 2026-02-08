package app.botdrop;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.shared.logger.Logger;

/**
 * Step 3 of setup: Channel setup (simplified)
 * 
 * Flow:
 * 1. User selects platform (Telegram/Discord/Feishu)
 * 2. User opens setup bot/docs to create bot and get credentials
 * 3. User enters credentials in app
 * 4. App configures and starts gateway
 */
public class ChannelFragment extends Fragment {

    private static final String LOG_TAG = "ChannelFragment";
    private static final String SETUP_BOT_URL = "https://t.me/BotDropSetupBot";
    private static final String FEISHU_DOCS_URL = "https://open.feishu.cn/document/develop-an-echo-bot/introduction";

    // Platform types
    public enum Platform {
        TELEGRAM, DISCORD, FEISHU
    }

    private RadioGroup mPlatformGroup;
    private RadioButton mTelegramRadio;
    private RadioButton mDiscordRadio;
    private RadioButton mFeishuRadio;
    private Button mOpenSetupBotButton;
    private TextView mTokenLabel;
    private EditText mTokenInput;
    private TextView mUserIdLabel;
    private EditText mUserIdInput;
    private Button mConnectButton;
    private TextView mErrorMessage;
    
    private Platform mSelectedPlatform = Platform.TELEGRAM;

    private BotDropService mService;
    private boolean mBound = false;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BotDropService.LocalBinder binder = (BotDropService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            Logger.logDebug(LOG_TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            Logger.logDebug(LOG_TAG, "Service disconnected");
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_botdrop_channel, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find views
        mPlatformGroup = view.findViewById(R.id.channel_platform_group);
        mTelegramRadio = view.findViewById(R.id.platform_telegram);
        mDiscordRadio = view.findViewById(R.id.platform_discord);
        mFeishuRadio = view.findViewById(R.id.platform_feishu);
        mOpenSetupBotButton = view.findViewById(R.id.channel_open_setup_bot);
        mTokenLabel = view.findViewById(R.id.channel_token_label);
        mTokenInput = view.findViewById(R.id.channel_token_input);
        mUserIdLabel = view.findViewById(R.id.channel_userid_label);
        mUserIdInput = view.findViewById(R.id.channel_userid_input);
        mConnectButton = view.findViewById(R.id.channel_connect_button);
        mErrorMessage = view.findViewById(R.id.channel_error_message);

        // Setup platform selection
        mPlatformGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.platform_telegram) {
                mSelectedPlatform = Platform.TELEGRAM;
            } else if (checkedId == R.id.platform_discord) {
                mSelectedPlatform = Platform.DISCORD;
            } else if (checkedId == R.id.platform_feishu) {
                mSelectedPlatform = Platform.FEISHU;
            }
            updateUIForPlatform();
        });

        // Setup click handlers
        mOpenSetupBotButton.setOnClickListener(v -> openSetupDocs());
        mConnectButton.setOnClickListener(v -> connect());

        Logger.logDebug(LOG_TAG, "ChannelFragment view created");
    }

    @Override
    public void onStart() {
        super.onStart();
        // Bind to service (matching InstallFragment lifecycle pattern)
        Intent intent = new Intent(requireActivity(), BotDropService.class);
        requireActivity().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mBound) {
            try {
                requireActivity().unbindService(mConnection);
            } catch (IllegalArgumentException e) {
                Logger.logDebug(LOG_TAG, "Service was already unbound");
            }
            mBound = false;
        }
    }

    private void openSetupDocs() {
        String url;
        switch (mSelectedPlatform) {
            case FEISHU:
                url = FEISHU_DOCS_URL;
                break;
            case TELEGRAM:
            default:
                url = SETUP_BOT_URL;
                break;
        }
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(browserIntent);
    }

    private void updateUIForPlatform() {
        switch (mSelectedPlatform) {
            case TELEGRAM:
                mOpenSetupBotButton.setText("Open @BotDropSetupBot");
                mTokenLabel.setText("Bot Token (from @BotFather)");
                mTokenInput.setHint("123456789:ABCdefGHI...");
                mUserIdLabel.setText("Your User ID (from @BotDropSetupBot)");
                mUserIdInput.setHint("123456789");
                mUserIdInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                break;
            case DISCORD:
                mOpenSetupBotButton.setText("View Discord Bot Setup Guide");
                mTokenLabel.setText("Bot Token");
                mTokenInput.setHint("YOUR_DISCORD_BOT_TOKEN");
                mUserIdLabel.setText("Your Discord User ID");
                mUserIdInput.setHint("123456789012345678");
                mUserIdInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                break;
            case FEISHU:
                mOpenSetupBotButton.setText("View Feishu App Setup Guide");
                mTokenLabel.setText("App ID");
                mTokenInput.setHint("cli_xxxxxxxxxxxxxxxx");
                mUserIdLabel.setText("App Secret");
                mUserIdInput.setHint("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
                mUserIdInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                break;
        }
        // Clear inputs when switching platforms
        mTokenInput.setText("");
        mUserIdInput.setText("");
        mErrorMessage.setVisibility(View.GONE);
    }

    private void connect() {
        // Clear previous error
        mErrorMessage.setVisibility(View.GONE);

        String token = mTokenInput.getText().toString().trim();
        String userId = mUserIdInput.getText().toString().trim();

        // Validate inputs
        if (TextUtils.isEmpty(token)) {
            showError(getTokenErrorMessage());
            return;
        }

        if (TextUtils.isEmpty(userId)) {
            showError(getUserIdErrorMessage());
            return;
        }

        // Platform-specific validation
        if (!validateCredentials(token, userId)) {
            return;
        }

        // Disable button during processing
        mConnectButton.setEnabled(false);
        mConnectButton.setText("Connecting...");

        // Write channel config based on platform
        String platformName;
        switch (mSelectedPlatform) {
            case DISCORD:
                platformName = "discord";
                break;
            case FEISHU:
                platformName = "feishu";
                break;
            case TELEGRAM:
            default:
                platformName = "telegram";
                break;
        }
        boolean success = ChannelSetupHelper.writeChannelConfig(platformName, token, userId);
        if (!success) {
            showError("Failed to write configuration");
            resetButton();
            return;
        }

        // Start gateway
        startGateway();
    }

    private void startGateway() {
        if (!mBound || mService == null) {
            showError("Service not ready, please try again");
            resetButton();
            return;
        }

        Logger.logInfo(LOG_TAG, "Starting gateway...");

        mService.startGateway(result -> {
            // Check if fragment is still attached
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                return;
            }
            
            requireActivity().runOnUiThread(() -> {
                // Double-check in UI thread
                if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                    return;
                }
                
                if (result.success) {
                    Logger.logInfo(LOG_TAG, "Gateway started successfully");
                    Toast.makeText(requireContext(), "Connected! Gateway is starting...", Toast.LENGTH_LONG).show();

                    // Setup complete, advance to next step
                    SetupActivity activity = (SetupActivity) getActivity();
                    if (activity != null && !activity.isFinishing()) {
                        activity.goToNextStep();
                    }
                } else {
                    Logger.logError(LOG_TAG, "Failed to start gateway: " + result.stderr);
                    
                    String errorMsg = result.stderr;
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = result.stdout;
                    }
                    if (TextUtils.isEmpty(errorMsg)) {
                        errorMsg = "Unknown error (exit code: " + result.exitCode + ")";
                    }
                    
                    showError("Failed to start gateway: " + errorMsg);
                    resetButton();
                }
            });
        });
    }

    private void showError(String message) {
        mErrorMessage.setText(message);
        mErrorMessage.setVisibility(View.VISIBLE);
    }

    private void resetButton() {
        mConnectButton.setEnabled(true);
        mConnectButton.setText("Connect & Start");
    }

    private String getTokenErrorMessage() {
        switch (mSelectedPlatform) {
            case FEISHU:
                return "Please enter your App ID";
            case DISCORD:
                return "Please enter your Bot Token";
            case TELEGRAM:
            default:
                return "Please enter your Bot Token";
        }
    }

    private String getUserIdErrorMessage() {
        switch (mSelectedPlatform) {
            case FEISHU:
                return "Please enter your App Secret";
            case DISCORD:
                return "Please enter your Discord User ID";
            case TELEGRAM:
            default:
                return "Please enter your User ID";
        }
    }

    private boolean validateCredentials(String token, String userId) {
        switch (mSelectedPlatform) {
            case TELEGRAM:
                // Telegram bot tokens: "123456789:ABC-DEF..."
                if (!token.matches("^\\d+:[A-Za-z0-9_-]+$")) {
                    showError("Invalid bot token format (should be like: 123456:ABC...)");
                    return false;
                }
                // Telegram User ID: numeric
                if (!userId.matches("^\\d+$")) {
                    showError("Invalid User ID format (should be numeric)");
                    return false;
                }
                break;
            case DISCORD:
                // Discord tokens are long alphanumeric strings
                if (token.length() < 20) {
                    showError("Invalid Discord bot token (too short)");
                    return false;
                }
                // Discord User ID: numeric (snowflake)
                if (!userId.matches("^\\d+$")) {
                    showError("Invalid Discord User ID format (should be numeric)");
                    return false;
                }
                break;
            case FEISHU:
                // Feishu App ID: starts with "cli_" followed by 16 chars
                if (!token.matches("^cli_[a-z0-9]{16}$")) {
                    showError("Invalid App ID format (should be like: cli_xxxxxxxxxxxxxxxx)");
                    return false;
                }
                // Feishu App Secret: 32 character hex string
                if (!userId.matches("^[a-zA-Z0-9]{32}$")) {
                    showError("Invalid App Secret format (should be 32 alphanumeric characters)");
                    return false;
                }
                break;
        }
        return true;
    }
}
