//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.message;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;

public class MessageOutputStream extends OutputStream
{
    private static final Logger LOG = Log.getLogger(MessageOutputStream.class);
    private final OutgoingFrames outgoing;
    private final int bufferSize;
    private long frameCount = 0;
    private WebSocketFrame frame;
    private ByteBuffer buffer;
    private FutureWriteCallback blocker;
    private boolean closed = false;

    public MessageOutputStream(WebSocketSession session)
    {
        this.outgoing = session.getOutgoingHandler();
        this.bufferSize = session.getPolicy().getMaxBinaryMessageBufferSize();
        this.buffer = session.getBufferPool().acquire(bufferSize,true);
        BufferUtil.flipToFill(buffer);
        this.frame = new WebSocketFrame(OpCode.BINARY);
    }

    private void assertNotClosed() throws IOException
    {
        if (closed)
        {
            throw new IOException("Stream is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException
    {
        assertNotClosed();
        LOG.debug("close()");

        // finish sending whatever in the buffer with FIN=true
        flush(true);

        // close stream
        LOG.debug("Sent Frame Count: {}",frameCount);
        closed = true;
        super.close();
        LOG.debug("closed");
    }

    @Override
    public synchronized void flush() throws IOException
    {
        LOG.debug("flush()");
        assertNotClosed();

        // flush whatever is in the buffer with FIN=false
        flush(false);
        super.flush();
        LOG.debug("flushed");
    }

    /**
     * Flush whatever is in the buffer.
     * 
     * @param fin
     *            fin flag
     * @throws IOException
     */
    private synchronized void flush(boolean fin) throws IOException
    {
        BufferUtil.flipToFlush(buffer,0);
        LOG.debug("flush({}): {}",fin,BufferUtil.toDetailString(buffer));
        frame.setPayload(buffer);
        frame.setFin(fin);

        blocker = new FutureWriteCallback();
        outgoing.outgoingFrame(frame,blocker);
        try
        {
            // block on write
            blocker.get();
            // block success
            frameCount++;
            frame.setOpCode(OpCode.CONTINUATION);
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            if (cause != null)
            {
                if (cause instanceof IOException)
                {
                    throw (IOException)cause;
                }
                else
                {
                    throw new IOException(cause);
                }
            }
            throw new IOException("Failed to flush",e);
        }
        catch (InterruptedException e)
        {
            throw new IOException("Failed to flush",e);
        }
    }

    @Override
    public synchronized void write(byte[] b) throws IOException
    {
        this.write(b,0,b.length);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException
    {
        LOG.debug("write(byte[{}], {}, {})",b.length,off,len);
        int left = len; // bytes left to write
        int offset = off; // offset within provided array
        while (left > 0)
        {
            LOG.debug("buffer: {}",BufferUtil.toDetailString(buffer));
            int space = buffer.remaining();
            int size = Math.min(space,left);
            buffer.put(b,offset,size);
            left -= size; // decrement bytes left
            if (left > 0)
            {
                flush(false);
            }
            offset += size; // increment offset
        }
    }

    @Override
    public synchronized void write(int b) throws IOException
    {
        assertNotClosed();

        // buffer up to limit, flush once buffer reached.
        buffer.put((byte)b);
        if (buffer.remaining() <= 0)
        {
            flush(false);
        }
    }
}
