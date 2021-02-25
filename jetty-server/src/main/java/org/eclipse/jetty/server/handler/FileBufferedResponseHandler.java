//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.HttpOutput.Interceptor;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Buffered Response Handler
 * <p>
 * A Handler that can apply a {@link Interceptor}
 * mechanism to buffer the entire response content until the output is closed.
 * This allows the commit to be delayed until the response is complete and thus
 * headers and response status can be changed while writing the body.
 * <p>
 * Note that the decision to buffer is influenced by the headers and status at the
 * first write, and thus subsequent changes to those headers will not influence the
 * decision to buffer or not.
 * <p>
 * Note also that there are no memory limits to the size of the buffer, thus
 * this handler can represent an unbounded memory commitment if the content
 * generated can also be unbounded.
 * </p>
 */
public class FileBufferedResponseHandler extends HandlerWrapper
{
    static final Logger LOG = LoggerFactory.getLogger(FileBufferedResponseHandler.class);

    private final IncludeExclude<String> _methods = new IncludeExclude<>();
    private final IncludeExclude<String> _paths = new IncludeExclude<>(PathSpecSet.class);
    private final IncludeExclude<String> _mimeTypes = new IncludeExclude<>();
    private Path tempDir = new File(System.getProperty("java.io.tmpdir")).toPath();

    public FileBufferedResponseHandler()
    {
        // include only GET requests

        _methods.include(HttpMethod.GET.asString());
        // Exclude images, aduio and video from buffering
        for (String type : MimeTypes.getKnownMimeTypes())
        {
            if (type.startsWith("image/") ||
                type.startsWith("audio/") ||
                type.startsWith("video/"))
                _mimeTypes.exclude(type);
        }
        LOG.debug("{} mime types {}", this, _mimeTypes);
    }

    public IncludeExclude<String> getMethodIncludeExclude()
    {
        return _methods;
    }

    public IncludeExclude<String> getPathIncludeExclude()
    {
        return _paths;
    }

    public IncludeExclude<String> getMimeIncludeExclude()
    {
        return _mimeTypes;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        final ServletContext context = baseRequest.getServletContext();
        final String path = baseRequest.getPathInContext();
        LOG.debug("{} handle {} in {}", this, baseRequest, context);

        HttpOutput out = baseRequest.getResponse().getHttpOutput();

        // Are we already being gzipped?
        Interceptor interceptor = out.getInterceptor();
        while (interceptor != null)
        {
            if (interceptor instanceof BufferedInterceptor)
            {
                LOG.debug("{} already intercepting {}", this, request);
                _handler.handle(target, baseRequest, request, response);
                return;
            }
            interceptor = interceptor.getNextInterceptor();
        }

        // If not a supported method - no Vary because no matter what client, this URI is always excluded
        if (!_methods.test(baseRequest.getMethod()))
        {
            LOG.debug("{} excluded by method {}", this, request);
            _handler.handle(target, baseRequest, request, response);
            return;
        }

        // If not a supported URI- no Vary because no matter what client, this URI is always excluded
        // Use pathInfo because this is be
        if (!isPathBufferable(path))
        {
            LOG.debug("{} excluded by path {}", this, request);
            _handler.handle(target, baseRequest, request, response);
            return;
        }

        // If the mime type is known from the path, then apply mime type filtering 
        String mimeType = context == null ? MimeTypes.getDefaultMimeByExtension(path) : context.getMimeType(path);
        if (mimeType != null)
        {
            mimeType = MimeTypes.getContentTypeWithoutCharset(mimeType);
            if (!isMimeTypeBufferable(mimeType))
            {
                LOG.debug("{} excluded by path suffix mime type {}", this, request);
                // handle normally without setting vary header
                _handler.handle(target, baseRequest, request, response);
                return;
            }
        }

        // install interceptor and handle
        out.setInterceptor(new BufferedInterceptor(baseRequest.getHttpChannel(), out.getInterceptor()));

        if (_handler != null)
            _handler.handle(target, baseRequest, request, response);
    }

    public Path getTempDir()
    {
        return tempDir;
    }

    public void setTempDir(Path tempDir)
    {
        this.tempDir = tempDir;
    }

    protected boolean isMimeTypeBufferable(String mimetype)
    {
        return _mimeTypes.test(mimetype);
    }

    protected boolean isPathBufferable(String requestURI)
    {
        if (requestURI == null)
            return true;

        return _paths.test(requestURI);
    }

    private class BufferedInterceptor implements Interceptor
    {
        private final Interceptor _next;
        private final HttpChannel _channel;
        private Boolean _aggregating;
        private File _file;
        private OutputStream _bufferedOutputStream;

        public BufferedInterceptor(HttpChannel httpChannel, Interceptor interceptor)
        {
            _next = interceptor;
            _channel = httpChannel;
        }

        @Override
        public void resetBuffer()
        {
            if (_bufferedOutputStream != null)
            {
                try
                {
                    _bufferedOutputStream.close();
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.trace("Ignored", e);
                }
            }

            if (_file != null)
            {
                try
                {
                    Files.delete(_file.toPath());
                }
                catch (IOException e)
                {
                    LOG.warn("Could Not Delete File {}", _file, e);
                }
            }

            _aggregating = null;
            _bufferedOutputStream = null;
            _file = null;
        }

        @Override
        public void write(ByteBuffer content, boolean last, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} write last={} {}", this, last, BufferUtil.toDetailString(content));

            // If we are not committed, must decide if we should aggregate or not.
            if (_aggregating == null)
            {
                if (last)
                    _aggregating = Boolean.FALSE;
                else
                {
                    Response response = _channel.getResponse();
                    int sc = response.getStatus();
                    if (sc > 0 && (sc < 200 || sc == 204 || sc == 205 || sc >= 300))
                        _aggregating = Boolean.FALSE;  // No body
                    else
                    {
                        String ct = response.getContentType();
                        if (ct == null)
                            _aggregating = Boolean.TRUE;
                        else
                        {
                            ct = MimeTypes.getContentTypeWithoutCharset(ct);
                            _aggregating = isMimeTypeBufferable(StringUtil.asciiToLowerCase(ct));
                        }
                    }
                }
            }

            // If we are not aggregating, then handle normally.
            if (!_aggregating)
            {
                getNextInterceptor().write(content, last, callback);
                return;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{} aggregating", this);

            try
            {
                aggregate(content);
            }
            catch (IOException e)
            {
                callback.failed(e);
                return;
            }

            if (last)
                commit(callback);
            else
                callback.succeeded();
        }

        protected void aggregate(ByteBuffer content) throws IOException
        {
            if (_bufferedOutputStream == null)
            {
                // Create a new OutputStream to a file.
                _file = Files.createTempFile(tempDir, "BufferedResponse", "").toFile();
                _bufferedOutputStream = new BufferedOutputStream(Files.newOutputStream(_file.toPath(), StandardOpenOption.WRITE));
            }

            BufferUtil.writeTo(content, _bufferedOutputStream);
        }

        @Override
        public Interceptor getNextInterceptor()
        {
            return _next;
        }

        protected void commit(Callback callback)
        {
            try
            {
                Callback wrappedCallback = Callback.from(callback, this::resetBuffer);
                _bufferedOutputStream.flush();
                ByteBuffer buffer = BufferUtil.toMappedBuffer(_file);
                getNextInterceptor().write(buffer, true, wrappedCallback);
            }
            catch (IOException e)
            {
                callback.failed(e);
            }
        }
    }
}