package app.botdrop;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Handles SMS-related commands from OpenClaw via file-based IPC.
 * <p>
 * Communication protocol:
 * - OpenClaw writes request to: ~/.openclaw/sms/sms_request.json
 *   Format: {"action": "...", "timestamp": "ms_since_epoch", ...}
 * - Android reads request, processes it, writes response to: ~/.openclaw/sms/sms_response_{timestamp}.json
 * - OpenClaw polls for response file (with matching timestamp) and reads it
 * <p>
 * Supported actions:
 * - get_latest: Get the most recent SMS message
 * - get_recent: Get recent SMS messages (limit parameter)
 * - get_unread: Get all unread SMS messages
 * - search: Search by keyword
 * - query: Advanced query with multiple filters
 * - stats: Get SMS statistics
 * - senders: Get list of senders with message counts
 * - from: Get messages from specific sender
 * - by_type: Get messages by type (inbox/sent/draft)
 * - date_range: Get messages within date range
 * - regex: Search using regex pattern
 * - check_permission: Check SMS permission
 * - start_monitor: Start SMS monitoring service
 * - stop_monitor: Stop SMS monitoring service
 */
public class SmsCommandHandler {

    private static final String LOG_TAG = "SmsCommandHandler";
    private static final String SMS_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openclaw/sms";
    private static final String MONITOR_DIR = SMS_DIR + "/monitor";
    private static final String REQUEST_FILE = SMS_DIR + "/sms_request.json";
    private static final String RESPONSE_PREFIX = "sms_response_";
    private static final String RESPONSE_SUFFIX = ".json";

    private static final long POLL_INTERVAL_MS = 1000;
    private static final long RESPONSE_TIMEOUT_MS = 30000;

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

    public void start() {
        if (mIsRunning) return;
        mIsRunning = true;
        Logger.logInfo(LOG_TAG, "Starting SMS command handler");
        schedulePoll();
    }

    public void stop() {
        mIsRunning = false;
        mHandler.removeCallbacksAndMessages(null);
        Logger.logInfo(LOG_TAG, "Stopped SMS command handler");
    }

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
            mHandler.postDelayed(this::schedulePoll, POLL_INTERVAL_MS);
        });
    }

    private void checkAndProcessRequest() {
        File requestFile = new File(REQUEST_FILE);
        if (!requestFile.exists()) return;

        long lastModified = requestFile.lastModified();
        if (requestFile.equals(mLastProcessedRequest) && lastModified <= mLastProcessedTime) {
            return;
        }

        Logger.logInfo(LOG_TAG, "New SMS request detected");

        String timestamp = null;
        try {
            String requestJson = readFile(requestFile);
            if (requestJson == null || requestJson.isEmpty()) return;
            
            Logger.logInfo(LOG_TAG, "requestJson - " + requestJson);

            JSONObject request = new JSONObject(requestJson);
            String action = request.optString("action", "");
            timestamp = request.optString("timestamp", "");

            Logger.logInfo(LOG_TAG, "Processing SMS action: " + action);

            String responseJson = processAction(request, action);
            writeResponse(responseJson, timestamp);

            mLastProcessedRequest = requestFile;
            mLastProcessedTime = lastModified;

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to process SMS request: " + e.getMessage());
            try {
                JSONObject error = new JSONObject();
                error.put("error", "Failed to process request: " + e.getMessage());
                writeResponse(error.toString(), timestamp);
            } catch (Exception ignored) {}
        }
    }

    private String processAction(JSONObject request, String action) throws Exception {
        int limit = request.optInt("limit", 10);
        String keyword = request.optString("keyword", "");

        switch (action) {
            case "get_latest":
                return SmsReader.getLatestSmsJson(mContext);

            case "get_recent":
                return SmsReader.getRecentSmsJson(mContext, limit, keyword);

            case "search":
                if (keyword.isEmpty()) {
                    return errorJson("Missing required 'keyword' parameter for search action");
                }
                return SmsReader.getRecentSmsJson(mContext, Math.max(limit, 50), keyword);

            case "get_unread":
                return SmsReader.getUnreadSmsJson(mContext);

            case "query":
                return processQuery(request, limit);

            case "stats":
                return SmsReader.getSmsStats(mContext).toString();

            case "senders":
                return SmsReader.getSenders(mContext, limit).toString();

            case "from":
                String sender = request.optString("sender", "");
                if (sender.isEmpty()) {
                    return errorJson("Missing required 'sender' parameter");
                }
                SmsReader.QueryParams params = new SmsReader.QueryParams()
                    .sender(sender)
                    .limit(limit);
                return SmsReader.querySmsJson(mContext, params);

            case "by_type":
                String type = request.optString("type", "");
                if (type.isEmpty()) {
                    return errorJson("Missing required 'type' parameter (inbox/sent/draft)");
                }
                SmsReader.QueryParams typeParams = new SmsReader.QueryParams()
                    .type(type)
                    .limit(limit);
                return SmsReader.querySmsJson(mContext, typeParams);

            case "date_range":
                return processDateRange(request, limit);

            case "regex":
                String pattern = request.optString("pattern", "");
                if (pattern.isEmpty()) {
                    return errorJson("Missing required 'pattern' parameter for regex search");
                }
                SmsReader.QueryParams regexParams = new SmsReader.QueryParams()
                    .limit(limit);
                regexParams.regex = pattern;
                return SmsReader.querySmsJson(mContext, regexParams);

            case "check_permission":
                return checkPermissionJson();

            default:
                JSONObject error = new JSONObject();
                error.put("error", "Unknown action: " + action);
                error.put("supported_actions", new String[]{
                    "get_latest", "get_recent", "get_unread", "search",
                    "query", "stats", "senders", "from", "by_type",
                    "date_range", "regex", "check_permission",
                    "start_monitor", "stop_monitor", "monitor_status"
                });
                return error.toString();
        }
    }

    private String processQuery(JSONObject request, int defaultLimit) throws Exception {
        SmsReader.QueryParams params = new SmsReader.QueryParams();
        params.limit = request.optInt("limit", defaultLimit);
        params.offset = request.optInt("offset", 0);
        params.sortDesc = request.optBoolean("desc", true);

        // Sender filters
        String sender = request.optString("sender", "");
        if (!sender.isEmpty()) {
            params.sender(sender);
        }

        JSONArray senders = request.optJSONArray("senders");
        if (senders != null) {
            List<String> senderList = new ArrayList<>();
            for (int i = 0; i < senders.length(); i++) {
                senderList.add(senders.getString(i));
            }
            params.senders = senderList;
        }

        // Keyword filters
        String keyword = request.optString("keyword", "");
        if (!keyword.isEmpty()) {
            params.keyword(keyword);
        }

        JSONArray keywords = request.optJSONArray("keywords");
        if (keywords != null) {
            List<String> keywordList = new ArrayList<>();
            for (int i = 0; i < keywords.length(); i++) {
                keywordList.add(keywords.getString(i));
            }
            params.keywords = keywordList;
        }

        // Other filters
        String type = request.optString("type", "");
        if (!type.isEmpty()) {
            params.type(type);
        }

        if (request.has("read")) {
            params.read(request.optBoolean("read", true));
        }

        // Date range
        if (request.has("date_from")) {
            params.dateFrom = request.getLong("date_from");
        }
        if (request.has("date_to")) {
            params.dateTo = request.getLong("date_to");
        }

        return SmsReader.querySmsJson(mContext, params);
    }

    private String processDateRange(JSONObject request, int limit) throws Exception {
        long from = request.optLong("from", 0);
        long to = request.optLong("to", System.currentTimeMillis());

        if (from == 0) {
            // Support relative time: "1d", "7d", "30d"
            String period = request.optString("period", "");
            if (!period.isEmpty()) {
                to = System.currentTimeMillis();
                long days = parsePeriod(period);
                from = to - (days * 24 * 60 * 60 * 1000);
            } else {
                return errorJson("Missing 'from' timestamp or 'period' parameter");
            }
        }

        SmsReader.QueryParams params = new SmsReader.QueryParams()
            .dateRange(from, to)
            .limit(limit);

        String keyword = request.optString("keyword", "");
        if (!keyword.isEmpty()) {
            params.keyword(keyword);
        }

        return SmsReader.querySmsJson(mContext, params);
    }

    private long parsePeriod(String period) {
        if (period.endsWith("d")) {
            try {
                return Long.parseLong(period.substring(0, period.length() - 1));
            } catch (NumberFormatException e) {
                return 7; // default 7 days
            }
        }
        return 7;
    }

    private String errorJson(String message) throws Exception {
        JSONObject error = new JSONObject();
        error.put("error", message);
        return error.toString();
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
        String filename = RESPONSE_PREFIX + 
            (timestamp != null && !timestamp.isEmpty() ? timestamp : System.currentTimeMillis()) + 
            RESPONSE_SUFFIX;
        File responseFile = new File(SMS_DIR, filename);

        File parentDir = responseFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (FileWriter writer = new FileWriter(responseFile)) {
            writer.write(json);
        }

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

    // Static helpers for OpenClaw side
    public static boolean createRequest(String action, int limit) {
        return createRequest(action, limit, null);
    }

    public static boolean createRequest(String action, int limit, String extra) {
        try {
            File requestFile = new File(REQUEST_FILE);
            File parentDir = requestFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            String timestamp = String.valueOf(System.currentTimeMillis());
            JSONObject request = new JSONObject();
            request.put("action", action);
            request.put("timestamp", timestamp);
            request.put("limit", limit);
            if (extra != null) {
                request.put("extra", extra);
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

    public static String readResponse(String timestamp) {
        try {
            if (timestamp == null || timestamp.isEmpty()) return null;
            
            String filename = RESPONSE_PREFIX + timestamp + RESPONSE_SUFFIX;
            File responseFile = new File(SMS_DIR, filename);
            
            if (!responseFile.exists()) return null;

            StringBuilder content = new StringBuilder();
            try (Scanner scanner = new Scanner(responseFile)) {
                while (scanner.hasNextLine()) {
                    content.append(scanner.nextLine());
                }
            }

            responseFile.delete();
            return content.toString();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to read response: " + e.getMessage());
            return null;
        }
    }

    public static String waitForResponse(long timeoutMs, String timestamp) {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            String response = readResponse(timestamp);
            if (response != null) return response;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
        return null;
    }

    @Deprecated
    public static String readResponse() { return null; }

    @Deprecated
    public static String waitForResponse(long timeoutMs) { return null; }
}
