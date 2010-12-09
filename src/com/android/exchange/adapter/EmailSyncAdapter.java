/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.exchange.adapter;

import com.android.email.LegacyConversions;
import com.android.email.Utility;
import com.android.email.mail.Address;
import com.android.email.mail.MeetingInfo;
import com.android.email.mail.MessagingException;
import com.android.email.mail.PackedString;
import com.android.email.mail.Part;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.provider.EmailContent.SyncColumns;
import com.android.email.service.MailService;
import com.android.exchange.Eas;
import com.android.exchange.EasSyncService;
import com.android.exchange.MessageMoveRequest;
import com.android.exchange.utility.CalendarUtilities;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.webkit.MimeTypeMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Sync adapter for EAS email
 *
 */
public class EmailSyncAdapter extends AbstractSyncAdapter {

    private static final int UPDATES_READ_COLUMN = 0;
    private static final int UPDATES_MAILBOX_KEY_COLUMN = 1;
    private static final int UPDATES_SERVER_ID_COLUMN = 2;
    private static final int UPDATES_FLAG_COLUMN = 3;
    private static final String[] UPDATES_PROJECTION =
        {MessageColumns.FLAG_READ, MessageColumns.MAILBOX_KEY, SyncColumns.SERVER_ID,
            MessageColumns.FLAG_FAVORITE};

    private static final int MESSAGE_ID_SUBJECT_ID_COLUMN = 0;
    private static final int MESSAGE_ID_SUBJECT_SUBJECT_COLUMN = 1;
    private static final String[] MESSAGE_ID_SUBJECT_PROJECTION =
        new String[] { Message.RECORD_ID, MessageColumns.SUBJECT };

    private static final String WHERE_BODY_SOURCE_MESSAGE_KEY = Body.SOURCE_MESSAGE_KEY + "=?";
    private static final String WHERE_MAILBOX_KEY_AND_MOVED =
        MessageColumns.MAILBOX_KEY + "=? AND (" + MessageColumns.FLAGS + "&" +
        EasSyncService.MESSAGE_FLAG_MOVED_MESSAGE + ")!=0";
    private static final String[] FETCH_REQUEST_PROJECTION =
        new String[] {EmailContent.RECORD_ID, SyncColumns.SERVER_ID};
    private static final int FETCH_REQUEST_RECORD_ID = 0;
    private static final int FETCH_REQUEST_SERVER_ID = 1;

    private static final String EMAIL_WINDOW_SIZE = "5";

    String[] mBindArguments = new String[2];
    String[] mBindArgument = new String[1];

    /*package*/ ArrayList<Long> mDeletedIdList = new ArrayList<Long>();
    /*package*/ ArrayList<Long> mUpdatedIdList = new ArrayList<Long>();
    /*package*/ ArrayList<FetchRequest> mFetchRequestList = new ArrayList<FetchRequest>();
    private boolean mFetchNeeded = false;

    // Holds the parser's value for isLooping()
    boolean mIsLooping = false;

    public EmailSyncAdapter(EasSyncService service) {
        super(service);
    }

    @Override
    public void wipe() {
        mContentResolver.delete(Message.CONTENT_URI,
                Message.MAILBOX_KEY + "=" + mMailbox.mId, null);
        mContentResolver.delete(Message.DELETED_CONTENT_URI,
                Message.MAILBOX_KEY + "=" + mMailbox.mId, null);
        mContentResolver.delete(Message.UPDATED_CONTENT_URI,
                Message.MAILBOX_KEY + "=" + mMailbox.mId, null);
        // Delete attachments...
        AttachmentProvider.deleteAllMailboxAttachmentFiles(mContext, mAccount.mId, mMailbox.mId);
    }

    private String getEmailFilter() {
        switch (mAccount.mSyncLookback) {
            case com.android.email.Account.SYNC_WINDOW_1_DAY:
                return Eas.FILTER_1_DAY;
            case com.android.email.Account.SYNC_WINDOW_3_DAYS:
                return Eas.FILTER_3_DAYS;
            case com.android.email.Account.SYNC_WINDOW_1_WEEK:
                return Eas.FILTER_1_WEEK;
            case com.android.email.Account.SYNC_WINDOW_2_WEEKS:
                return Eas.FILTER_2_WEEKS;
            case com.android.email.Account.SYNC_WINDOW_1_MONTH:
                return Eas.FILTER_1_MONTH;
            case com.android.email.Account.SYNC_WINDOW_ALL:
                return Eas.FILTER_ALL;
            default:
                return Eas.FILTER_1_WEEK;
        }
    }

    /**
     * Holder for fetch request information (record id and server id)
     */
    static class FetchRequest {
        final long messageId;
        final String serverId;

        FetchRequest(long _messageId, String _serverId) {
            messageId = _messageId;
            serverId = _serverId;
        }
    }

    @Override
    public void sendSyncOptions(Double protocolVersion, Serializer s)
            throws IOException  {
        mFetchRequestList.clear();
        // Find partially loaded messages; this should typically be a rare occurrence
        Cursor c = mContext.getContentResolver().query(Message.CONTENT_URI,
                FETCH_REQUEST_PROJECTION,
                MessageColumns.FLAG_LOADED + "=" + Message.FLAG_LOADED_PARTIAL + " AND " +
                MessageColumns.MAILBOX_KEY + "=?",
                new String[] {Long.toString(mMailbox.mId)}, null);
        try {
            // Put all of these messages into a list; we'll need both id and server id
            while (c.moveToNext()) {
                mFetchRequestList.add(new FetchRequest(c.getLong(FETCH_REQUEST_RECORD_ID),
                        c.getString(FETCH_REQUEST_SERVER_ID)));
            }
        } finally {
            c.close();
        }

        // The "empty" case is typical; we send a request for changes, and also specify a sync
        // window, body preference type (HTML for EAS 12.0 and later; MIME for EAS 2.5), and
        // truncation
        // If there are fetch requests, we only want the fetches (i.e. no changes from the server)
        // so we turn MIME support off.  Note that we are always using EAS 2.5 if there are fetch
        // requests
        if (mFetchRequestList.isEmpty()) {
            s.tag(Tags.SYNC_DELETES_AS_MOVES);
            s.tag(Tags.SYNC_GET_CHANGES);
            s.data(Tags.SYNC_WINDOW_SIZE, EMAIL_WINDOW_SIZE);
            s.start(Tags.SYNC_OPTIONS);
            // Set the lookback appropriately (EAS calls this a "filter")
            s.data(Tags.SYNC_FILTER_TYPE, getEmailFilter());
            // Set the truncation amount for all classes
            if (protocolVersion >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                s.start(Tags.BASE_BODY_PREFERENCE);
                // HTML for email
                s.data(Tags.BASE_TYPE, Eas.BODY_PREFERENCE_HTML);
                s.data(Tags.BASE_TRUNCATION_SIZE, Eas.EAS12_TRUNCATION_SIZE);
                s.end();
            } else {
                // Use MIME data for EAS 2.5
                s.data(Tags.SYNC_MIME_SUPPORT, Eas.MIME_BODY_PREFERENCE_MIME);
                s.data(Tags.SYNC_MIME_TRUNCATION, Eas.EAS2_5_TRUNCATION_SIZE);
            }
            s.end();
        } else {
            s.start(Tags.SYNC_OPTIONS);
            // Ask for plain text, rather than MIME data.  This guarantees that we'll get a usable
            // text body
            s.data(Tags.SYNC_MIME_SUPPORT, Eas.MIME_BODY_PREFERENCE_TEXT);
            s.data(Tags.SYNC_TRUNCATION, Eas.EAS2_5_TRUNCATION_SIZE);
            s.end();
        }
    }

    @Override
    public boolean parse(InputStream is) throws IOException {
        EasEmailSyncParser p = new EasEmailSyncParser(is, this);
        mFetchNeeded = false;
        boolean res = p.parse();
        // Hold on to the parser's value for isLooping() to pass back to the service
        mIsLooping = p.isLooping();
        // If we've need a body fetch, or we've just finished one, return true in order to continue
        if (mFetchNeeded || !mFetchRequestList.isEmpty()) {
            return true;
        }
        return res;
    }

    /**
     * Return the value of isLooping() as returned from the parser
     */
    @Override
    public boolean isLooping() {
        return mIsLooping;
    }

    @Override
    public boolean isSyncable() {
        return true;
    }

    public class EasEmailSyncParser extends AbstractSyncParser {

        private static final String WHERE_SERVER_ID_AND_MAILBOX_KEY =
            SyncColumns.SERVER_ID + "=? and " + MessageColumns.MAILBOX_KEY + "=?";

        private String mMailboxIdAsString;

        ArrayList<Message> newEmails = new ArrayList<Message>();
        ArrayList<Message> fetchedEmails = new ArrayList<Message>();
        ArrayList<Long> deletedEmails = new ArrayList<Long>();
        ArrayList<ServerChange> changedEmails = new ArrayList<ServerChange>();

        public EasEmailSyncParser(InputStream in, EmailSyncAdapter adapter) throws IOException {
            super(in, adapter);
            mMailboxIdAsString = Long.toString(mMailbox.mId);
        }

        public void addData (Message msg) throws IOException {
            ArrayList<Attachment> atts = new ArrayList<Attachment>();
            boolean truncated = false;

            while (nextTag(Tags.SYNC_APPLICATION_DATA) != END) {
                switch (tag) {
                    case Tags.EMAIL_ATTACHMENTS:
                    case Tags.BASE_ATTACHMENTS: // BASE_ATTACHMENTS is used in EAS 12.0 and up
                        attachmentsParser(atts, msg);
                        break;
                    case Tags.EMAIL_TO:
                        msg.mTo = Address.pack(Address.parse(getValue()));
                        break;
                    case Tags.EMAIL_FROM:
                        Address[] froms = Address.parse(getValue());
                        if (froms != null && froms.length > 0) {
                            msg.mDisplayName = froms[0].toFriendly();
                        }
                        msg.mFrom = Address.pack(froms);
                        break;
                    case Tags.EMAIL_CC:
                        msg.mCc = Address.pack(Address.parse(getValue()));
                        break;
                    case Tags.EMAIL_REPLY_TO:
                        msg.mReplyTo = Address.pack(Address.parse(getValue()));
                        break;
                    case Tags.EMAIL_DATE_RECEIVED:
                        msg.mTimeStamp = Utility.parseEmailDateTimeToMillis(getValue());
                        break;
                    case Tags.EMAIL_SUBJECT:
                        msg.mSubject = getValue();
                        break;
                    case Tags.EMAIL_READ:
                        msg.mFlagRead = getValueInt() == 1;
                        break;
                    case Tags.BASE_BODY:
                        bodyParser(msg);
                        break;
                    case Tags.EMAIL_FLAG:
                        msg.mFlagFavorite = flagParser();
                        break;
                    case Tags.EMAIL_MIME_TRUNCATED:
                        truncated = getValueInt() == 1;
                        break;
                    case Tags.EMAIL_MIME_DATA:
                        // We get MIME data for EAS 2.5.  First we parse it, then we take the
                        // html and/or plain text data and store it in the message
                        if (truncated) {
                            // If the MIME data is truncated, don't bother parsing it, because
                            // it will take time and throw an exception anyway when EOF is reached
                            // In this case, we will load the body separately by tagging the message
                            // "partially loaded".
                            userLog("Partially loaded: ", msg.mServerId);
                            msg.mFlagLoaded = Message.FLAG_LOADED_PARTIAL;
                            mFetchNeeded = true;
                        } else {
                            mimeBodyParser(msg, getValue());
                        }
                        break;
                    case Tags.EMAIL_BODY:
                        String text = getValue();
                        msg.mText = text;
                        break;
                    case Tags.EMAIL_MESSAGE_CLASS:
                        String messageClass = getValue();
                        if (messageClass.equals("IPM.Schedule.Meeting.Request")) {
                            msg.mFlags |= Message.FLAG_INCOMING_MEETING_INVITE;
                        } else if (messageClass.equals("IPM.Schedule.Meeting.Canceled")) {
                            msg.mFlags |= Message.FLAG_INCOMING_MEETING_CANCEL;
                        }
                        break;
                    case Tags.EMAIL_MEETING_REQUEST:
                        meetingRequestParser(msg);
                        break;
                    default:
                        skipTag();
                }
            }

            if (atts.size() > 0) {
                msg.mAttachments = atts;
            }
        }

        /**
         * Set up the meetingInfo field in the message with various pieces of information gleaned
         * from MeetingRequest tags.  This information will be used later to generate an appropriate
         * reply email if the user chooses to respond
         * @param msg the Message being built
         * @throws IOException
         */
        private void meetingRequestParser(Message msg) throws IOException {
            PackedString.Builder packedString = new PackedString.Builder();
            while (nextTag(Tags.EMAIL_MEETING_REQUEST) != END) {
                switch (tag) {
                    case Tags.EMAIL_DTSTAMP:
                        packedString.put(MeetingInfo.MEETING_DTSTAMP, getValue());
                        break;
                    case Tags.EMAIL_START_TIME:
                        packedString.put(MeetingInfo.MEETING_DTSTART, getValue());
                        break;
                    case Tags.EMAIL_END_TIME:
                        packedString.put(MeetingInfo.MEETING_DTEND, getValue());
                        break;
                    case Tags.EMAIL_ORGANIZER:
                        packedString.put(MeetingInfo.MEETING_ORGANIZER_EMAIL, getValue());
                        break;
                    case Tags.EMAIL_LOCATION:
                        packedString.put(MeetingInfo.MEETING_LOCATION, getValue());
                        break;
                    case Tags.EMAIL_GLOBAL_OBJID:
                        packedString.put(MeetingInfo.MEETING_UID,
                                CalendarUtilities.getUidFromGlobalObjId(getValue()));
                        break;
                    case Tags.EMAIL_CATEGORIES:
                        nullParser();
                        break;
                    case Tags.EMAIL_RECURRENCES:
                        recurrencesParser();
                        break;
                    case Tags.EMAIL_RESPONSE_REQUESTED:
                        packedString.put(MeetingInfo.MEETING_RESPONSE_REQUESTED, getValue());
                        break;
                    default:
                        skipTag();
                }
            }
            if (msg.mSubject != null) {
                packedString.put(MeetingInfo.MEETING_TITLE, msg.mSubject);
            }
            msg.mMeetingInfo = packedString.toString();
        }

        private void nullParser() throws IOException {
            while (nextTag(Tags.EMAIL_CATEGORIES) != END) {
                skipTag();
            }
        }

        private void recurrencesParser() throws IOException {
            while (nextTag(Tags.EMAIL_RECURRENCES) != END) {
                switch (tag) {
                    case Tags.EMAIL_RECURRENCE:
                        nullParser();
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private void addParser(ArrayList<Message> emails) throws IOException {
            Message msg = new Message();
            msg.mAccountKey = mAccount.mId;
            msg.mMailboxKey = mMailbox.mId;
            msg.mFlagLoaded = Message.FLAG_LOADED_COMPLETE;

            while (nextTag(Tags.SYNC_ADD) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        msg.mServerId = getValue();
                        break;
                    case Tags.SYNC_APPLICATION_DATA:
                        addData(msg);
                        break;
                    default:
                        skipTag();
                }
            }
            emails.add(msg);
        }

        // For now, we only care about the "active" state
        private Boolean flagParser() throws IOException {
            Boolean state = false;
            while (nextTag(Tags.EMAIL_FLAG) != END) {
                switch (tag) {
                    case Tags.EMAIL_FLAG_STATUS:
                        state = getValueInt() == 2;
                        break;
                    default:
                        skipTag();
                }
            }
            return state;
        }

        private void bodyParser(Message msg) throws IOException {
            String bodyType = Eas.BODY_PREFERENCE_TEXT;
            String body = "";
            while (nextTag(Tags.EMAIL_BODY) != END) {
                switch (tag) {
                    case Tags.BASE_TYPE:
                        bodyType = getValue();
                        break;
                    case Tags.BASE_DATA:
                        body = getValue();
                        break;
                    default:
                        skipTag();
                }
            }
            // We always ask for TEXT or HTML; there's no third option
            if (bodyType.equals(Eas.BODY_PREFERENCE_HTML)) {
                msg.mHtml = body;
            } else {
                msg.mText = body;
            }
        }

        /**
         * Parses untruncated MIME data, saving away the text parts
         * @param msg the message we're building
         * @param mimeData the MIME data we've received from the server
         * @throws IOException
         */
        private void mimeBodyParser(Message msg, String mimeData) throws IOException {
            try {
                ByteArrayInputStream in = new ByteArrayInputStream(mimeData.getBytes());
                // The constructor parses the message
                MimeMessage mimeMessage = new MimeMessage(in);
                // Now process body parts & attachments
                ArrayList<Part> viewables = new ArrayList<Part>();
                // We'll ignore the attachments, as we'll get them directly from EAS
                ArrayList<Part> attachments = new ArrayList<Part>();
                MimeUtility.collectParts(mimeMessage, viewables, attachments);
                Body tempBody = new Body();
                // updateBodyFields fills in the content fields of the Body
                LegacyConversions.updateBodyFields(tempBody, msg, viewables);
                // But we need them in the message itself for handling during commit()
                msg.mHtml = tempBody.mHtmlContent;
                msg.mText = tempBody.mTextContent;
            } catch (MessagingException e) {
                // This would most likely indicate a broken stream
                throw new IOException(e);
            }
        }

        private void attachmentsParser(ArrayList<Attachment> atts, Message msg) throws IOException {
            while (nextTag(Tags.EMAIL_ATTACHMENTS) != END) {
                switch (tag) {
                    case Tags.EMAIL_ATTACHMENT:
                    case Tags.BASE_ATTACHMENT:  // BASE_ATTACHMENT is used in EAS 12.0 and up
                        attachmentParser(atts, msg);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private void attachmentParser(ArrayList<Attachment> atts, Message msg) throws IOException {
            String fileName = null;
            String length = null;
            String location = null;

            while (nextTag(Tags.EMAIL_ATTACHMENT) != END) {
                switch (tag) {
                    // We handle both EAS 2.5 and 12.0+ attachments here
                    case Tags.EMAIL_DISPLAY_NAME:
                    case Tags.BASE_DISPLAY_NAME:
                        fileName = getValue();
                        break;
                    case Tags.EMAIL_ATT_NAME:
                    case Tags.BASE_FILE_REFERENCE:
                        location = getValue();
                        break;
                    case Tags.EMAIL_ATT_SIZE:
                    case Tags.BASE_ESTIMATED_DATA_SIZE:
                        length = getValue();
                        break;
                    default:
                        skipTag();
                }
            }

            if ((fileName != null) && (length != null) && (location != null)) {
                Attachment att = new Attachment();
                att.mEncoding = "base64";
                att.mSize = Long.parseLong(length);
                att.mFileName = fileName;
                att.mLocation = location;
                att.mMimeType = getMimeTypeFromFileName(fileName);
                atts.add(att);
                msg.mFlagAttachment = true;
            }
        }

        /**
         * Try to determine a mime type from a file name, defaulting to application/x, where x
         * is either the extension or (if none) octet-stream
         * At the moment, this is somewhat lame, since many file types aren't recognized
         * @param fileName the file name to ponder
         * @return
         */
        // Note: The MimeTypeMap method currently uses a very limited set of mime types
        // A bug has been filed against this issue.
        public String getMimeTypeFromFileName(String fileName) {
            String mimeType;
            int lastDot = fileName.lastIndexOf('.');
            String extension = null;
            if ((lastDot > 0) && (lastDot < fileName.length() - 1)) {
                extension = fileName.substring(lastDot + 1).toLowerCase();
            }
            if (extension == null) {
                // A reasonable default for now.
                mimeType = "application/octet-stream";
            } else {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mimeType == null) {
                    mimeType = "application/" + extension;
                }
            }
            return mimeType;
        }

        private Cursor getServerIdCursor(String serverId, String[] projection) {
            mBindArguments[0] = serverId;
            mBindArguments[1] = mMailboxIdAsString;
            return mContentResolver.query(Message.CONTENT_URI, projection,
                    WHERE_SERVER_ID_AND_MAILBOX_KEY, mBindArguments, null);
        }

        /*package*/ void deleteParser(ArrayList<Long> deletes, int entryTag) throws IOException {
            while (nextTag(entryTag) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        String serverId = getValue();
                        // Find the message in this mailbox with the given serverId
                        Cursor c = getServerIdCursor(serverId, MESSAGE_ID_SUBJECT_PROJECTION);
                        try {
                            if (c.moveToFirst()) {
                                deletes.add(c.getLong(MESSAGE_ID_SUBJECT_ID_COLUMN));
                                if (Eas.USER_LOG) {
                                    userLog("Deleting ", serverId + ", "
                                            + c.getString(MESSAGE_ID_SUBJECT_SUBJECT_COLUMN));
                                }
                            }
                        } finally {
                            c.close();
                        }
                        break;
                    default:
                        skipTag();
                }
            }
        }

        class ServerChange {
            long id;
            Boolean read;
            Boolean flag;

            ServerChange(long _id, Boolean _read, Boolean _flag) {
                id = _id;
                read = _read;
                flag = _flag;
            }
        }

        /*package*/ void changeParser(ArrayList<ServerChange> changes) throws IOException {
            String serverId = null;
            Boolean oldRead = false;
            Boolean oldFlag = false;
            long id = 0;
            while (nextTag(Tags.SYNC_CHANGE) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        serverId = getValue();
                        Cursor c = getServerIdCursor(serverId, Message.LIST_PROJECTION);
                        try {
                            if (c.moveToFirst()) {
                                userLog("Changing ", serverId);
                                oldRead = c.getInt(Message.LIST_READ_COLUMN) == Message.READ;
                                oldFlag = c.getInt(Message.LIST_FAVORITE_COLUMN) == 1;
                                id = c.getLong(Message.LIST_ID_COLUMN);
                            }
                        } finally {
                            c.close();
                        }
                        break;
                    case Tags.SYNC_APPLICATION_DATA:
                        changeApplicationDataParser(changes, oldRead, oldFlag, id);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private void changeApplicationDataParser(ArrayList<ServerChange> changes, Boolean oldRead,
                Boolean oldFlag, long id) throws IOException {
            Boolean read = null;
            Boolean flag = null;
            while (nextTag(Tags.SYNC_APPLICATION_DATA) != END) {
                switch (tag) {
                    case Tags.EMAIL_READ:
                        read = getValueInt() == 1;
                        break;
                    case Tags.EMAIL_FLAG:
                        flag = flagParser();
                        break;
                    default:
                        skipTag();
                }
            }
            if (((read != null) && !oldRead.equals(read)) ||
                    ((flag != null) && !oldFlag.equals(flag))) {
                changes.add(new ServerChange(id, read, flag));
            }
        }

        /* (non-Javadoc)
         * @see com.android.exchange.adapter.EasContentParser#commandsParser()
         */
        @Override
        public void commandsParser() throws IOException {
            while (nextTag(Tags.SYNC_COMMANDS) != END) {
                if (tag == Tags.SYNC_ADD) {
                    addParser(newEmails);
                    incrementChangeCount();
                } else if (tag == Tags.SYNC_DELETE || tag == Tags.SYNC_SOFT_DELETE) {
                    deleteParser(deletedEmails, tag);
                    incrementChangeCount();
                } else if (tag == Tags.SYNC_CHANGE) {
                    changeParser(changedEmails);
                    incrementChangeCount();
                } else
                    skipTag();
            }
        }

        @Override
        public void responsesParser() throws IOException {
            while (nextTag(Tags.SYNC_RESPONSES) != END) {
                if (tag == Tags.SYNC_ADD || tag == Tags.SYNC_CHANGE || tag == Tags.SYNC_DELETE) {
                    // We can ignore all of these
                } else if (tag == Tags.SYNC_FETCH) {
                    addParser(fetchedEmails);
                }
            }
        }

        @Override
        public void commit() {
            int notifyCount = 0;

            // Use a batch operation to handle the changes
            // TODO New mail notifications?  Who looks for these?
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

            for (Message msg: fetchedEmails) {
                // If there's really no text, we're done here
                if (msg.mText == null) continue;
                // Find the original message's id (by serverId and mailbox)
                Cursor c = getServerIdCursor(msg.mServerId, EmailContent.ID_PROJECTION);
                String id = null;
                try {
                    if (c.moveToFirst()) {
                        id = c.getString(EmailContent.ID_PROJECTION_COLUMN);
                    }
                } finally {
                    c.close();
                }

                // If we find one, we do two things atomically: 1) set the body text for the
                // message, and 2) mark the message loaded (i.e. completely loaded)
                if (id != null) {
                    userLog("Fetched body successfully for ", id);
                    mBindArgument[0] = id;
                    ops.add(ContentProviderOperation.newUpdate(Body.CONTENT_URI)
                            .withSelection(Body.MESSAGE_KEY + "=?", mBindArgument)
                            .withValue(Body.TEXT_CONTENT, msg.mText)
                            .build());
                    ops.add(ContentProviderOperation.newUpdate(Message.CONTENT_URI)
                            .withSelection(EmailContent.RECORD_ID + "=?", mBindArgument)
                            .withValue(Message.FLAG_LOADED, Message.FLAG_LOADED_COMPLETE)
                            .build());
                }
            }

            for (Message msg: newEmails) {
                if (!msg.mFlagRead) {
                    notifyCount++;
                }
                msg.addSaveOps(ops);
            }

            for (Long id : deletedEmails) {
                ops.add(ContentProviderOperation.newDelete(
                        ContentUris.withAppendedId(Message.CONTENT_URI, id)).build());
                AttachmentProvider.deleteAllAttachmentFiles(mContext, mAccount.mId, id);
            }

            if (!changedEmails.isEmpty()) {
                // Server wins in a conflict...
                for (ServerChange change : changedEmails) {
                     ContentValues cv = new ContentValues();
                    if (change.read != null) {
                        cv.put(MessageColumns.FLAG_READ, change.read);
                    }
                    if (change.flag != null) {
                        cv.put(MessageColumns.FLAG_FAVORITE, change.flag);
                    }
                    ops.add(ContentProviderOperation.newUpdate(
                            ContentUris.withAppendedId(Message.CONTENT_URI, change.id))
                                .withValues(cv)
                                .build());
                }
            }

            // We only want to update the sync key here
            ContentValues mailboxValues = new ContentValues();
            mailboxValues.put(Mailbox.SYNC_KEY, mMailbox.mSyncKey);
            ops.add(ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mMailbox.mId))
                        .withValues(mailboxValues).build());

            addCleanupOps(ops);

            // No commits if we're stopped
            synchronized (mService.getSynchronizer()) {
                if (mService.isStopped()) return;
                try {
                    mContentResolver.applyBatch(EmailProvider.EMAIL_AUTHORITY, ops);
                    userLog(mMailbox.mDisplayName, " SyncKey saved as: ", mMailbox.mSyncKey);
                } catch (RemoteException e) {
                    // There is nothing to be done here; fail by returning null
                } catch (OperationApplicationException e) {
                    // There is nothing to be done here; fail by returning null
                }
            }

            if (notifyCount > 0) {
                // Use the new atomic add URI in EmailProvider
                // We could add this to the operations being done, but it's not strictly
                // speaking necessary, as the previous batch preserves the integrity of the
                // database, whereas this is purely for notification purposes, and is itself atomic
                ContentValues cv = new ContentValues();
                cv.put(EmailContent.FIELD_COLUMN_NAME, AccountColumns.NEW_MESSAGE_COUNT);
                cv.put(EmailContent.ADD_COLUMN_NAME, notifyCount);
                Uri uri = ContentUris.withAppendedId(Account.ADD_TO_FIELD_URI, mAccount.mId);
                mContentResolver.update(uri, cv, null, null);
                MailService.actionNotifyNewMessages(mContext, mAccount.mId);
            }
        }
    }

    @Override
    public String getCollectionName() {
        return "Email";
    }

    private void addCleanupOps(ArrayList<ContentProviderOperation> ops) {
        // If we've sent local deletions, clear out the deleted table
        for (Long id: mDeletedIdList) {
            ops.add(ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(Message.DELETED_CONTENT_URI, id)).build());
        }
        // And same with the updates
        for (Long id: mUpdatedIdList) {
            ops.add(ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(Message.UPDATED_CONTENT_URI, id)).build());
        }
        // Delete any moved messages (since we've just synced the mailbox, and no longer need the
        // placeholder message); this prevents duplicates from appearing in the mailbox.
        mBindArgument[0] = Long.toString(mMailbox.mId);
        ops.add(ContentProviderOperation.newDelete(Message.CONTENT_URI)
                .withSelection(WHERE_MAILBOX_KEY_AND_MOVED, mBindArgument).build());
    }

    @Override
    public void cleanup() {
        if (!mDeletedIdList.isEmpty() || !mUpdatedIdList.isEmpty()) {
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            addCleanupOps(ops);
            try {
                mContext.getContentResolver()
                    .applyBatch(EmailProvider.EMAIL_AUTHORITY, ops);
            } catch (RemoteException e) {
                // There is nothing to be done here; fail by returning null
            } catch (OperationApplicationException e) {
                // There is nothing to be done here; fail by returning null
            }
        }
    }

    private String formatTwo(int num) {
        if (num < 10) {
            return "0" + (char)('0' + num);
        } else
            return Integer.toString(num);
    }

    /**
     * Create date/time in RFC8601 format.  Oddly enough, for calendar date/time, Microsoft uses
     * a different format that excludes the punctuation (this is why I'm not putting this in a
     * parent class)
     */
    public String formatDateTime(Calendar calendar) {
        StringBuilder sb = new StringBuilder();
        //YYYY-MM-DDTHH:MM:SS.MSSZ
        sb.append(calendar.get(Calendar.YEAR));
        sb.append('-');
        sb.append(formatTwo(calendar.get(Calendar.MONTH) + 1));
        sb.append('-');
        sb.append(formatTwo(calendar.get(Calendar.DAY_OF_MONTH)));
        sb.append('T');
        sb.append(formatTwo(calendar.get(Calendar.HOUR_OF_DAY)));
        sb.append(':');
        sb.append(formatTwo(calendar.get(Calendar.MINUTE)));
        sb.append(':');
        sb.append(formatTwo(calendar.get(Calendar.SECOND)));
        sb.append(".000Z");
        return sb.toString();
    }

    /**
     * Note that messages in the deleted database preserve the message's unique id; therefore, we
     * can utilize this id to find references to the message.  The only reference situation at this
     * point is in the Body table; it is when sending messages via SmartForward and SmartReply
     */
    private boolean messageReferenced(ContentResolver cr, long id) {
        mBindArgument[0] = Long.toString(id);
        // See if this id is referenced in a body
        Cursor c = cr.query(Body.CONTENT_URI, Body.ID_PROJECTION, WHERE_BODY_SOURCE_MESSAGE_KEY,
                mBindArgument, null);
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /*private*/ /**
     * Serialize commands to delete items from the server; as we find items to delete, add their
     * id's to the deletedId's array
     *
     * @param s the Serializer we're using to create post data
     * @param deletedIds ids whose deletions are being sent to the server
     * @param first whether or not this is the first command being sent
     * @return true if SYNC_COMMANDS hasn't been sent (false otherwise)
     * @throws IOException
     */
    boolean sendDeletedItems(Serializer s, ArrayList<Long> deletedIds, boolean first)
            throws IOException {
        ContentResolver cr = mContext.getContentResolver();

        // Find any of our deleted items
        Cursor c = cr.query(Message.DELETED_CONTENT_URI, Message.LIST_PROJECTION,
                MessageColumns.MAILBOX_KEY + '=' + mMailbox.mId, null, null);
        // We keep track of the list of deleted item id's so that we can remove them from the
        // deleted table after the server receives our command
        deletedIds.clear();
        try {
            while (c.moveToNext()) {
                String serverId = c.getString(Message.LIST_SERVER_ID_COLUMN);
                // Keep going if there's no serverId
                if (serverId == null) {
                    continue;
                // Also check if this message is referenced elsewhere
                } else if (messageReferenced(cr, c.getLong(Message.CONTENT_ID_COLUMN))) {
                    userLog("Postponing deletion of referenced message: ", serverId);
                    continue;
                } else if (first) {
                    s.start(Tags.SYNC_COMMANDS);
                    first = false;
                }
                // Send the command to delete this message
                s.start(Tags.SYNC_DELETE).data(Tags.SYNC_SERVER_ID, serverId).end();
                deletedIds.add(c.getLong(Message.LIST_ID_COLUMN));
            }
        } finally {
            c.close();
        }

       return first;
    }

    @Override
    public boolean sendLocalChanges(Serializer s) throws IOException {
        ContentResolver cr = mContext.getContentResolver();

        // Never upsync from these folders
        if (mMailbox.mType == Mailbox.TYPE_DRAFTS || mMailbox.mType == Mailbox.TYPE_OUTBOX) {
            return false;
        }

        // This code is split out for unit testing purposes
        boolean firstCommand = sendDeletedItems(s, mDeletedIdList, true);

        if (!mFetchRequestList.isEmpty()) {
            // Add FETCH commands for messages that need a body (i.e. we didn't find it during
            // our earlier sync; this happens only in EAS 2.5 where the body couldn't be found
            // after parsing the message's MIME data)
            if (firstCommand) {
                s.start(Tags.SYNC_COMMANDS);
                firstCommand = false;
            }
            for (FetchRequest req: mFetchRequestList) {
                s.start(Tags.SYNC_FETCH).data(Tags.SYNC_SERVER_ID, req.serverId).end();
            }
        }

        // Find our trash mailbox, since deletions will have been moved there...
        long trashMailboxId =
            Mailbox.findMailboxOfType(mContext, mMailbox.mAccountKey, Mailbox.TYPE_TRASH);

        // Do the same now for updated items
        Cursor c = cr.query(Message.UPDATED_CONTENT_URI, Message.LIST_PROJECTION,
                MessageColumns.MAILBOX_KEY + '=' + mMailbox.mId, null, null);

        // We keep track of the list of updated item id's as we did above with deleted items
        mUpdatedIdList.clear();
        try {
            while (c.moveToNext()) {
                long id = c.getLong(Message.LIST_ID_COLUMN);
                // Say we've handled this update
                mUpdatedIdList.add(id);
                // We have the id of the changed item.  But first, we have to find out its current
                // state, since the updated table saves the opriginal state
                Cursor currentCursor = cr.query(ContentUris.withAppendedId(Message.CONTENT_URI, id),
                        UPDATES_PROJECTION, null, null, null);
                try {
                    // If this item no longer exists (shouldn't be possible), just move along
                    if (!currentCursor.moveToFirst()) {
                         continue;
                    }
                    // Keep going if there's no serverId
                    String serverId = currentCursor.getString(UPDATES_SERVER_ID_COLUMN);
                    if (serverId == null) {
                        continue;
                    }
                    // If the message is now in the trash folder, it has been deleted by the user
                    if (currentCursor.getLong(UPDATES_MAILBOX_KEY_COLUMN) == trashMailboxId) {
                         if (firstCommand) {
                            s.start(Tags.SYNC_COMMANDS);
                            firstCommand = false;
                        }
                        // Send the command to delete this message
                        s.start(Tags.SYNC_DELETE).data(Tags.SYNC_SERVER_ID, serverId).end();
                        continue;
                    }

                    boolean flagChange = false;
                    boolean readChange = false;

                    long mailbox = currentCursor.getLong(UPDATES_MAILBOX_KEY_COLUMN);
                    if (mailbox != c.getLong(Message.LIST_MAILBOX_KEY_COLUMN)) {
                        // The message has moved to another mailbox; add a request for this
                        // Note: The Sync command doesn't handle moving messages, so we need
                        // to handle this as a "request" (similar to meeting response and
                        // attachment load)
                        mService.addRequest(new MessageMoveRequest(id, mailbox));
                        // Regardless of other changes that might be made, we don't want to indicate
                        // that this message has been updated until the move request has been
                        // handled (without this, a crash between the flag upsync and the move
                        // would cause the move to be lost)
                        mUpdatedIdList.remove(id);
                    }

                    // We can only send flag changes to the server in 12.0 or later
                    int flag = 0;
                    if (mService.mProtocolVersionDouble >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                        flag = currentCursor.getInt(UPDATES_FLAG_COLUMN);
                        if (flag != c.getInt(Message.LIST_FAVORITE_COLUMN)) {
                            flagChange = true;
                        }
                    }

                    int read = currentCursor.getInt(UPDATES_READ_COLUMN);
                    if (read != c.getInt(Message.LIST_READ_COLUMN)) {
                        readChange = true;
                    }

                    if (!flagChange && !readChange) {
                        // In this case, we've got nothing to send to the server
                        continue;
                    }

                    if (firstCommand) {
                        s.start(Tags.SYNC_COMMANDS);
                        firstCommand = false;
                    }
                    // Send the change to "read" and "favorite" (flagged)
                    s.start(Tags.SYNC_CHANGE)
                        .data(Tags.SYNC_SERVER_ID, c.getString(Message.LIST_SERVER_ID_COLUMN))
                        .start(Tags.SYNC_APPLICATION_DATA);
                    if (readChange) {
                        s.data(Tags.EMAIL_READ, Integer.toString(read));
                    }
                    // "Flag" is a relatively complex concept in EAS 12.0 and above.  It is not only
                    // the boolean "favorite" that we think of in Gmail, but it also represents a
                    // follow up action, which can include a subject, start and due dates, and even
                    // recurrences.  We don't support any of this as yet, but EAS 12.0 and higher
                    // require that a flag contain a status, a type, and four date fields, two each
                    // for start date and end (due) date.
                    if (flagChange) {
                        if (flag != 0) {
                            // Status 2 = set flag
                            s.start(Tags.EMAIL_FLAG).data(Tags.EMAIL_FLAG_STATUS, "2");
                            // "FollowUp" is the standard type
                            s.data(Tags.EMAIL_FLAG_TYPE, "FollowUp");
                            long now = System.currentTimeMillis();
                            Calendar calendar =
                                GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"));
                            calendar.setTimeInMillis(now);
                            // Flags are required to have a start date and end date (duplicated)
                            // First, we'll set the current date/time in GMT as the start time
                            String utc = formatDateTime(calendar);
                            s.data(Tags.TASK_START_DATE, utc).data(Tags.TASK_UTC_START_DATE, utc);
                            // And then we'll use one week from today for completion date
                            calendar.setTimeInMillis(now + 1*WEEKS);
                            utc = formatDateTime(calendar);
                            s.data(Tags.TASK_DUE_DATE, utc).data(Tags.TASK_UTC_DUE_DATE, utc);
                            s.end();
                        } else {
                            s.tag(Tags.EMAIL_FLAG);
                        }
                    }
                    s.end().end(); // SYNC_APPLICATION_DATA, SYNC_CHANGE
                } finally {
                    currentCursor.close();
                }
            }
        } finally {
            c.close();
        }

        if (!firstCommand) {
            s.end(); // SYNC_COMMANDS
        }
        return false;
    }
}
