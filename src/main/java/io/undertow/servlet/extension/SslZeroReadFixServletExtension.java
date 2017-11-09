package io.undertow.servlet.extension;

import javax.servlet.ServletContext;

import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.servlet.ServletExtension;
import io.undertow.servlet.api.DeploymentInfo;

/**
 * 
 * @author rnaylor
 *
 *         For comments on how this class work please see the associated Handler class:
 * @see SslZeroReadFixHandler
 * 
 *      <p>
 *      This code has been developed using code snippets made available from contributors of the JBossDeveloper and
 *      github Undertow project (see references below). The code snippets are offered without restriction and the
 *      Undertow project as a whole is available under the Apache 2.0 license. References:</br>
 *      <ul>
 *      <li>Confluence page discussing the bug (first section only):
 *      https://confluence.idbs.co.uk/display/WM/%5BWM-26%5D+SW0032682+App+server+has+rather+high+CPU+and+RAM+usage
 *      <li>Suggested WF fix with discussion: https://issues.jboss.org/browse/UNDERTOW-282
 *      <li>Github version of the code fix: https://gist.github.com/leapingbytes/4041d28abd23d3edb5c2
 *      </ul>
 * 
 */
public class SslZeroReadFixServletExtension implements ServletExtension
{
    public static final String UNDERTOW_SSL_FIX_LOGGER_STRING = "UndertowSslFixLoggerString";

    // a jvm arg that allows this whole ssl zero read to be disabled if required - defaults to false meaning the fix IS
    // enabled
    private static final boolean SSL_ZERO_READ_FIX_DISABLED = Boolean
            .getBoolean("io.undertow.servlet.extension.ssl_zero_read_fix.disabled"); // default false, so fix will be
                                                                                     // activated

    @Override
    public void handleDeployment(final DeploymentInfo deploymentInfo, final ServletContext servletContext)
    {
        // note: the fix is ON by default - we have to supply a jvm arg to switch it off
        if (!SSL_ZERO_READ_FIX_DISABLED)
        {
            SslZeroReadFixHandler.logHelper(SslZeroReadFixHandler.LOG_HELPER.LOG_UNDERTOW_282_FIX_ACTIVATED,
                deploymentInfo.getDeploymentName());
            deploymentInfo.addInitialHandlerChainWrapper(new HandlerWrapper()
            {
                @Override
                public HttpHandler wrap(final HttpHandler handler)
                {
                    return new SslZeroReadFixHandler(handler);
                }
            });
        }
        else
        {
            SslZeroReadFixHandler.logHelper(SslZeroReadFixHandler.LOG_HELPER.LOG_UNDERTOW_282_FIX_DEACTIVATED,
                deploymentInfo.getDeploymentName());
        }
    }
}