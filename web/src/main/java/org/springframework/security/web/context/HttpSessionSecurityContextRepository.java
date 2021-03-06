package org.springframework.security.web.context;

import java.lang.reflect.Method;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;

/**
 * A {@code SecurityContextRepository} implementation which stores the security context in the {@code HttpSession}
 * between requests.
 * <p>
 * The {@code HttpSession} will be queried to retrieve the {@code SecurityContext} in the <tt>loadContext</tt>
 * method (using the key {@link #SPRING_SECURITY_CONTEXT_KEY}). If a valid {@code SecurityContext} cannot be
 * obtained from the {@code HttpSession} for whatever reason, a fresh {@code SecurityContext} will be created
 * by calling by {@link SecurityContextHolder#createEmptyContext()} and this instance will be returned instead.
 * <p>
 * When <tt>saveContext</tt> is called, the context will be stored under the same key, provided
 * <ol>
 * <li>The value has changed</li>
 * <li>The configured <tt>AuthenticationTrustResolver</tt> does not report that the contents represent an anonymous
 * user</li>
 * </ol>
 * <p>
 * With the standard configuration, no {@code HttpSession} will be created during <tt>loadContext</tt> if one does
 * not already exist. When <tt>saveContext</tt> is called at the end of the web request, and no session exists, a new
 * {@code HttpSession} will <b>only</b> be created if the supplied {@code SecurityContext} is not equal
 * to an empty {@code SecurityContext} instance. This avoids needless <code>HttpSession</code> creation,
 * but automates the storage of changes made to the context during the request. Note that if
 * {@link SecurityContextPersistenceFilter} is configured to eagerly create sessions, then the session-minimisation
 * logic applied here will not make any difference. If you are using eager session creation, then you should
 * ensure that the <tt>allowSessionCreation</tt> property of this class is set to <tt>true</tt> (the default).
 * <p>
 * If for whatever reason no {@code HttpSession} should <b>ever</b> be created (for example, if
 * Basic authentication is being used or similar clients that will never present the same {@literal jsessionid}), then
 * {@link #setAllowSessionCreation(boolean) allowSessionCreation} should be set to <code>false</code>.
 * Only do this if you really need to conserve server memory and ensure all classes using the
 * {@code SecurityContextHolder} are designed to have no persistence of the {@code SecurityContext}
 * between web requests.
 *
 * @author Luke Taylor
 * @since 3.0
 */
public class HttpSessionSecurityContextRepository implements SecurityContextRepository {
    public static final String SPRING_SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";

    protected final Log logger = LogFactory.getLog(this.getClass());

    private Class<? extends SecurityContext> securityContextClass = null;
    /** SecurityContext instance used to check for equality with default (unauthenticated) content */
    private Object contextObject = SecurityContextHolder.createEmptyContext();
    private boolean cloneFromHttpSession = false;
    private boolean allowSessionCreation = true;
    private boolean disableUrlRewriting = false;

    private AuthenticationTrustResolver authenticationTrustResolver = new AuthenticationTrustResolverImpl();

    /**
     * Gets the security context for the current request (if available) and returns it.
     * <p>
     * If the session is null, the context object is null or the context object stored in the session
     * is not an instance of <tt>SecurityContext</tt>, a new context object will be generated and
     * returned.
     * <p>
     * If <tt>cloneFromHttpSession</tt> is set to true, it will attempt to clone the context object first
     * and return the cloned instance.
     */
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        HttpServletRequest request = requestResponseHolder.getRequest();
        HttpServletResponse response = requestResponseHolder.getResponse();
        HttpSession httpSession = request.getSession(false);

        SecurityContext context = readSecurityContextFromSession(httpSession);

        if (context == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("No SecurityContext was available from the HttpSession: " + httpSession +". " +
                        "A new one will be created.");
            }
            context = generateNewContext();

        }

        requestResponseHolder.setResponse(new SaveToSessionResponseWrapper(response, request,
                httpSession != null, context.hashCode()));

        return context;
    }

    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        SaveContextOnUpdateOrErrorResponseWrapper responseWrapper = (SaveContextOnUpdateOrErrorResponseWrapper)response;
        // saveContext() might already be called by the response wrapper
        // if something in the chain called sendError() or sendRedirect(). This ensures we only call it
        // once per request.
        if (!responseWrapper.isContextSaved() ) {
            responseWrapper.saveContext(context);
        }
    }

    public boolean containsContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (session == null) {
            return false;
        }

        return session.getAttribute(SPRING_SECURITY_CONTEXT_KEY) != null;
    }

    /**
     *
     * @param httpSession the session obtained from the request.
     */
    private SecurityContext readSecurityContextFromSession(HttpSession httpSession) {
        final boolean debug = logger.isDebugEnabled();

        if (httpSession == null) {
            if (debug) {
                logger.debug("No HttpSession currently exists");
            }

            return null;
        }

        // Session exists, so try to obtain a context from it.

        Object contextFromSession = httpSession.getAttribute(SPRING_SECURITY_CONTEXT_KEY);

        if (contextFromSession == null) {
            if (debug) {
                logger.debug("HttpSession returned null object for SPRING_SECURITY_CONTEXT");
            }

            return null;
        }

        // We now have the security context object from the session.
        if (!(contextFromSession instanceof SecurityContext)) {
            if (logger.isWarnEnabled()) {
                logger.warn("SPRING_SECURITY_CONTEXT did not contain a SecurityContext but contained: '"
                        + contextFromSession + "'; are you improperly modifying the HttpSession directly "
                        + "(you should always use SecurityContextHolder) or using the HttpSession attribute "
                        + "reserved for this class?");
            }

            return null;
        }

        // Clone if required (see SEC-356)
        if (cloneFromHttpSession) {
            contextFromSession = cloneContext(contextFromSession);
        }

        if (debug) {
            logger.debug("Obtained a valid SecurityContext from SPRING_SECURITY_CONTEXT: '" + contextFromSession + "'");
        }

        // Everything OK. The only non-null return from this method.

        return (SecurityContext) contextFromSession;
    }

    /**
     *
     * @param context the object which was stored under the security context key in the HttpSession.
     * @return the cloned SecurityContext object. Never null.
     */
    private Object cloneContext(Object context) {
        Object clonedContext = null;
        Assert.isInstanceOf(Cloneable.class, context,
                "Context must implement Cloneable and provide a Object.clone() method");
        try {
            Method m = context.getClass().getMethod("clone", new Class[]{});
            if (!m.isAccessible()) {
                m.setAccessible(true);
            }
            clonedContext = m.invoke(context, new Object[]{});
        } catch (Exception ex) {
            ReflectionUtils.handleReflectionException(ex);
        }

        return clonedContext;
    }

    /**
     * By default, calls {@link SecurityContextHolder#createEmptyContext()} to obtain a new context (there should be
     * no context present in the holder when this method is called). Using this approach the context creation
     * strategy is decided by the {@link SecurityContextHolderStrategy} in use. The default implementations
     * will return a new <tt>SecurityContextImpl</tt>.
     * <p>
     * An alternative way of customizing the <tt>SecurityContext</tt> implementation is by setting the
     * <tt>securityContextClass</tt> property. In this case, the method will attempt to invoke the no-args
     * constructor on the supplied class instead and return the created instance.
     *
     * @return a new SecurityContext instance. Never null.
     */
    SecurityContext generateNewContext() {
        SecurityContext context = null;

        if (securityContextClass == null) {
            context = SecurityContextHolder.createEmptyContext();

            return context;
        }

        try {
            context = securityContextClass.newInstance();
        } catch (Exception e) {
            ReflectionUtils.handleReflectionException(e);
        }
        return context;
    }

    @SuppressWarnings("unchecked")
    @Deprecated
    /**
     * Sets the {@code SecurityContext} implementation class.
     *
     * @deprecated use a custom {@code SecurityContextHolderStrategy} where the {@code createEmptyContext} method
     *      returns the correct implementation.
     */
    public void setSecurityContextClass(Class contextClass) {
        if (contextClass == null || (!SecurityContext.class.isAssignableFrom(contextClass))) {
            throw new IllegalArgumentException("securityContextClass must implement SecurityContext "
                    + "(typically use org.springframework.security.core.context.SecurityContextImpl; existing class is "
                    + contextClass + ")");
        }

        this.securityContextClass = contextClass;
        contextObject = generateNewContext();
    }

    /**
     * Normally, the {@code SecurityContext} retrieved from the session is stored directly in the
     * {@code SecurityContextHolder}, meaning that it is shared between concurrent threads.
     * In this case, if one thread modifies the contents of the context, all threads will see the same
     * change.
     *
     * @param cloneFromHttpSession set to true to clone the security context retrieved from the session.
     *          Defaults to false.
     * @deprecated Override the {@code loadContext} method and copy the created context instead.
     */
    @Deprecated
    public void setCloneFromHttpSession(boolean cloneFromHttpSession) {
        this.cloneFromHttpSession = cloneFromHttpSession;
    }

    /**
     * If set to true (the default), a session will be created (if required) to store the security context if it is
     * determined that its contents are different from the default empty context value.
     * <p>
     * Note that setting this flag to false does not prevent this class from storing the security context. If your
     * application (or another filter) creates a session, then the security context will still be stored for an
     * authenticated user.
     *
     * @param allowSessionCreation
     */
    public void setAllowSessionCreation(boolean allowSessionCreation) {
        this.allowSessionCreation = allowSessionCreation;
    }

    /**
     * Allows the use of session identifiers in URLs to be disabled. Off by default.
     *
     * @param disableUrlRewriting set to <tt>true</tt> to disable URL encoding methods in the response wrapper
     *                            and prevent the use of <tt>jsessionid</tt> parameters.
     */
    public void setDisableUrlRewriting(boolean disableUrlRewriting) {
        this.disableUrlRewriting = disableUrlRewriting;
    }

    //~ Inner Classes ==================================================================================================

    /**
     * Wrapper that is applied to every request/response to update the <code>HttpSession<code> with
     * the <code>SecurityContext</code> when a <code>sendError()</code> or <code>sendRedirect</code>
     * happens. See SEC-398.
     * <p>
     * Stores the necessary state from the start of the request in order to make a decision about whether
     * the security context has changed before saving it.
     */
    final class SaveToSessionResponseWrapper extends SaveContextOnUpdateOrErrorResponseWrapper {

        private HttpServletRequest request;
        private boolean httpSessionExistedAtStartOfRequest;
        private int contextHashBeforeChainExecution;

        /**
         * Takes the parameters required to call <code>saveContext()</code> successfully in
         * addition to the request and the response object we are wrapping.
         *
         * @param request the request object (used to obtain the session, if one exists).
         * @param httpSessionExistedAtStartOfRequest indicates whether there was a session in place before the
         *        filter chain executed. If this is true, and the session is found to be null, this indicates that it was
         *        invalidated during the request and a new session will now be created.
         * @param contextHashBeforeChainExecution the hashcode of the context before the filter chain executed.
         *        The context will only be stored if it has a different hashcode, indicating that the context changed
         *        during the request.
         */
        SaveToSessionResponseWrapper(HttpServletResponse response, HttpServletRequest request,
                                                      boolean httpSessionExistedAtStartOfRequest,
                                                      int contextHashBeforeChainExecution) {
            super(response, disableUrlRewriting);
            this.request = request;
            this.httpSessionExistedAtStartOfRequest = httpSessionExistedAtStartOfRequest;
            this.contextHashBeforeChainExecution = contextHashBeforeChainExecution;
        }

        /**
         * Stores the supplied security context in the session (if available) and if it has changed since it was
         * set at the start of the request. If the AuthenticationTrustResolver identifies the current user as
         * anonymous, then the context will not be stored.
         *
         * @param context the context object obtained from the SecurityContextHolder after the request has
         *        been processed by the filter chain. SecurityContextHolder.getContext() cannot be used to obtain
         *        the context as it has already been cleared by the time this method is called.
         *
         */
        @Override
        protected void saveContext(SecurityContext context) {
            // See SEC-776
            if (authenticationTrustResolver.isAnonymous(context.getAuthentication())) {
                if (logger.isDebugEnabled()) {
                    logger.debug("SecurityContext contents are anonymous - context will not be stored in HttpSession. ");
                }
                return;
            }

            HttpSession httpSession = request.getSession(false);

            if (httpSession == null) {
                httpSession = createNewSessionIfAllowed(context);
            }

            // If HttpSession exists, store current SecurityContextHolder contents but only if
            // the SecurityContext has actually changed (see JIRA SEC-37)
            if (httpSession != null && context.hashCode() != contextHashBeforeChainExecution) {
                httpSession.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);

                if (logger.isDebugEnabled()) {
                    logger.debug("SecurityContext stored to HttpSession: '" + context + "'");
                }
            }
        }

        private HttpSession createNewSessionIfAllowed(SecurityContext context) {
            if (httpSessionExistedAtStartOfRequest) {
                if (logger.isDebugEnabled()) {
                    logger.debug("HttpSession is now null, but was not null at start of request; "
                            + "session was invalidated, so do not create a new session");
                }

                return null;
            }

            if (!allowSessionCreation) {
                if (logger.isDebugEnabled()) {
                    logger.debug("The HttpSession is currently null, and the "
                                    + "HttpSessionContextIntegrationFilter is prohibited from creating an HttpSession "
                                    + "(because the allowSessionCreation property is false) - SecurityContext thus not "
                                    + "stored for next request");
                }

                return null;
            }
            // Generate a HttpSession only if we need to

            if (contextObject.equals(context)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("HttpSession is null, but SecurityContext has not changed from default empty context: ' "
                            + context
                            + "'; not creating HttpSession or storing SecurityContext");
                }

                return null;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("HttpSession being created as SecurityContext is non-default");
            }

            try {
                return request.getSession(true);
            } catch (IllegalStateException e) {
                // Response must already be committed, therefore can't create a new session
                logger.warn("Failed to create a session, as response has been committed. Unable to store" +
                        " SecurityContext.");
            }

            return null;
        }
    }
}
