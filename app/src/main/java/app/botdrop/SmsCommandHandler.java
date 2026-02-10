package app.botdrop;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Handles SMS-related commands from OpenClaw via file-based IPC.
 * <p>
 * Communication protocol:
 * - OpenClaw writes request to: ~/.openclaw/sms/sms_request.json
 *   Format: {"action": "get_latest" | "get_recent" | "get_unread" | "search", "limit": number, "keyword": "search_term", "timestamp": "ms_since_epoch"}
 * - Android reads request, processes it, writes response to: ~/.openclaw/sms/sms_response_{timestamp}.json
 * - OpenClaw polls for response file (with matching timestamp) and reads it
 * <p>
 * Supported actions:
 * - get_latest: Returns the most recent SMS message
 * - get_recent: Returns recent SMS messages (limit parameter required)
 * - get_unread: Returns all unread SMS messages
 * - search: Search SMS messages by keyword (keyword parameter required)
 */
public class SmsCommandHandler {

    private static final String LOG_TAG = "SmsCommandHandler";
    private static final String SMS_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/sms";
    private static final String REQUEST_FILE = SMS_DIR + "/sms_request.json";
    private static final String RESPONSE_PREFIX = "sms_response_";
    private static final String RESPONSE_SUFFIX = ".json";

    private static final long POLL_INTERVAL_MS = 1000; // Check every second
    private static final long RESPONSE_TIMEOUT_MS = 30000; // 30 second timeout for responses

    private final Context mContext;
    private final Handler mHandler;
    private final ExecutorService mExecutor;
    private boolean mIsRunning = false;
    private File mLastProcessedRequest = null;
    private long mLastProcessedTime = 0;

    public SmsCommandHandler(Context context) {
        mContext = context.getApplicationContext();
        mHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Start monitoring for SMS command requests
     */
    public void start() {
        if (mIsRunning) return;
        mIsRunning = true;
        Logger.logInfo(LOG_TAG, "Starting SMS command handler");
        schedulePoll();
    }

    /**
     * Stop monitoring for SMS command requests
     */
    public void stop() {
        mIsRunning = false;
        mHandler.removeCallbacksAndMessages(null);
        Logger.logInfo(LOG_TAG, "Stopped SMS command handler");
    }

    /**
     * Shutdown the handler and cleanup resources
     */
    public void shutdown() {
        stop();
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
        }
    }

    private void schedulePoll() {
        if (!mIsRunning) return;

        mExecutor.execute(() -> {
            try {
                checkAndProcessRequest();
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Error processing SMS request: " + e.getMessage());
            }

            // Schedule next poll
            mHandler.postDelayed(this::schedulePoll, POLL_INTERVAL_MS);
        });
    }

    private void checkAndProcessRequest() {
        File requestFile = new File(REQUEST_FILE);

        if (!requestFile.exists()) {
            return;
        }

        // Check if this is a new request (different file or modified time)
        long lastModified = requestFile.lastModified();
        if (requestFile.equals(mLastProcessedRequest) && lastModified <= mLastProcessedTime) {
            return; // Already processed this request
        }

        Logger.logInfo(LOG_TAG, "New SMS request detected");

        String timestamp = null;
        try {
            // Read request
            String requestJson = readFile(requestFile);
            if (requestJson != null && !requestJson.isEmpty()) {
                Logger.logInfo(LOG_TAG, "requestJson - " + requestJson);
            }
            if (requestJson == null || requestJson.isEmpty()) {
                return;
            }

            JSONObject request = new JSONObject(requestJson);
            String action = request.optString("action", "");
            int limit = request.optInt("limit", 10);
            String keyword = request.optString("keyword", "");
            timestamp = request.optString("timestamp", "");

            Logger.logInfo(LOG_TAG, "Processing SMS action: " + action + ", keyword: " + keyword + ", timestamp: " + timestamp);

            // Process based on action
            String responseJson;
            switch (action) {
                case "get_latest":
                    responseJson = SmsReader.getLatestSmsJson(mContext);
                    break;
                case "get_recent":
                    responseJson = SmsReader.getRecentSmsJson(mContext, limit, keyword);
                    break;
                case "search":
                    if (keyword == null || keyword.isEmpty()) {
                        JSONObject error = new JSONObject();
                        error.put("error", "Missing required 'keyword' parameter for search action");
                        responseJson = error.toString();
                    } else {
                        // Search scans more messages but applies keyword filter
                        responseJson = SmsReader.getRecentSmsJson(mContext, Math.max(limit, 100), keyword);
                    }
                    break;
                case "get_unread":
                    responseJson = SmsReader.getUnreadSmsJson(mContext);
                    break;
                case "check_permission":
                    responseJson = checkPermissionJson();
                    break;
                default:
                    JSONObject error = new JSONObject();
                    error.put("error", "Unknown action: " + action);
                    error.put("supported_actions", new String[]{"get_latest", "get_recent", "get_unread", "check_permission", "search"});
                    responseJson = error.toString();
            }

            // Write response with timestamp in filename
            writeResponse(responseJson, timestamp);

            // Mark as processed
            mLastProcessedRequest = requestFile;
            mLastProcessedTime = lastModified;

            // Optionally delete request file to prevent reprocessing
            // requestFile.delete();

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to process SMS request: " + e.getMessage());
            try {
                JSONObject error = new JSONObject();
                error.put("error", "Failed to process request: " + e.getMessage());
                writeResponse(error.toString(), timestamp);
            } catch (Exception ignored) {
            }
        }
    }

    private String checkPermissionJson() throws Exception {
        JSONObject result = new JSONObject();
        boolean hasPermission = SmsReader.hasSmsPermission(mContext);
        result.put("has_permission", hasPermission);
        result.put("permission", "android.permission.READ_SMS");
        if (!hasPermission) {
            result.put("message", "SMS permission not granted. Please grant SMS permission in app settings.");
        }
        return result.toString();
    }

    private void writeResponse(String json, String timestamp) throws Exception {
        // Generate filename with timestamp if provided, otherwise use current time
        String filename;
        if (timestamp != null && !timestamp.isEmpty()) {
            filename = RESPONSE_PREFIX + timestamp + RESPONSE_SUFFIX;
        } else {
            filename = RESPONSE_PREFIX + System.currentTimeMillis() + RESPONSE_SUFFIX;
        }
        
        File responseFile = new File(SMS_DIR, filename);

        File parentDir = responseFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (FileWriter writer = new FileWriter(responseFile)) {
            writer.write(json);
        }

        // Set file permissions to be readable by termux user
        responseFile.setReadable(true, false);

        Logger.logInfo(LOG_TAG, "SMS response written to " + responseFile.getAbsolutePath());
    }

    private String readFile(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        try (Scanner scanner = new Scanner(file)) {
            while (scanner.hasNextLine()) {
                content.append(scanner.nextLine());
            }
        }
        return content.toString();
    }

    /**
     * Static helper to create a request file from OpenClaw side
     * This can be called from OpenClaw through a shell command
     */
    public static boolean createRequest(String action, int limit) {
        try {
            File requestFile = new File(REQUEST_FILE);
            File parentDir = requestFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // Generate timestamp
            String timestamp = String.valueOf(System.currentTimeMillis());

            JSONObject request = new JSONObject();
            request.put("action", action);
            request.put("timestamp", timestamp);
            if (limit > 0) {
                request.put("limit", limit);
            }

            try (FileWriter writer = new FileWriter(requestFile)) {
                writer.write(request.toString());
            }

            requestFile.setReadable(true, false);
            return true;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create request: " + e.getMessage());
            return false;
        }
    }

    /**
     * Static helper to read response from OpenClaw side.
     * Reads the response file matching the given timestamp and deletes it after reading.
     * 
     * @param timestamp The timestamp from the request to match the response file
     */
    public static String readResponse(String timestamp) {
        try {
            if (timestamp == null || timestamp.isEmpty()) {
                return null;
            }
            
            String filename = RESPONSE_PREFIX + timestamp + RESPONSE_SUFFIX;
            File responseFile = new File(SMS_DIR, filename);
            
            if (!responseFile.exists()) {
                return null;
            }

            StringBuilder content = new StringBuilder();
            try (Scanner scanner = new Scanner(responseFile)) {
                while (scanner.hasNextLine()) {
                    content.append(scanner.nextLine());
                }
            }

//            // Delete response file after reading
//            responseFile.delete();
            Logger.logError(LOG_TAG, "response: " + content.toString());

            return content.toString();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read response: " + e.getMessage());
            return null;
        }
    }

    /**
     * Wait for and read response with timeout
     * 
     * @param timeoutMs Timeout in milliseconds
     * @param timestamp The timestamp from the request to match the response file
     */
    public static String waitForResponse(long timeoutMs, String timestamp) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            String response = readResponse(timestamp);
            if (response != null) {
                return response;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
        return null;
    }

    /**
     * Legacy method for backward compatibility - reads most recent response file
     * @deprecated Use {@link #readResponse(String)} instead
     */
    @Deprecated
    public static String readResponse() {
        return null;
    }

    /**
     * Legacy method for backward compatibility - waits for any response
     * @deprecated Use {@link #waitForResponse(long, String)} instead
     */
    @Deprecated
    public static String waitForResponse(long timeoutMs) {
        return null;
    }
}
