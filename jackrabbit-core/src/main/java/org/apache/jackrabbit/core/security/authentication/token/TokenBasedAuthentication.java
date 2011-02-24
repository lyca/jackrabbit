/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.security.authentication.token;

import org.apache.jackrabbit.api.security.authentication.token.TokenCredentials;
import org.apache.jackrabbit.api.security.principal.ItemBasedPrincipal;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.security.authentication.Authentication;
import org.apache.jackrabbit.spi.Name;
import org.apache.jackrabbit.util.ISO8601;
import org.apache.jackrabbit.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.Credentials;
import javax.jcr.InvalidItemStateException;
import javax.jcr.ItemExistsException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.version.VersionException;
import java.security.Principal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Authentication implementation that compares the tokens stored with a
 * given user node to the token present in the SimpleCredentials attributes.
 * Authentication succeeds if the login token refers to a non-expired
 * token node and if all other credential attributes are equal to the
 * corresponding properties.
 */
public class TokenBasedAuthentication implements Authentication {

    private static final Logger log = LoggerFactory.getLogger(TokenBasedAuthentication.class);

    /**
     * Default expiration time for login tokens is 2 hours.
     */
    public static final long TOKEN_EXPIRATION = 2 * 3600 * 1000;

    /**
     * The name of the login token attribute.
     */
    public static final String TOKEN_ATTRIBUTE = ".token";

    private static final String TOKEN_ATTRIBUTE_EXPIRY = TOKEN_ATTRIBUTE + ".exp";
    private static final String TOKENS_NODE_NAME = ".tokens";
    private static final String TOKENS_NT_NAME = "nt:unstructured"; // TODO: configurable


    private final String token;
    private final long tokenExpiration;
    private final Session session;

    private final Map<String, String> attributes;
    private final Map<String, String> info;
    private final long expiry;

    public TokenBasedAuthentication(String token, long tokenExpiration, Session session) throws RepositoryException {
        this.session = session;
        this.tokenExpiration = tokenExpiration;
        this.token = token;
        long expTime = Long.MAX_VALUE;
        if (token != null) {
            attributes = new HashMap<String, String>();
            info = new HashMap<String, String>();

            Node n = session.getNodeByIdentifier(token);
            PropertyIterator it = n.getProperties();
            while (it.hasNext()) {
                Property p = it.nextProperty();
                String name = p.getName();
                if (TOKEN_ATTRIBUTE_EXPIRY.equals(name)) {
                    expTime = p.getLong();
                } else if (isMandatoryAttribute(name)) {
                    attributes.put(name, p.getString());
                } else if (isInfoAttribute(name)) {
                    info.put(name, p.getString());
                } // else: jcr property -> ignore
            }
        } else {
            attributes = Collections.emptyMap();
            info = Collections.emptyMap();
        }
        expiry = expTime;
    }

    /**
     * @see Authentication#canHandle(javax.jcr.Credentials)
     */
    public boolean canHandle(Credentials credentials) {
        return token != null && isTokenBasedLogin(credentials);
    }

    /**
     * @see Authentication#authenticate(javax.jcr.Credentials)
     */
    public boolean authenticate(Credentials credentials) throws RepositoryException {
        if (!(credentials instanceof TokenCredentials)) {
            throw new RepositoryException("TokenCredentials expected. Cannot handle " + credentials.getClass().getName());
        }
        TokenCredentials tokenCredentials = (TokenCredentials) credentials;

        // credentials without userID -> check if attributes provide
        // sufficient information for successful authentication.
        if (token.equals(tokenCredentials.getToken())) {
            long loginTime = new Date().getTime();
            // test if the token has already expired
            if (expiry < loginTime) {
                // already expired -> login fails.
                // ... remove the expired token node before aborting the login
                removeToken();
                return false;
            }
            // check if all other required attributes match
            for (String name : attributes.keySet()) {
                if (!attributes.get(name).equals(tokenCredentials.getAttribute(name))) {
                    // no match -> login fails.
                    return false;
                }
            }

            // update set of informative attributes on the credentials
            // based on the properties present on the token node.
            Collection<String> attrNames = Arrays.asList(tokenCredentials.getAttributeNames());
            for (String key : info.keySet()) {
                if (!attrNames.contains(key)) {
                    tokenCredentials.setAttribute(key, info.get(key));
                }
            }
            // collect those attributes present on the credentials that
            // are missing or different in the token node.
            Map<String, String> newAttributes = new HashMap<String,String>(attrNames.size());
            for (String attrName : tokenCredentials.getAttributeNames()) {
                String attrValue = tokenCredentials.getAttribute(attrName);
                if (!isMandatoryAttribute(attrName) &&
                        (!info.containsKey(attrName) || !info.get(attrName).equals(attrValue))) {
                    newAttributes.put(attrName, attrValue);
                }
            }

            // update token node if required: optionally resetting the
            // expiration and the set of informative properties
            updateTokenNode(expiry, loginTime, newAttributes);

            return true;
        }

        // wrong credentials that cannot be compared by this authentication
        return false;
    }

    /**
     * Performs the following checks/updates:
     * <ol>
     * <li>Reset the expiration if half of the expiration has passed in order to
     * minimize write operations (avoid resetting upon each login).</li>
     * <li>Update the token node to reflect the set of new/changed informative
     * attributes provided by the credentials.</li>
     * </ol>
     *
     * @param tokenExpiry
     * @param loginTime
     * @param newAttributes
     */
    private void updateTokenNode(long tokenExpiry, long loginTime, Map<String,String> newAttributes) {
        Node tokenNode = null;
        Session s = null;
        try {
            // a) expiry...
            if (tokenExpiry - loginTime <= tokenExpiration/2) {
                long expirationTime = loginTime + tokenExpiration;
                Calendar cal = GregorianCalendar.getInstance();
                cal.setTimeInMillis(expirationTime);

                tokenNode = getTokenNode();
                s = tokenNode.getSession();
                tokenNode.setProperty(TOKEN_ATTRIBUTE_EXPIRY, s.getValueFactory().createValue(cal));
            }

            // b) handle informative attributes...
            if (!newAttributes.isEmpty()) {
                if (tokenNode == null) {
                    tokenNode = getTokenNode();
                    s = tokenNode.getSession();
                }
                for (String attrName : newAttributes.keySet()) {
                    tokenNode.setProperty(attrName, newAttributes.get(attrName));
                    log.info("Updating token node with informative attribute '" + attrName + "'");
                }
            }

            if (s != null) {
                s.save();
            }
        } catch (RepositoryException e) {
            log.warn("Failed to update expiry or informative attributes of token node.", e);
        } finally {
            if (s != null) {
                s.logout();
            }
        }
    }

    /**
     * Remove the node associated with the expired token defined by this TokenBasedAuthentication.
     */
    private void removeToken() {
        Session s = null;
        try {
            Node tokenNode = getTokenNode();
            s = tokenNode.getSession();
            
            tokenNode.remove();
            s.save();
        } catch (RepositoryException e) {
            log.warn("Internal error while removing token node.", e);
        } finally {
            if (s != null) {
                s.logout();
            }
        }
    }

    /**
     * Retrieve the token node using another session to avoid concurrent write
     * operations with the shared system session.
     *
     * @return the token node
     * @throws RepositoryException
     * @throws AccessDeniedException
     */
    private Node getTokenNode() throws RepositoryException, AccessDeniedException {
        Session s = ((SessionImpl) session).createSession(session.getWorkspace().getName());
        return s.getNodeByIdentifier(token);
    }

    //--------------------------------------------------------------------------
    /**
     * Returns <code>true</code> if the given <code>credentials</code> object
     * is an instance of <code>TokenCredentials</code>.
     *
     * @param credentials
     * @return <code>true</code> if the given <code>credentials</code> object
     * is an instance of <code>TokenCredentials</code>; <code>false</code> otherwise.
     */
    public static boolean isTokenBasedLogin(Credentials credentials) {
        return credentials instanceof TokenCredentials;
    }

    /**
     * Returns <code>true</code> if the specified <code>attributeName</code>
     * starts with or equals {@link #TOKEN_ATTRIBUTE}.
     *  
     * @param attributeName
     * @return <code>true</code> if the specified <code>attributeName</code>
     * starts with or equals {@link #TOKEN_ATTRIBUTE}.
     */
    public static boolean isMandatoryAttribute(String attributeName) {
        return attributeName != null && attributeName.startsWith(TOKEN_ATTRIBUTE);
    }

    /**
     * Returns <code>false</code> if the specified attribute name doesn't have
     * a 'jcr' or 'rep' namespace prefix; <code>true</code> otherwise. This is
     * a lazy evaluation in order to avoid testing the defining node type of
     * the associated jcr property.
     *
     * @param propertyName
     * @return <code>true</code> if the specified property name doesn't seem
     * to represent repository internal information.
     */
    private static boolean isInfoAttribute(String propertyName) {
        String prefix = Text.getNamespacePrefix(propertyName);
        return !Name.NS_JCR_PREFIX.equals(prefix) && !Name.NS_REP_PREFIX.equals(prefix);
    }

    /**
     * Returns <code>true</code> if the specified <code>credentials</code>
     * should be used to create a new login token.
     *
     * @param credentials
     * @return <code>true</code> if upon successful authentication a new
     * login token should be created; <code>false</code> otherwise.
     */
    public static boolean doCreateToken(Credentials credentials) {
        if (credentials instanceof SimpleCredentials) {
            Object attr = ((SimpleCredentials) credentials).getAttribute(TOKEN_ATTRIBUTE);
            return (attr != null && "".equals(attr.toString()));
        }
        return false;
    }

    /**
     * Create a new token node for the specified user.
     *
     * @param user
     * @param credentials
     * @param tokenExpiration
     * @param session
     * @return A new instance of <code>TokenCredentials</code> to be used for
     * further login actions against this Authentication implementation.
     * @throws RepositoryException If there is no node corresponding to the
     * specified user in the current workspace or if an error occurs while
     * creating the token node.
     */
    public synchronized static Credentials createToken(User user, SimpleCredentials credentials,
                                                long tokenExpiration, Session session) throws RepositoryException {
        String workspaceName = session.getWorkspace().getName();
        if (user == null) {
            throw new RepositoryException("Cannot create login token: No corresponding node for 'null' user in workspace '" + workspaceName + "'.");
        }
        String userPath = null;
        Principal pr = user.getPrincipal();
        if (pr instanceof ItemBasedPrincipal) {
            userPath = ((ItemBasedPrincipal) pr).getPath();
        }

        TokenCredentials tokenCredentials;
        if (userPath != null && session.nodeExists(userPath)) {
            Node userNode = session.getNode(userPath);
            Node tokenParent;
            if (userNode.hasNode(TOKENS_NODE_NAME)) {
                tokenParent = userNode.getNode(TOKENS_NODE_NAME);
            } else {
                tokenParent = userNode.addNode(TOKENS_NODE_NAME, TOKENS_NT_NAME);
            }

            long creationTime = new Date().getTime();
            long expirationTime = creationTime + tokenExpiration;

            Calendar cal = GregorianCalendar.getInstance();
            cal.setTimeInMillis(creationTime);

            String tokenName = Text.replace(ISO8601.format(cal), ":", ".");
            Node tokenNode = tokenParent.addNode(tokenName);

            String token = tokenNode.getIdentifier();
            tokenCredentials = new TokenCredentials(token);
            credentials.setAttribute(TOKEN_ATTRIBUTE, token);

            // add expiration time property
            cal.setTimeInMillis(expirationTime);
            tokenNode.setProperty(TOKEN_ATTRIBUTE_EXPIRY, session.getValueFactory().createValue(cal));

            // add additional attributes passed in by the credentials.
            for (String name : credentials.getAttributeNames()) {
                if (!TOKEN_ATTRIBUTE.equals(name)) {
                    String value = credentials.getAttribute(name).toString();
                    tokenNode.setProperty(name, value);
                    tokenCredentials.setAttribute(name, value);
                }
            }
            session.save();
            return tokenCredentials;
        } else {
            throw new RepositoryException("Cannot create login token: No corresponding node for User " + user.getID() +" in workspace '" + workspaceName + "'.");
        }
    }
}