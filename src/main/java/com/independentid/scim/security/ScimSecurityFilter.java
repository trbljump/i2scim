/*
 * Copyright (c) 2020.
 *
 * Confidential and Proprietary
 *
 * This unpublished source code may not be distributed outside
 * “Independent Identity Org”. without express written permission of
 * Phillip Hunt.
 *
 * People at companies that have signed necessary non-disclosure
 * agreements may only distribute to others in the company that are
 * bound by the same confidentiality agreement and distribution is
 * subject to the terms of such agreement.
 */

package com.independentid.scim.security;

import com.independentid.scim.core.ConfigMgr;
import com.independentid.scim.core.err.ScimException;
import com.independentid.scim.protocol.RequestCtx;
import com.independentid.scim.protocol.ScimResponse;
import io.quarkus.security.identity.SecurityIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

@WebFilter(filterName = "ScimSecurity",urlPatterns = "/*")
public class ScimSecurityFilter implements Filter {
    private final static Logger logger = LoggerFactory.getLogger(ScimSecurityFilter.class);

    @Inject
    ConfigMgr cmgr;

    @Inject
    AccessManager amgr;

    @Inject
    SecurityIdentity identity;

    public void init(FilterConfig filterConfig) throws ServletException {
        logger.info("SCIM Security Filter started.");
        if (!cmgr.isSecurityEnabled())
            logger.warn("\tSecurity filter *disabled*.");
    }

    public void destroy() {

    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (logger.isDebugEnabled())
            logger.debug("\tEvaluating request for: User="+identity.getPrincipal().getName()+", Type="+identity.getPrincipal().getClass().toString());
        if (cmgr.isSecurityEnabled()) {
            RequestCtx ctx = (RequestCtx) request.getAttribute(RequestCtx.REQUEST_ATTRIBUTE);
            if (ctx == null) {
                try {
                    ctx = new RequestCtx((HttpServletRequest) request,(HttpServletResponse) response, cmgr.getSchemaManager());
                    request.setAttribute(RequestCtx.REQUEST_ATTRIBUTE,ctx);
                } catch (ScimException e) {
                    e.printStackTrace();
                }
            }
            assert ctx != null;
            if (amgr.isOperationValid(ctx,identity,(HttpServletRequest) request)) {
                chain.doFilter(request, response);
                return;
            }

            // Process default overrides
            Set<String> roles = identity.getRoles();

            if (roles.contains("root")) {
                if (logger.isDebugEnabled())
                    logger.debug("Allowing request for "+identity.getPrincipal().getName()+" based on root access rights");
                chain.doFilter(request, response);
                return;
            }

            if(roles.contains("full")) {
                if (logger.isDebugEnabled())
                    logger.debug("Allowing request for "+identity.getPrincipal().getName()+" based on \"full\" role.");
                chain.doFilter(request, response);
                return;
            }

            if (response instanceof HttpServletResponse) {
                if (logger.isDebugEnabled())
                    logger.debug("No acis/roles matched for user: "+identity.getPrincipal().getName()+", Scopes Provided: "+roles.toString());
                //No further chain processing
                HttpServletResponse resp = (HttpServletResponse) response;
                if (identity.isAnonymous())
                    resp.setStatus(ScimResponse.ST_UNAUTHORIZED);
                else
                    resp.setStatus(ScimResponse.ST_FORBIDDEN);
                return;
            }
        }
        // Security disabled, pass the request on.

        chain.doFilter(request,response);
    }
}
