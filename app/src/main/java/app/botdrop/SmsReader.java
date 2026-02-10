package app.botdrop;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Utility class for reading SMS messages from the device.
 * Provides methods to read, search, filter and manage SMS messages.
 */
public class SmsReader {

    private static final String LOG_TAG = "SmsReader";
    private static final Uri SMS_INBOX_URI = Telephony.Sms.Inbox.CONTENT_URI;
    private static final Uri SMS_ALL_URI = Telephony.Sms.CONTENT_URI;

    /**
     * SMS message data class
     */
    public static class SmsMessage {
        public final String id;
        public final String address;  // Sender phone number
        public final String body;     // Message content
        public final long date;       // Timestamp
        public final String dateFormatted;
        public final int read;        // 0 = unread, 1 = read
        public final String type;     // Message type
        public final int typeCode;    // Raw type code

        public SmsMessage(String id, String address, String body, long date, int read, int type) {
            this.id = id;
            this.address = address;
            this.body = body;
            this.date = date;
            this.read = read;
            this.typeCode = type;
            this.type = getTypeString(type);
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            this.dateFormatted = sdf.format(new Date(date));
        }

        private static String getTypeString(int type) {
            switch (type) {
                case Telephony.Sms.MESSAGE_TYPE_INBOX: return "inbox";
                case Telephony.Sms.MESSAGE_TYPE_SENT: return "sent";
                case Telephony.Sms.MESSAGE_TYPE_DRAFT: return "draft";
                case Telephony.Sms.MESSAGE_TYPE_OUTBOX: return "outbox";
                case Telephony.Sms.MESSAGE_TYPE_FAILED: return "failed";
                case Telephony.Sms.MESSAGE_TYPE_QUEUED: return "queued";
                default: return "unknown";
            }
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("sender", address);
            json.put("body", body);
            json.put("timestamp", date);
            json.put("date", dateFormatted);
            json.put("read", read == 1);
            json.put("type", type);
            return json;
        }

        @Override
        public String toString() {
            return String.format("[%s] From: %s | Body: %s", dateFormatted, address, body);
        }
    }

    /**
     * Query parameters for filtering SMS messages
     */
    public static class QueryParams {
        public String sender;           // Filter by sender address (partial match)
        public List<String> senders;    // Filter by multiple senders
        public String keyword;          // Filter by body content
        public List<String> keywords;   // Multiple keywords (AND logic)
        public String regex;            // Regex pattern for body
        public Long dateFrom;           // Start timestamp
        public Long dateTo;             // End timestamp
        public Boolean isRead;          // Filter by read status
        public String type;             // Filter by type: inbox, sent, draft, etc.
        public int limit = 50;          // Max results
        public int offset = 0;          // Pagination offset
        public boolean sortDesc = true; // Sort order

        public QueryParams() {}

        public QueryParams limit(int limit) {
            this.limit = limit;
            return this;
        }

        public QueryParams sender(String sender) {
            this.sender = sender;
            return this;
        }

        public QueryParams keyword(String keyword) {
            this.keyword = keyword;
            return this;
        }

        public QueryParams dateRange(Long from, Long to) {
            this.dateFrom = from;
            this.dateTo = to;
            return this;
        }

        public QueryParams read(Boolean read) {
            this.isRead = read;
            return this;
        }

        public QueryParams type(String type) {
            this.type = type;
            return this;
        }
    }

    /**
     * Query SMS messages with flexible filters
     */
    public static SmsMessage[] querySms(Context context, QueryParams params) {
        if (!hasSmsPermission(context)) {
            Logger.logError(LOG_TAG, "No SMS permission granted");
            return new SmsMessage[0];
        }

        ContentResolver cr = context.getContentResolver();
        String[] projection = {
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE
        };

        // Build selection
        List<String> selectionParts = new ArrayList<>();
        List<String> selectionArgs = new ArrayList<>();

        if (params.dateFrom != null) {
            selectionParts.add(Telephony.Sms.DATE + " >= ?");
            selectionArgs.add(String.valueOf(params.dateFrom));
        }
        if (params.dateTo != null) {
            selectionParts.add(Telephony.Sms.DATE + " <= ?");
            selectionArgs.add(String.valueOf(params.dateTo));
        }
        if (params.isRead != null) {
            selectionParts.add(Telephony.Sms.READ + " = ?");
            selectionArgs.add(params.isRead ? "1" : "0");
        }
        if (params.type != null && !params.type.isEmpty()) {
            int typeCode = getTypeCode(params.type);
            if (typeCode >= 0) {
                selectionParts.add(Telephony.Sms.TYPE + " = ?");
                selectionArgs.add(String.valueOf(typeCode));
            }
        }

        String selection = selectionParts.isEmpty() ? null : 
            String.join(" AND ", selectionParts);
        String[] selectionArgsArray = selectionArgs.isEmpty() ? null : 
            selectionArgs.toArray(new String[0]);

        String sortOrder = Telephony.Sms.DATE + (params.sortDesc ? " DESC" : " ASC");

        List<SmsMessage> messages = new ArrayList<>();
        Pattern regexPattern = null;
        if (params.regex != null && !params.regex.isEmpty()) {
            try {
                regexPattern = Pattern.compile(params.regex, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                Logger.logError(LOG_TAG, "Invalid regex pattern: " + e.getMessage());
            }
        }

        int skipped = 0;
        try (Cursor cursor = cr.query(SMS_ALL_URI, projection, selection, selectionArgsArray, sortOrder)) {
            if (cursor != null) {
                while (cursor.moveToNext() && messages.size() < params.limit) {
                    SmsMessage msg = extractMessageFromCursor(cursor);
                    
                    // Apply post-query filters
                    if (!matchesFilters(msg, params, regexPattern)) {
                        continue;
                    }
                    
                    // Handle offset
                    if (skipped < params.offset) {
                        skipped++;
                        continue;
                    }
                    
                    messages.add(msg);
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error querying SMS: " + e.getMessage());
        }

        return messages.toArray(new SmsMessage[0]);
    }

    private static boolean matchesFilters(SmsMessage msg, QueryParams params, Pattern regexPattern) {
        // Sender filter
        if (params.sender != null && !params.sender.isEmpty()) {
            String senderLower = params.sender.toLowerCase();
            String addrLower = msg.address != null ? msg.address.toLowerCase() : "";
            if (!addrLower.contains(senderLower)) {
                return false;
            }
        }

        // Multiple senders filter
        if (params.senders != null && !params.senders.isEmpty()) {
            boolean senderMatch = false;
            String addrLower = msg.address != null ? msg.address.toLowerCase() : "";
            for (String s : params.senders) {
                if (addrLower.contains(s.toLowerCase())) {
                    senderMatch = true;
                    break;
                }
            }
            if (!senderMatch) return false;
        }

        // Keyword filter
        if (params.keyword != null && !params.keyword.isEmpty()) {
            String kwLower = params.keyword.toLowerCase();
            String bodyLower = msg.body != null ? msg.body.toLowerCase() : "";
            String addrLower = msg.address != null ? msg.address.toLowerCase() : "";
            if (!bodyLower.contains(kwLower) && !addrLower.contains(kwLower)) {
                return false;
            }
        }

        // Multiple keywords filter (AND logic)
        if (params.keywords != null && !params.keywords.isEmpty()) {
            String bodyLower = msg.body != null ? msg.body.toLowerCase() : "";
            for (String kw : params.keywords) {
                if (!bodyLower.contains(kw.toLowerCase())) {
                    return false;
                }
            }
        }

        // Regex filter
        if (regexPattern != null) {
            if (msg.body == null || !regexPattern.matcher(msg.body).find()) {
                return false;
            }
        }

        return true;
    }

    private static int getTypeCode(String type) {
        switch (type.toLowerCase()) {
            case "inbox": return Telephony.Sms.MESSAGE_TYPE_INBOX;
            case "sent": return Telephony.Sms.MESSAGE_TYPE_SENT;
            case "draft": return Telephony.Sms.MESSAGE_TYPE_DRAFT;
            case "outbox": return Telephony.Sms.MESSAGE_TYPE_OUTBOX;
            case "failed": return Telephony.Sms.MESSAGE_TYPE_FAILED;
            case "queued": return Telephony.Sms.MESSAGE_TYPE_QUEUED;
            default: return -1;
        }
    }

    /**
     * Get SMS statistics
     */
    public static JSONObject getSmsStats(Context context) throws JSONException {
        if (!hasSmsPermission(context)) {
            JSONObject error = new JSONObject();
            error.put("error", "No SMS permission granted");
            return error;
        }

        ContentResolver cr = context.getContentResolver();
        String[] projection = {Telephony.Sms.TYPE, Telephony.Sms.READ};

        int total = 0, inbox = 0, sent = 0, draft = 0, unread = 0;

        try (Cursor cursor = cr.query(SMS_ALL_URI, projection, null, null, null)) {
            if (cursor != null) {
                int typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE);
                int readIdx = cursor.getColumnIndex(Telephony.Sms.READ);
                while (cursor.moveToNext()) {
                    total++;
                    int type = cursor.getInt(typeIdx);
                    int read = cursor.getInt(readIdx);
                    if (read == 0) unread++;
                    switch (type) {
                        case Telephony.Sms.MESSAGE_TYPE_INBOX: inbox++; break;
                        case Telephony.Sms.MESSAGE_TYPE_SENT: sent++; break;
                        case Telephony.Sms.MESSAGE_TYPE_DRAFT: draft++; break;
                    }
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error getting SMS stats: " + e.getMessage());
        }

        JSONObject stats = new JSONObject();
        stats.put("total", total);
        stats.put("inbox", inbox);
        stats.put("sent", sent);
        stats.put("draft", draft);
        stats.put("unread", unread);
        return stats;
    }

    /**
     * Get unique sender list with message counts
     */
    public static JSONObject getSenders(Context context, int limit) throws JSONException {
        if (!hasSmsPermission(context)) {
            JSONObject error = new JSONObject();
            error.put("error", "No SMS permission granted");
            return error;
        }

        ContentResolver cr = context.getContentResolver();
        String[] projection = {Telephony.Sms.ADDRESS};

        java.util.Map<String, Integer> senderCounts = new java.util.HashMap<>();

        try (Cursor cursor = cr.query(SMS_ALL_URI, projection, null, null, 
                Telephony.Sms.DATE + " DESC")) {
            if (cursor != null) {
                int addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
                while (cursor.moveToNext()) {
                    String addr = cursor.getString(addrIdx);
                    if (addr != null) {
                        senderCounts.put(addr, senderCounts.getOrDefault(addr, 0) + 1);
                    }
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error getting senders: " + e.getMessage());
        }

        // Sort by count
        List<java.util.Map.Entry<String, Integer>> sorted = new ArrayList<>(senderCounts.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        JSONArray senders = new JSONArray();
        for (int i = 0; i < Math.min(sorted.size(), limit); i++) {
            JSONObject sender = new JSONObject();
            sender.put("address", sorted.get(i).getKey());
            sender.put("count", sorted.get(i).getValue());
            senders.put(sender);
        }

        JSONObject result = new JSONObject();
        result.put("count", sorted.size());
        result.put("senders", senders);
        return result;
    }

    // ============ Convenience Methods ============

    public static SmsMessage getLatestSms(Context context) {
        QueryParams params = new QueryParams().limit(1);
        SmsMessage[] results = querySms(context, params);
        return results.length > 0 ? results[0] : null;
    }

    public static SmsMessage[] getRecentSms(Context context, int limit) {
        return getRecentSms(context, limit, null);
    }

    public static SmsMessage[] getRecentSms(Context context, int limit, String keyword) {
        QueryParams params = new QueryParams().limit(limit);
        if (keyword != null && !keyword.isEmpty()) {
            params.keyword(keyword);
        }
        return querySms(context, params);
    }

    public static SmsMessage[] getUnreadSms(Context context) {
        QueryParams params = new QueryParams()
            .read(false)
            .limit(100);
        return querySms(context, params);
    }

    public static SmsMessage[] getMessagesBySender(Context context, String sender, int limit) {
        QueryParams params = new QueryParams()
            .sender(sender)
            .limit(limit);
        return querySms(context, params);
    }

    public static SmsMessage[] getMessagesByType(Context context, String type, int limit) {
        QueryParams params = new QueryParams()
            .type(type)
            .limit(limit);
        return querySms(context, params);
    }

    public static SmsMessage[] searchByRegex(Context context, String regex, int limit) {
        QueryParams params = new QueryParams()
            .limit(limit);
        params.regex = regex;
        return querySms(context, params);
    }

    public static SmsMessage[] searchByDateRange(Context context, long from, long to, int limit) {
        QueryParams params = new QueryParams()
            .dateRange(from, to)
            .limit(limit);
        return querySms(context, params);
    }

    // ============ JSON Output Methods ============

    public static String getLatestSmsJson(Context context) {
        try {
            SmsMessage message = getLatestSms(context);
            if (message == null) {
                JSONObject error = new JSONObject();
                error.put("error", "No SMS found or permission denied");
                return error.toString();
            }
            return message.toJson().toString();
        } catch (JSONException e) {
            return "{\"error\":\"Failed to create JSON: " + e.getMessage() + "\"}";
        }
    }

    public static String getRecentSmsJson(Context context, int limit) {
        return getRecentSmsJson(context, limit, null);
    }

    public static String getRecentSmsJson(Context context, int limit, String keyword) {
        try {
            SmsMessage[] messages = getRecentSms(context, limit, keyword);
            return messagesToJson(messages, keyword);
        } catch (JSONException e) {
            return "{\"error\":\"Failed to create JSON: " + e.getMessage() + "\"}";
        }
    }

    public static String getUnreadSmsJson(Context context) {
        try {
            SmsMessage[] messages = getUnreadSms(context);
            return messagesToJson(messages, null);
        } catch (JSONException e) {
            return "{\"error\":\"Failed to create JSON: " + e.getMessage() + "\"}";
        }
    }

    public static String querySmsJson(Context context, QueryParams params) {
        try {
            SmsMessage[] messages = querySms(context, params);
            JSONObject result = new JSONObject();
            result.put("count", messages.length);
            if (params.keyword != null && !params.keyword.isEmpty()) {
                result.put("filter_keyword", params.keyword);
            }
            if (params.sender != null && !params.sender.isEmpty()) {
                result.put("filter_sender", params.sender);
            }
            if (params.type != null && !params.type.isEmpty()) {
                result.put("filter_type", params.type);
            }
            JSONArray array = new JSONArray();
            for (SmsMessage msg : messages) {
                array.put(msg.toJson());
            }
            result.put("messages", array);
            return result.toString();
        } catch (JSONException e) {
            return "{\"error\":\"Failed to create JSON: " + e.getMessage() + "\"}";
        }
    }

    private static String messagesToJson(SmsMessage[] messages, String keyword) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("count", messages.length);
        if (keyword != null && !keyword.isEmpty()) {
            result.put("filter_keyword", keyword);
        }
        JSONArray array = new JSONArray();
        for (SmsMessage msg : messages) {
            array.put(msg.toJson());
        }
        result.put("messages", array);
        return result.toString();
    }

    public static boolean hasSmsPermission(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.READ_SMS) 
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static SmsMessage extractMessageFromCursor(Cursor cursor) {
        String id = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms._ID));
        String address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS));
        String body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY));
        long date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE));
        int read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ));
        int type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE));

        return new SmsMessage(id, address, body, date, read, type);
    }
}
