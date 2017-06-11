// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.net.urlconnection;

import android.util.Pair;

import org.chromium.base.Log;
import org.chromium.net.CronetEngine;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlRequestException;
import org.chromium.net.UrlResponseInfo;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An implementation of {@link HttpURLConnection} that uses Cronet to send
 * requests and receive responses.
 * {@hide}
 */
public class CronetHttpURLConnection extends HttpURLConnection {
    private static final String TAG = "cr_CronetHttpURLConn";
    private static final String CONTENT_LENGTH = "Content-Length";
    private final CronetEngine mCronetEngine;
    private final MessageLoop mMessageLoop;
    private UrlRequest mRequest;
    private final List<Pair<String, String>> mRequestHeaders;

    private CronetInputStream mInputStream;
    private CronetOutputStream mOutputStream;
    private UrlResponseInfo mResponseInfo;
    private UrlRequestException mException;
    private boolean mOnRedirectCalled = false;
    private boolean mHasResponse = false;
    private List<Map.Entry<String, String>> mResponseHeadersList;
    private Map<String, List<String>> mResponseHeadersMap;

    public CronetHttpURLConnection(URL url, CronetEngine cronetEngine) {
        super(url);
        mCronetEngine = cronetEngine;
        mMessageLoop = new MessageLoop();
        mInputStream = new CronetInputStream(this);
        mRequestHeaders = new ArrayList<Pair<String, String>>();
    }

    /**
     * Opens a connection to the resource. If the connect method is called when
     * the connection has already been opened (indicated by the connected field
     * having the value {@code true}), the call is ignored.
     */
    @Override
    public void connect() throws IOException {
        getOutputStream();
        // If request is started in getOutputStream, calling startRequest()
        // again has no effect.
        startRequest();
    }

    /**
     * Releases this connection so that its resources may be either reused or
     * closed.
     */
    @Override
    public void disconnect() {
        // Disconnect before connection is made should have no effect.
        if (connected) {
            mRequest.cancel();
        }
    }

    /**
     * Returns the response message returned by the remote HTTP server.
     */
    @Override
    public String getResponseMessage() throws IOException {
        getResponse();
        return mResponseInfo.getHttpStatusText();
    }

    /**
     * Returns the response code returned by the remote HTTP server.
     */
    @Override
    public int getResponseCode() throws IOException {
        getResponse();
        return mResponseInfo.getHttpStatusCode();
    }

    /**
     * Returns an unmodifiable map of the response-header fields and values.
     */
    @Override
    public Map<String, List<String>> getHeaderFields() {
        try {
            getResponse();
        } catch (IOException e) {
            return Collections.emptyMap();
        }
        return getAllHeaders();
    }

    /**
     * Returns the value of the named header field. If called on a connection
     * that sets the same header multiple times with possibly different values,
     * only the last value is returned.
     */
    @Override
    public final String getHeaderField(String fieldName) {
        try {
            getResponse();
        } catch (IOException e) {
            return null;
        }
        Map<String, List<String>> map = getAllHeaders();
        if (!map.containsKey(fieldName)) {
            return null;
        }
        List<String> values = map.get(fieldName);
        return values.get(values.size() - 1);
    }

    /**
     * Returns the name of the header field at the given position {@code pos}, or {@code null}
     * if there are fewer than {@code pos} fields.
     */
    @Override
    public final String getHeaderFieldKey(int pos) {
        Map.Entry<String, String> header = getHeaderFieldEntry(pos);
        if (header == null) {
            return null;
        }
        return header.getKey();
    }

    /**
     * Returns the header value at the field position {@code pos} or {@code null} if the header
     * has fewer than {@code pos} fields.
     */
    @Override
    public final String getHeaderField(int pos) {
        Map.Entry<String, String> header = getHeaderFieldEntry(pos);
        if (header == null) {
            return null;
        }
        return header.getValue();
    }

    /**
     * Returns an InputStream for reading data from the resource pointed by this
     * {@link java.net.URLConnection}.
     * @throws FileNotFoundException if http response code is equal or greater
     *             than {@link HTTP_BAD_REQUEST}.
     * @throws IOException If the request gets a network error or HTTP error
     *             status code, or if the caller tried to read the response body
     *             of a redirect when redirects are disabled.
     */
    @Override
    public InputStream getInputStream() throws IOException {
        getResponse();
        if (!instanceFollowRedirects && mOnRedirectCalled) {
            throw new IOException("Cannot read response body of a redirect.");
        }
        // Emulate default implementation's behavior to throw
        // FileNotFoundException when we get a 400 and above.
        if (mResponseInfo.getHttpStatusCode() >= HTTP_BAD_REQUEST) {
            throw new FileNotFoundException(url.toString());
        }
        return mInputStream;
    }

    /**
     * Returns an {@link OutputStream} for writing data to this {@link URLConnection}.
     * @throws IOException if no {@code OutputStream} could be created.
     */
    @Override
    public OutputStream getOutputStream() throws IOException {
        if (mOutputStream == null && doOutput) {
            if (connected) {
                throw new ProtocolException(
                        "Cannot write to OutputStream after receiving response.");
            }
            if (isChunkedUpload()) {
                mOutputStream = new CronetChunkedOutputStream(this, chunkLength, mMessageLoop);
                // Start the request now since all headers can be sent.
                startRequest();
            } else {
                long fixedStreamingModeContentLength = getStreamingModeContentLength();
                if (fixedStreamingModeContentLength != -1) {
                    mOutputStream = new CronetFixedModeOutputStream(
                            this, fixedStreamingModeContentLength, mMessageLoop);
                    // Start the request now since all headers can be sent.
                    startRequest();
                } else {
                    // For the buffered case, start the request only when
                    // content-length bytes are received, or when a
                    // connect action is initiated by the consumer.
                    Log.d(TAG, "Outputstream is being buffered in memory.");
                    String length = getRequestProperty(CONTENT_LENGTH);
                    if (length == null) {
                        mOutputStream = new CronetBufferedOutputStream(this);
                    } else {
                        long lengthParsed = Long.parseLong(length);
                        mOutputStream = new CronetBufferedOutputStream(this, lengthParsed);
                    }
                }
            }
        }
        return mOutputStream;
    }

    /**
     * Helper method to get content length passed in by
     * {@link #setFixedLengthStreamingMode}
     */
    private long getStreamingModeContentLength() {
        long contentLength = fixedContentLength;
        // Use reflection to see whether fixedContentLengthLong (only added
        // in API 19) is inherited.
        try {
            Class<?> parent = this.getClass();
            long superFixedContentLengthLong =
                    parent.getField("fixedContentLengthLong").getLong(this);
            if (superFixedContentLengthLong != -1) {
                contentLength = superFixedContentLengthLong;
            }
        } catch (Exception e) {
            // Ignored.
        }
        return contentLength;
    }

    /**
     * Starts the request if {@code connected} is false.
     */
    private void startRequest() throws IOException {
        if (connected) {
            return;
        }
        final UrlRequest.Builder requestBuilder = new UrlRequest.Builder(
                getURL().toString(), new CronetUrlRequestCallback(), mMessageLoop, mCronetEngine);
        if (doOutput) {
            if (method.equals("GET")) {
                method = "POST";
            }
            if (mOutputStream != null) {
                requestBuilder.setUploadDataProvider(
                        mOutputStream.getUploadDataProvider(), mMessageLoop);
                if (getRequestProperty(CONTENT_LENGTH) == null && !isChunkedUpload()) {
                    addRequestProperty(CONTENT_LENGTH,
                            Long.toString(mOutputStream.getUploadDataProvider().getLength()));
                }
                // Tells mOutputStream that startRequest() has been called, so
                // the underlying implementation can prepare for reading if needed.
                mOutputStream.setConnected();
            } else {
                if (getRequestProperty(CONTENT_LENGTH) == null) {
                    addRequestProperty(CONTENT_LENGTH, "0");
                }
            }
            // Default Content-Type to application/x-www-form-urlencoded
            if (getRequestProperty("Content-Type") == null) {
                addRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded");
            }
        }
        for (Pair<String, String> requestHeader : mRequestHeaders) {
            requestBuilder.addHeader(requestHeader.first, requestHeader.second);
        }
        if (!getUseCaches()) {
            requestBuilder.disableCache();
        }
        // Set HTTP method.
        requestBuilder.setHttpMethod(method);

        connected = true;
        mRequest = requestBuilder.build();
        // Start the request.
        mRequest.start();
    }

    /**
     * Returns an input stream from the server in the case of an error such as
     * the requested file has not been found on the remote server.
     */
    @Override
    public InputStream getErrorStream() {
        try {
            getResponse();
        } catch (IOException e) {
            return null;
        }
        if (mResponseInfo.getHttpStatusCode() >= HTTP_BAD_REQUEST) {
            return mInputStream;
        }
        return null;
    }

    /**
     * Adds the given property to the request header.
     */
    @Override
    public final void addRequestProperty(String key, String value) {
        setRequestPropertyInternal(key, value, false);
    }

    /**
     * Sets the value of the specified request header field.
     */
    @Override
    public final void setRequestProperty(String key, String value) {
        setRequestPropertyInternal(key, value, true);
    }

    private final void setRequestPropertyInternal(String key, String value,
            boolean overwrite) {
        if (connected) {
            throw new IllegalStateException(
                    "Cannot modify request property after connection is made.");
        }
        int index = findRequestProperty(key);
        if (index >= 0) {
            if (overwrite) {
                mRequestHeaders.remove(index);
            } else {
                // Cronet does not support adding multiple headers
                // of the same key, see crbug.com/432719 for more details.
                throw new UnsupportedOperationException(
                        "Cannot add multiple headers of the same key, " + key
                        + ". crbug.com/432719.");
            }
        }
        // Adds the new header at the end of mRequestHeaders.
        mRequestHeaders.add(Pair.create(key, value));
    }

    /**
     * Returns an unmodifiable map of general request properties used by this
     * connection.
     */
    @Override
    public Map<String, List<String>> getRequestProperties() {
        if (connected) {
            throw new IllegalStateException(
                    "Cannot access request headers after connection is set.");
        }
        Map<String, List<String>> map = new TreeMap<String, List<String>>(
                String.CASE_INSENSITIVE_ORDER);
        for (Pair<String, String> entry : mRequestHeaders) {
            if (map.containsKey(entry.first)) {
                // This should not happen due to setRequestPropertyInternal.
                throw new IllegalStateException(
                    "Should not have multiple values.");
            } else {
                List<String> values = new ArrayList<String>();
                values.add(entry.second);
                map.put(entry.first, Collections.unmodifiableList(values));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Returns the value of the request header property specified by {@code
     * key} or {@code null} if there is no key with this name.
     */
    @Override
    public String getRequestProperty(String key) {
        int index = findRequestProperty(key);
        if (index >= 0) {
            return mRequestHeaders.get(index).second;
        }
        return null;
    }

    /**
     * Returns whether this connection uses a proxy server.
     */
    @Override
    public boolean usingProxy() {
        // TODO(xunjieli): implement this.
        return false;
    }

    @Override
    public void setConnectTimeout(int timeout) {
        // Per-request connect timeout is not supported because of late binding.
        // Sockets are assigned to requests according to request priorities
        // when sockets are connected. This requires requests with the same host,
        // domain and port to have same timeout.
        Log.d(TAG, "setConnectTimeout is not supported by CronetHttpURLConnection");
    }

    /**
     * Used by {@link CronetInputStream} to get more data from the network
     * stack. This should only be called after the request has started. Note
     * that this call might block if there isn't any more data to be read.
     * Since byteBuffer is passed to the UrlRequest, it must be a direct
     * ByteBuffer.
     */
    void getMoreData(ByteBuffer byteBuffer) throws IOException {
        mRequest.read(byteBuffer);
        mMessageLoop.loop(getReadTimeout());
    }

    /**
     * Returns the index of request header in {@link #mRequestHeaders} or
     * -1 if not found.
     */
    private int findRequestProperty(String key) {
        for (int i = 0; i < mRequestHeaders.size(); i++) {
            Pair<String, String> entry = mRequestHeaders.get(i);
            if (entry.first.equalsIgnoreCase(key)) {
                return i;
            }
        }
        return -1;
    }

    private class CronetUrlRequestCallback extends UrlRequest.Callback {
        public CronetUrlRequestCallback() {}

        @Override
        public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
            mResponseInfo = info;
            // Quits the message loop since we have the headers now.
            mMessageLoop.quit();
        }

        @Override
        public void onReadCompleted(
                UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) {
            mResponseInfo = info;
            mMessageLoop.quit();
        }

        @Override
        public void onRedirectReceived(
                UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
            mOnRedirectCalled = true;
            try {
                URL newUrl = new URL(newLocationUrl);
                boolean sameProtocol = newUrl.getProtocol().equals(url.getProtocol());
                if (instanceFollowRedirects) {
                    // Update the url variable even if the redirect will not be
                    // followed due to different protocols.
                    url = newUrl;
                }
                if (instanceFollowRedirects && sameProtocol) {
                    mRequest.followRedirect();
                    return;
                }
            } catch (MalformedURLException e) {
                // Ignored. Just cancel the request and not follow the redirect.
            }
            mResponseInfo = info;
            mRequest.cancel();
            setResponseDataCompleted(null);
        }

        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            mResponseInfo = info;
            setResponseDataCompleted(null);
        }

        @Override
        public void onFailed(
                UrlRequest request, UrlResponseInfo info, UrlRequestException exception) {
            if (exception == null) {
                throw new IllegalStateException(
                        "Exception cannot be null in onFailed.");
            }
            mResponseInfo = info;
            mException = exception;
            setResponseDataCompleted(mException);
        }

        @Override
        public void onCanceled(UrlRequest request, UrlResponseInfo info) {
            mResponseInfo = info;
            setResponseDataCompleted(new IOException("stream closed"));
        }

        /**
         * Notifies {@link #mInputStream} that transferring of response data has
         * completed.
         * @param exception if not {@code null}, it is the exception to report when
         *            caller tries to read more data.
         */
        private void setResponseDataCompleted(IOException exception) {
            if (mInputStream != null) {
                mInputStream.setResponseDataCompleted(exception);
            }
            if (mOutputStream != null) {
                mOutputStream.setRequestCompleted(exception);
            }
            mHasResponse = true;
            mMessageLoop.quit();
        }
    }

    /**
     * Blocks until the respone headers are received.
     */
    private void getResponse() throws IOException {
        // Check to see if enough data has been received.
        if (mOutputStream != null) {
            mOutputStream.checkReceivedEnoughContent();
            if (isChunkedUpload()) {
                // Write last chunk.
                mOutputStream.close();
            }
        }
        if (!mHasResponse) {
            startRequest();
            // Blocks until onResponseStarted or onFailed is called.
            mMessageLoop.loop();
            mHasResponse = true;
        }
        checkHasResponse();
    }

    /**
     * Checks whether response headers are received, and throws an exception if
     * an exception occurred before headers received. This method should only be
     * called after onResponseStarted or onFailed.
     */
    private void checkHasResponse() throws IOException {
        if (!mHasResponse) throw new IllegalStateException("No response.");
        if (mException != null) {
            throw mException;
        } else if (mResponseInfo == null) {
            throw new NullPointerException(
                    "Response info is null when there is no exception.");
        }
    }

    /**
     * Helper method to return the response header field at position pos.
     */
    private Map.Entry<String, String> getHeaderFieldEntry(int pos) {
        try {
            getResponse();
        } catch (IOException e) {
            return null;
        }
        List<Map.Entry<String, String>> headers = getAllHeadersAsList();
        if (pos >= headers.size()) {
            return null;
        }
        return headers.get(pos);
    }

    /**
     * Returns whether the client has used {@link #setChunkedStreamingMode} to
     * set chunked encoding for upload.
     */
    private boolean isChunkedUpload() {
        return chunkLength > 0;
    }

    // TODO(xunjieli): Refactor to reuse code in UrlResponseInfo.
    private Map<String, List<String>> getAllHeaders() {
        if (mResponseHeadersMap != null) {
            return mResponseHeadersMap;
        }
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : getAllHeadersAsList()) {
            List<String> values = new ArrayList<String>();
            if (map.containsKey(entry.getKey())) {
                values.addAll(map.get(entry.getKey()));
            }
            values.add(entry.getValue());
            map.put(entry.getKey(), Collections.unmodifiableList(values));
        }
        mResponseHeadersMap = Collections.unmodifiableMap(map);
        return mResponseHeadersMap;
    }

    private List<Map.Entry<String, String>> getAllHeadersAsList() {
        if (mResponseHeadersList != null) {
            return mResponseHeadersList;
        }
        mResponseHeadersList = new ArrayList<Map.Entry<String, String>>();
        for (Map.Entry<String, String> entry : mResponseInfo.getAllHeadersAsList()) {
            // Strips Content-Encoding response header. See crbug.com/592700.
            if (!entry.getKey().equalsIgnoreCase("Content-Encoding")) {
                mResponseHeadersList.add(
                        new AbstractMap.SimpleImmutableEntry<String, String>(entry));
            }
        }
        mResponseHeadersList = Collections.unmodifiableList(mResponseHeadersList);
        return mResponseHeadersList;
    }
}
