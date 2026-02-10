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
import java.util.Date;
import java.util.Locale;

/**
 * Utility class for reading SMS messages from the device.
 * Provides methods to read latest SMS and query SMS inbox.
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

        public SmsMessage(String id, String address, String body, long date, int read, int type) {
            this.id = id;
            this.address = address;
            this.body = body;
            this.date = date;
            this.read = read;
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
     * Get the most recent SMS message
     * @param context Application context
     * @return The latest SMS message, or null if no messages found
     */
    public static SmsMessage getLatestSms(Context context) {
        if (!hasSmsPermission(context)) {
            Logger.logError(LOG_TAG, "No SMS permission granted");
            return null;
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

        // Sort by date descending to get the most recent
        String sortOrder = Telephony.Sms.DATE + " DESC";

        try (Cursor cursor = cr.query(SMS_ALL_URI, projection, null, null, sortOrder)) {
            if (cursor != null && cursor.moveToFirst()) {
                return extractMessageFromCursor(cursor);
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error reading latest SMS: " + e.getMessage());
        }

        return null;
    }

    /**
     * Get recent SMS messages
     * @param context Application context
     * @param limit Maximum number of messages to return
     * @return Array of SMS messages, ordered by date (newest first)
     */
    public static SmsMessage[] getRecentSms(Context context, int limit) {
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

        String sortOrder = Telephony.Sms.DATE + " DESC LIMIT " + limit;

        java.util.List<SmsMessage> messages = new java.util.ArrayList<>();

        try (Cursor cursor = cr.query(SMS_ALL_URI, projection, null, null, sortOrder)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    messages.add(extractMessageFromCursor(cursor));
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error reading SMS messages: " + e.getMessage());
        }

        return messages.toArray(new SmsMessage[0]);
    }

    /**
     * Get unread SMS messages
     * @param context Application context
     * @return Array of unread SMS messages
     */
    public static SmsMessage[] getUnreadSms(Context context) {
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

        String selection = Telephony.Sms.READ + " = ?";
        String[] selectionArgs = {"0"}; // 0 = unread
        String sortOrder = Telephony.Sms.DATE + " DESC";

        java.util.List<SmsMessage> messages = new java.util.ArrayList<>();

        try (Cursor cursor = cr.query(SMS_ALL_URI, projection, selection, selectionArgs, sortOrder)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    messages.add(extractMessageFromCursor(cursor));
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error reading unread SMS: " + e.getMessage());
        }

        return messages.toArray(new SmsMessage[0]);
    }

    /**
     * Get the latest SMS as JSON string
     * @param context Application context
     * @return JSON string of the latest SMS, or error JSON if failed
     */
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

    /**
     * Get recent SMS messages as JSON array string
     * @param context Application context
     * @param limit Maximum number of messages
     * @return JSON array string of messages
     */
    public static String getRecentSmsJson(Context context, int limit) {
        try {
            SmsMessage[] messages = getRecentSms(context, limit);
            JSONArray array = new JSONArray();
            for (SmsMessage msg : messages) {
                array.put(msg.toJson());
            }
            
            JSONObject result = new JSONObject();
            result.put("count", messages.length);
            result.put("messages", array);
            return result.toString();
        } catch (JSONException e) {
            return "{\"error\":\"Failed to create JSON: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get unread SMS messages as JSON array string
     * @param context Application context
     * @return JSON array string of unread messages
     */
    public static String getUnreadSmsJson(Context context) {
        try {
            SmsMessage[] messages = getUnreadSms(context);
            JSONArray array = new JSONArray();
            for (SmsMessage msg : messages) {
                array.put(msg.toJson());
            }
            
            JSONObject result = new JSONObject();
            result.put("count", messages.length);
            result.put("messages", array);
            return result.toString();
        } catch (JSONException e) {
            return "{\"error\":\"Failed to create JSON: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Check if the app has SMS permission
     * @param context Application context
     * @return true if permission granted
     */
    public static boolean hasSmsPermission(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.READ_SMS) 
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Extract SMS message data from cursor
     */
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
