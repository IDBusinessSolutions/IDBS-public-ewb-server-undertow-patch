package io.undertow.servlet.extension;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

import org.jboss.logging.Logger;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.conduits.AbstractStreamSourceConduit;
import org.xnio.conduits.StreamSourceConduit;

import io.undertow.server.ConduitWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.util.ConduitFactory;

/**
 * 
 * @author rnaylor
 *         <p>
 *         This is a patch for Wilfdly 8.2 fixing a bug deep within the undertow subsystem and then within XNIO.
 *         According to the WF website the bug has been fixed in Wildfly 10.1 and so should be removed. The code herein
 *         is based a solution proposed by WF architects.
 *         <p>
 *         Here are some reference to the WF issue, including WF web entries for the solution:
 *         <p>
 *         <ul>
 *         <li>Confluence page discussing the bug (first section only):
 *         https://confluence.idbs.co.uk/display/WM/%5BWM-26%5D+SW0032682+App+server+has+rather+high+CPU+and+RAM+usage
 *         <li>Suggested WF fix with discussion: https://issues.jboss.org/browse/UNDERTOW-282
 *         <li>Github version of the code fix: https://gist.github.com/leapingbytes/4041d28abd23d3edb5c2
 *         </ul>
 * 
 *         We suspect the bug is seen at Nexeon - suspect because it cannot be reproduced but stack traces are
 *         similar/the same as those seen on the WF website report. The bug happens because a low level socket read
 *         (within XNIO) fails to terminate correctly and continously reads a null/zero value. This results in the
 *         thread spinning out of control and permanent 100% cpu usage for this thread. On the client server machine
 *         this usually means a jump to 25% cpu for a 4-core system. If/as the problem happens again on another thread
 *         the server machines jumps tp 5-%-75% and finally 100% cpu usage.
 *         <p>
 *         The bug fix adds a ServletExtension to the system that in executed for every socket. The fix detects a
 *         multiple zero reads and timesout the server connection after 5 seconds of zero reads. Thus the CPU is not
 *         taken out forever.
 *         <p>
 *         There is a lot of logging added so that we can see what happens, also the fix can be deactivated using a JVM
 *         arg - stopping the ServletExtension from being wrapped in the deployed application
 * 
 *         <p>
 *         This code has been developed using code snippets made available from contributors of the JBossDeveloper and
 *         github Undertow project (see references below). The code snippets are offered without restriction and the
 *         Undertow project as a whole is available under the Apache 2.0 license. References:</br>
 *         <ul>
 *         <li>Confluence page discussing the bug (first section only):
 *         https://confluence.idbs.co.uk/display/WM/%5BWM-26%5D+SW0032682+App+server+has+rather+high+CPU+and+RAM+usage
 *         <li>Suggested WF fix with discussion: https://issues.jboss.org/browse/UNDERTOW-282
 *         <li>Github version of the code fix: https://gist.github.com/leapingbytes/4041d28abd23d3edb5c2
 *         </ul>
 * 
 */

public class SslZeroReadFixHandler implements HttpHandler
{
    private static final Logger logger = Logger
            .getLogger(SslZeroReadFixServletExtension.UNDERTOW_SSL_FIX_LOGGER_STRING);

    static final long ZERO_READ_TIMEOUT_PERIOD = Long
            .getLong("io.undertow.servlet.extension.ssl_zero_read_fix.zero_read_timeout_period", (5 * 1000l));
    // default 5 secs

    private HttpHandler handler = null;

    public SslZeroReadFixHandler(HttpHandler handler)
    {
        this.handler = handler;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception
    {
        if (!exchange.isRequestComplete())
        {
            logHelper(LOG_HELPER.LOG_DEBUG_ADDING_REQUEST_WRAPPER, exchange.toString());

            exchange.addRequestWrapper(new ConduitWrapper<StreamSourceConduit>()
            {
                @Override
                public StreamSourceConduit wrap(ConduitFactory<StreamSourceConduit> factory,
                        HttpServerExchange exchange)
                {
                    return new SslFixStreamSourceConduit(factory, exchange);
                }
            });
        }
        handler.handleRequest(exchange);
    }

    /*
     * Package access for unit tests
     */
    static class SslFixStreamSourceConduit extends AbstractStreamSourceConduit<StreamSourceConduit>
    {
        static final boolean CHECK_COUNT = Boolean
                .getBoolean("io.undertow.servlet.extension.ssl_zero_read_fix.check_count"); // default false

        static final int MAX_ZERO_READ_COUNT = Integer
                .getInteger("io.undertow.servlet.extension.ssl_zero_read_fix.max_zero_read_count", 20);

        private final HttpServerExchange exchange;

        private final ServerConnection serverConnection;

        private int zeroCount = 0;

        private long lastNonZeroTime = 0L;

        public SslFixStreamSourceConduit(ConduitFactory<StreamSourceConduit> factory, HttpServerExchange exchange)
        {
            super(factory.create());
            this.exchange = exchange;
            this.serverConnection = exchange.getConnection();

            logHelper(LOG_HELPER.LOG_DEBUG_CTOR);
        }

        @Override
        public long transferTo(long position, long count, FileChannel target) throws IOException
        {
            long ret = super.transferTo(position, count, target);
            logHelper(LOG_HELPER.LOG_DEBUG_IN_TRANSFER_TO_METHOD_01, ret);
            handleReturnValue(ret);
            return ret;
        }

        @Override
        public long transferTo(long count, ByteBuffer throughBuffer, StreamSinkChannel target) throws IOException
        {
            long ret = super.transferTo(count, throughBuffer, target);
            logHelper(LOG_HELPER.LOG_DEBUG_IN_TRANSFER_TO_METHOD_02, ret);
            handleReturnValue(ret);
            return ret;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException
        {
            int ret = super.read(dst);
            logHelper(LOG_HELPER.LOG_DEBUG_IN_READ_METHOD_01, ret);
            handleReturnValue(ret);
            return ret;
        }

        @Override
        public long read(ByteBuffer[] dsts, int offs, int len) throws IOException
        {
            long ret = super.read(dsts, offs, len);
            logHelper(LOG_HELPER.LOG_DEBUG_IN_READ_METHOD_02, ret);
            handleReturnValue(ret);
            return ret;
        }

        /*
         * Check for zero reads. If we get a zero read we "start" a counter and timer - checking to see if the read has
         * timed-out (counted-out). It is a fundamental part of the design of this bug fix that this instance of the
         * Handler will be called again for the next read on the input stream - hence if multiple zero reads persist we
         * can trap this and terminate the reading (and thus the infinite loop).
         */
        private void handleReturnValue(long ret) throws IOException
        {
            // no point in moving this System.currentTimeMillis() as it has to happen in both forks of the if statement
            // below
            long nowTime = System.currentTimeMillis();

            /*
             * the very first time through we are ignoring the the redValue and simply setting the time of last read.
             * This is OK because when the bug happens the zero value reads will be passed to this method thick and
             * fast...
             */
            if (lastNonZeroTime == 0l || ret > 0)
            {
                // first time through ... set the lastNonZeroTime ready for next time through (if the bug is happening)
                lastNonZeroTime = nowTime;
                zeroCount = 0;
            }
            else if (ret == 0)
            {
                this.zeroCount++;

                // we have the possibility of the bug, i.e. a zero read, do the test to see if will to terminate the
                // reads...
                if (((lastNonZeroTime + ZERO_READ_TIMEOUT_PERIOD < nowTime)
                    || (CHECK_COUNT && (zeroCount > MAX_ZERO_READ_COUNT))))
                {
                    try
                    {
                        logHelper(LOG_HELPER.LOG_TERMINATE_READS, zeroCount, exchange);
                        terminateReads();
                    }
                    catch (IOException e)
                    {
                        logHelper(LOG_HELPER.LOG_IO_EXCEPTION, "terminateRead()", e);
                        throw e;
                    }
                    finally
                    {
                        try
                        {
                            serverConnection.close();
                        }
                        catch (IOException e)
                        {
                            logHelper(LOG_HELPER.LOG_IO_EXCEPTION, "serverConnection.close()", e);
                            throw e;
                        }
                    }
                }
            }
        }

    }

    public enum LOG_HELPER {
        LOG_TERMINATE_READS, LOG_IO_EXCEPTION, LOG_DEBUG_IN_READ_METHOD_01, LOG_DEBUG_IN_READ_METHOD_02, LOG_DEBUG_IN_TRANSFER_TO_METHOD_01, LOG_DEBUG_IN_TRANSFER_TO_METHOD_02, LOG_DEBUG_CTOR, LOG_DEBUG_ADDING_REQUEST_WRAPPER, LOG_UNDERTOW_282_FIX_ACTIVATED, LOG_UNDERTOW_282_FIX_DEACTIVATED;
    }

    public static void logHelper(LOG_HELPER logReason, Object... arg)
    {

        switch (logReason)
        {
        case LOG_TERMINATE_READS:
        {
            logger.info("UNDERTOW-282 DETECTED. Remedial action will be taken to correct the problem.");
            logger.info("Connection will be closed due to excessive zero reads/timeout reached. Zero read count: "
                + arg[0] + ", timeout period:  " + (ZERO_READ_TIMEOUT_PERIOD / 1000) + " secs. Exchange info: "
                + ((HttpServerExchange)arg[1]).toString());
            logger.debug(
                "UNDERTOW-282 detected. Stack trace is:\n " + Arrays.toString(Thread.currentThread().getStackTrace()));
            break;
        }
        case LOG_IO_EXCEPTION:
        {
            logger.debug(
                "Got IOException during " + arg[0] + " when trying to stop zero read bug. Underlying problem is: ",
                (Exception)arg[1]);

            break;
        }
        case LOG_DEBUG_IN_TRANSFER_TO_METHOD_01:
        {
            logger.debug(
                "In transferTo(long position, long count, FileChannel target), evaluating read value [" + arg[0] + "]");
            break;
        }
        case LOG_DEBUG_IN_TRANSFER_TO_METHOD_02:
        {
            logger.debug(
                "In transferTo(long count, ByteBuffer throughBuffer, StreamSinkChannel target), evaluating read value ["
                    + arg[0] + "]");
            break;
        }
        case LOG_DEBUG_IN_READ_METHOD_01:
        {
            logger.debug("In read(ByteBuffer dst), evaluating read value [" + arg[0] + "]");
            break;
        }
        case LOG_DEBUG_IN_READ_METHOD_02:
        {
            logger.debug("In read(ByteBuffer[] dsts, int offs, int len), evaluating read value [" + arg[0] + "]");
            break;
        }
        case LOG_DEBUG_CTOR:
        {
            logger.debug("SslFixStreamSourceConduit being utilised");
            break;
        }
        case LOG_DEBUG_ADDING_REQUEST_WRAPPER:
        {
            logger.debug("Adding ConduitWrapper for SSL fix to exchange: " + arg[0]);
            break;
        }
        case LOG_UNDERTOW_282_FIX_ACTIVATED:
        {
            logger.info("UNDERTOW-282 fix activated. Adding " + SslZeroReadFixHandler.class.getName()
                + " as a ServletExtenson to " + arg[0]);

            logger.debug("io.undertow.servlet.extension.ssl_zero_read_fix.disabled = "
                + Boolean.getBoolean("io.undertow.servlet.extension.ssl_zero_read_fix.disabled"));
            logger.debug("io.undertow.servlet.extension.ssl_zero_read_fix.zero_read_timeout_period = "
                + ZERO_READ_TIMEOUT_PERIOD + " milli sec");
            logger.debug("io.undertow.servlet.extension.ssl_zero_read_fix.check_count = "
                + Boolean.getBoolean("io.undertow.servlet.extension.ssl_zero_read_fix.check_count"));
            logger.debug("io.undertow.servlet.extension.ssl_zero_read_fix.max_zero_read_count = "
                + Integer.getInteger("io.undertow.servlet.extension.ssl_zero_read_fix.max_zero_read_count", 20));

            break;
        }
        case LOG_UNDERTOW_282_FIX_DEACTIVATED:
        {
            logger.debug("UNDERTOW-282 fix NOT activated for " + arg[0]);
            break;
        }
        default:
        {
            logger.info("calling logging method with unknown switch value of " + logReason);
        }

        } // end switch
    }

}