/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.identity.openidconnect;

import com.nimbusds.jwt.JWTClaimsSet;
import net.minidev.json.JSONArray;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.identity.application.authentication.framework.exception.FrameworkException;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.application.common.model.RoleMapping;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.base.IdentityConstants;
import org.wso2.carbon.identity.base.IdentityException;
import org.wso2.carbon.identity.claim.metadata.mgt.ClaimMetadataHandler;
import org.wso2.carbon.identity.claim.metadata.mgt.exception.ClaimMetadataException;
import org.wso2.carbon.identity.core.util.IdentityCoreConstants;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCache;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCacheEntry;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCacheKey;
import org.wso2.carbon.identity.oauth2.authz.OAuthAuthzReqMessageContext;
import org.wso2.carbon.identity.oauth2.internal.OAuth2ServiceComponentHolder;
import org.wso2.carbon.identity.oauth2.token.OAuthTokenReqMessageContext;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import static org.apache.commons.collections.MapUtils.isEmpty;
import static org.apache.commons.collections.MapUtils.isNotEmpty;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.LOCAL_ROLE_CLAIM_URI;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.ACCESS_TOKEN;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.AUTHZ_CODE;

/**
 * Default implementation of {@link CustomClaimsCallbackHandler}. This callback handler populates available user
 * claims after filtering them through requested scopes.
 */
public class DefaultOIDCClaimsCallbackHandler implements CustomClaimsCallbackHandler {

    private final static Log log = LogFactory.getLog(DefaultOIDCClaimsCallbackHandler.class);
    private final static String INBOUND_AUTH2_TYPE = "oauth2";
    private final static String OIDC_DIALECT = "http://wso2.org/oidc/claim";
    public static final String USER_NOT_FOUND_ERROR_MESSAGE = "UserNotFound";
    private static String userAttributeSeparator = FrameworkUtils.getMultiAttributeSeparator();

    @Override
    public void handleCustomClaims(JWTClaimsSet jwtClaimsSet, OAuthTokenReqMessageContext requestMsgCtx) {
        try {
            Map<String, Object> claims = getUserClaims(requestMsgCtx);
            setClaimsToJwtClaimSet(claims, jwtClaimsSet);
        } catch (OAuthSystemException e) {
            log.error("Error occurred while adding claims of user: " + requestMsgCtx.getAuthorizedUser() +
                    " to the JWTClaimSet used to build the id_token.", e);
        }
    }

    @Override
    public void handleCustomClaims(JWTClaimsSet jwtClaimsSet, OAuthAuthzReqMessageContext authzReqMessageContext) {
        AuthenticatedUser authorizedUser = authzReqMessageContext.getAuthorizationReqDTO().getUser();
        if (log.isDebugEnabled()) {
            log.debug("Adding claims of user: " + authorizedUser + " to the JWTClaimSet used to build the id_token.");
        }

        try {
            Map<String, Object> claims = getUserClaims(authzReqMessageContext);
            setClaimsToJwtClaimSet(claims, jwtClaimsSet);
        } catch (OAuthSystemException e) {
            log.error("Error occurred while adding claims of user: " + authorizedUser + " to the JWTClaimSet used to " +
                    "build the id_token.", e);
        }
    }

    /**
     * Get response map
     *
     * @param requestMsgCtx Token request message context
     * @return Mapped claimed
     * @throws OAuthSystemException
     */
    private Map<String, Object> getUserClaims(OAuthTokenReqMessageContext requestMsgCtx) throws OAuthSystemException {
        // Get any user attributes that were cached against the access token
        // Map<(http://wso2.org/claims/email, email), "peter@example.com">
        Map<ClaimMapping, String> userAttributes = getUserAttributesCachedAgainstToken(getAccessToken(requestMsgCtx));
        if (isEmpty(userAttributes)) {
            userAttributes = getUserAttributesCachedAgainstAuthorizationCode(getAuthorizationCode(requestMsgCtx));
            if (log.isDebugEnabled()) {
                log.debug("Not claims cached against the access_token. Retrieving claims cached against the " +
                        "authorization code.");
            }
        }
        // Map<"email", "peter@example.com">
        Map<String, Object> claims = getClaimMapForUser(requestMsgCtx, userAttributes);
        String spTenantDomain = requestMsgCtx.getOauth2AccessTokenReqDTO().getTenantDomain();
        // Restrict Claims going into the token based on the scope
        return filterClaimsByScope(requestMsgCtx.getScope(), spTenantDomain, claims);
    }

    private String getAuthorizationCode(OAuthTokenReqMessageContext requestMsgCtx) {
        return (String) requestMsgCtx.getProperty(AUTHZ_CODE);
    }

    private String getAccessToken(OAuthTokenReqMessageContext requestMsgCtx) {
        return (String) requestMsgCtx.getProperty(ACCESS_TOKEN);
    }

    private Map<String, Object> getClaimMapForUser(OAuthTokenReqMessageContext requestMsgCtx,
                                                   Map<ClaimMapping, String> userAttributes) {
        Map<String, Object> claimMap = Collections.emptyMap();
        if (isEmpty(userAttributes)) {
            if (!isFederatedUser(requestMsgCtx)) {
                if (log.isDebugEnabled()) {
                    log.debug("No user attributes found in cache for user: " + requestMsgCtx.getAuthorizedUser() + "." +
                            " Retrieving claims from userstore.");
                }
                return retrieveClaimsForLocalUser(requestMsgCtx, claimMap);
            }
        }
        return getClaimsMap(userAttributes);
    }

    private Map<String, Object> retrieveClaimsForLocalUser(OAuthTokenReqMessageContext requestMsgCtx,
                                                           Map<String, Object> claimMap) {
        try {
            claimMap = getClaimsFromUserStore(requestMsgCtx);
            if (log.isDebugEnabled()) {
                log.debug("User attributes not found in cache. Retrieved attributes for local user: " +
                        requestMsgCtx.getAuthorizedUser() + " from userstore.");
            }
        } catch (UserStoreException | IdentityApplicationManagementException | IdentityException e) {
            log.error("Error occurred while getting claims for user: " + requestMsgCtx.getAuthorizedUser() +
                    " from userstore.", e);
        }
        return claimMap;
    }

    private boolean isFederatedUser(OAuthTokenReqMessageContext requestMsgCtx) {
        return requestMsgCtx.getAuthorizedUser().isFederatedUser();
    }

    private Map<ClaimMapping, String> getUserAttributesCachedAgainstAuthorizationCode(Object authorizationCode) {
        Map<ClaimMapping, String> userAttributes = Collections.emptyMap();
        if (authorizationCode != null) {
            // get the cached user claims against the authorization code if any
            userAttributes = getUserAttributesFromCacheUsingCode(authorizationCode.toString());
        }
        return userAttributes;
    }

    private Map<ClaimMapping, String> getUserAttributesCachedAgainstToken(String accessToken) {
        Map<ClaimMapping, String> userAttributes = Collections.emptyMap();
        if (accessToken != null) {
            // get the user claims cached against the access token if any
            userAttributes = getUserAttributesFromCacheUsingToken(accessToken);
        }
        return userAttributes;
    }

    private Map<String, Object> getUserClaims(OAuthAuthzReqMessageContext authzReqMessageContext)
            throws OAuthSystemException {

        Map<String, Object> claims = Collections.emptyMap();
        Map<ClaimMapping, String> userAttributes =
                getUserAttributesCachedAgainstToken(getAccessToken(authzReqMessageContext));

        if (isEmpty(userAttributes)) {
            if (!isFederatedUser(authzReqMessageContext)) {
                claims = getClaimsForLocalUser(authzReqMessageContext, claims);
            } else {
                claims = getClaimsMap(userAttributes);
            }
        } else {
            claims = getClaimsMap(userAttributes);
        }

        String spTenantDomain = authzReqMessageContext.getAuthorizationReqDTO().getTenantDomain();
        return filterClaimsByScope(authzReqMessageContext.getApprovedScope(), spTenantDomain, claims);
    }

    private Map<String, Object> getClaimsForLocalUser(OAuthAuthzReqMessageContext authzReqMessageContext, Map<String, Object> claims) {
        try {
            claims = getClaimsFromUserStore(authzReqMessageContext);
            if (log.isDebugEnabled()) {
                log.debug("User attributes not found in cache. Trying to retrieve attribute for user " +
                        authzReqMessageContext.getAuthorizationReqDTO().getUser());
            }
        } catch (UserStoreException | IdentityApplicationManagementException | IdentityException e) {
            log.error("Error occurred while getting claims for user " +
                    authzReqMessageContext.getAuthorizationReqDTO().getUser(), e);
        }
        return claims;
    }

    private boolean isFederatedUser(OAuthAuthzReqMessageContext authzReqMessageContext) {
        return authzReqMessageContext.getAuthorizationReqDTO().getUser().isFederatedUser();
    }

    private String getAccessToken(OAuthAuthzReqMessageContext authzReqMessageContext) {
        return (String) authzReqMessageContext.getProperty(ACCESS_TOKEN);
    }

    /**
     * Get claims map
     *
     * @param userAttributes User Attributes
     * @return User attribute map
     */
    private Map<String, Object> getClaimsMap(Map<ClaimMapping, String> userAttributes) {

        Map<String, Object> claims = new HashMap<>();
        if (isNotEmpty(userAttributes)) {
            for (Map.Entry<ClaimMapping, String> entry : userAttributes.entrySet()) {
                claims.put(entry.getKey().getRemoteClaim().getClaimUri(), entry.getValue());
            }
        }
        return claims;
    }

    /**
     * Get claims from user store
     *
     * @param tokenReqMessageContext Token request message context
     * @return Users claim map
     * @throws UserStoreException
     * @throws IdentityApplicationManagementException
     * @throws IdentityException
     */
    private Map<String, Object> getClaimsFromUserStore(OAuthTokenReqMessageContext tokenReqMessageContext)
            throws UserStoreException, IdentityApplicationManagementException, IdentityException {

        Map<String, Object> mappedAppClaims = new HashMap<>();
        String spTenantDomain = getServiceProviderTenantDomain(tokenReqMessageContext);

        String clientId = tokenReqMessageContext.getOauth2AccessTokenReqDTO().getClientId();
        ServiceProvider serviceProvider = getServiceProvider(clientId, spTenantDomain);

        if (serviceProvider == null) {
            log.warn("Unable to find a service provider associated with client_id: " + clientId + ". Returning empty " +
                    "claim map for user.");
            return mappedAppClaims;
        }

        ClaimMapping[] requestedLocalClaimMap = serviceProvider.getClaimConfig().getClaimMappings();
        if (ArrayUtils.isEmpty(requestedLocalClaimMap)) {
            if (log.isDebugEnabled()) {
                log.debug("No requested claims configured for service provider: " + serviceProvider.getApplicationName() + ".");
            }
            return mappedAppClaims;
        }

        AuthenticatedUser user = tokenReqMessageContext.getAuthorizedUser();
        String userTenantDomain = user.getTenantDomain();
        String username = user.toString();
        UserRealm realm = IdentityTenantUtil.getRealm(userTenantDomain, username);
        if (realm == null) {
            log.warn("Invalid tenant domain provided. Empty claim returned back for tenant " + userTenantDomain
                    + " and user " + username);
            return mappedAppClaims;
        }

        List<String> claimURIList = getRequestedClaimUris(requestedLocalClaimMap);
        if (log.isDebugEnabled()) {
            log.debug("Requested number of local claims: " + claimURIList.size());
        }

        Map<String, String> userClaims = getUserClaimsMap(username, realm, claimURIList);
        if (isNotEmpty(userClaims)) {
            handleServiceProviderRoleMappings(serviceProvider, userAttributeSeparator, userClaims);

            Map<String, Object> userClaimsInOIDCDialect = getUserClaimsInOIDCDialect(spTenantDomain, username, userClaims);
            mappedAppClaims.putAll(userClaimsInOIDCDialect);
        }

        mappedAppClaims.put(IdentityCoreConstants.MULTI_ATTRIBUTE_SEPARATOR, userAttributeSeparator);
        return mappedAppClaims;
    }

    private Map<String, Object> getUserClaimsInOIDCDialect(String spTenantDomain,
                                                           String username,
                                                           Map<String, String> userClaims) throws ClaimMetadataException {
        if (isEmpty(userClaims)) {
            // User claims can be empty if user does not exist in user stores. Probably a federated user.
            if (log.isDebugEnabled()) {
                log.debug("No claims found for " + username + " from user store.");
            }
            return new HashMap<>();
        } else {
            // Retrieve OIDC to Local Claim Mappings.
            Map<String, String> oidcToLocalClaimMappings = ClaimMetadataHandler.getInstance()
                    .getMappingsMapFromOtherDialectToCarbon(OIDC_DIALECT, null, spTenantDomain, false);

            if (log.isDebugEnabled()) {
                log.debug("Number of user claims retrieved for " + username + " from user store: " + userClaims.size());
            }
            // Get user claims in OIDC dialect
            return getUserClaimsInOidcDialect(oidcToLocalClaimMappings, userClaims);
        }
    }

    private Map<String, String> getUserClaimsMap(String username,
                                                 UserRealm realm,
                                                 List<String> claimURIList) throws FrameworkException, UserStoreException {
        Map<String, String> userClaims = new HashMap<>();
        try {
            userClaims = realm.getUserStoreManager().getUserClaimValues(
                    MultitenantUtils.getTenantAwareUsername(username),
                    claimURIList.toArray(new String[claimURIList.size()]),
                    null);
        } catch (UserStoreException e) {
            if (e.getMessage().contains(USER_NOT_FOUND_ERROR_MESSAGE)) {
                if (log.isDebugEnabled()) {
                    log.debug("User " + username + " not found in user store.");
                }
            } else {
                throw e;
            }
        }
        return userClaims;
    }

    private void handleServiceProviderRoleMappings(ServiceProvider serviceProvider,
                                                   String claimSeparator,
                                                   Map<String, String> userClaims) throws FrameworkException {
        //set local2sp role mappings
        String spMappedRoleClaim = getSpMappedRoleClaim(serviceProvider, userClaims, claimSeparator);
        if (StringUtils.isNotBlank(spMappedRoleClaim)) {
            userClaims.put(LOCAL_ROLE_CLAIM_URI, spMappedRoleClaim);
        }
    }

    private String getSpMappedRoleClaim(ServiceProvider serviceProvider,
                                        Map<String, String> userClaims,
                                        String claimSeparator) throws FrameworkException {
        if (isNotEmpty(userClaims) && userClaims.containsKey(LOCAL_ROLE_CLAIM_URI)) {
            String roleClaim = userClaims.get(LOCAL_ROLE_CLAIM_URI);
            List<String> rolesList = new LinkedList<>(Arrays.asList(roleClaim.split(claimSeparator)));
            return getServiceProviderMappedUserRoles(serviceProvider, rolesList, claimSeparator);
        } else {
            return StringUtils.EMPTY;
        }
    }

    private String getServiceProviderTenantDomain(OAuthTokenReqMessageContext requestMsgCtx) {
        String spTenantDomain = (String) requestMsgCtx.getProperty(MultitenantConstants.TENANT_DOMAIN);
        // There are certain flows where tenant domain is not added as a message context property.
        if (spTenantDomain == null) {
            spTenantDomain = requestMsgCtx.getOauth2AccessTokenReqDTO().getTenantDomain();
        }
        return spTenantDomain;
    }

    /**
     * @param serviceProvider
     * @param locallyMappedUserRoles
     * @return
     */
    private static String getServiceProviderMappedUserRoles(ServiceProvider serviceProvider,
                                                            List<String> locallyMappedUserRoles,
                                                            String claimSeparator) throws FrameworkException {

        if (CollectionUtils.isNotEmpty(locallyMappedUserRoles)) {
            // Get Local Role to Service Provider Role mappings
            RoleMapping[] localToSpRoleMapping = serviceProvider.getPermissionAndRoleConfig().getRoleMappings();

            if (ArrayUtils.isNotEmpty(localToSpRoleMapping)) {
                for (RoleMapping roleMapping : localToSpRoleMapping) {
                    // check whether a local role is mapped to service provider role
                    if (locallyMappedUserRoles.contains(roleMapping.getLocalRole().getLocalRoleName())) {
                        // remove the local role from the list of user roles
                        locallyMappedUserRoles.remove(roleMapping.getLocalRole().getLocalRoleName());
                        // add the service provider mapped role
                        locallyMappedUserRoles.add(roleMapping.getRemoteRole());
                    }
                }
            }
        }

        return StringUtils.join(locallyMappedUserRoles, claimSeparator);
    }

    private Map<String, Object> getClaimsFromUserStore(OAuthAuthzReqMessageContext requestMsgCtx)
            throws IdentityApplicationManagementException, IdentityException, UserStoreException {

        Map<String, Object> mappedAppClaims = new HashMap<>();

        String spTenantDomain = (String) requestMsgCtx.getProperty(MultitenantConstants.TENANT_DOMAIN);

        // There are certain flows where tenant domain is not added as a message context property.
        if (spTenantDomain == null) {
            spTenantDomain = requestMsgCtx.getAuthorizationReqDTO().getTenantDomain();
        }

        String consumerKey = requestMsgCtx.getAuthorizationReqDTO().getConsumerKey();
        ServiceProvider serviceProvider = getServiceProvider(spTenantDomain, consumerKey);
        if (serviceProvider == null) {
            return mappedAppClaims;
        }

        ClaimMapping[] requestedLocalClaimMap = serviceProvider.getClaimConfig().getClaimMappings();
        if (ArrayUtils.isEmpty(requestedLocalClaimMap)) {
            return new HashMap<>();
        }

        AuthenticatedUser user = requestMsgCtx.getAuthorizationReqDTO().getUser();
        String userTenantDomain = user.getTenantDomain();
        String username = user.toString();
        UserRealm realm = IdentityTenantUtil.getRealm(userTenantDomain, username);
        if (realm == null) {
            log.warn("Invalid tenant domain provided. Empty claim returned back for tenant " + userTenantDomain
                    + " and user " + user);
            return new HashMap<>();
        }

        List<String> claimURIList = getRequestedClaimUris(requestedLocalClaimMap);

        if (log.isDebugEnabled()) {
            log.debug("Requested number of local claims: " + claimURIList.size());
        }

        Map<String, String> userClaims = getUserClaimsMap(username, realm, claimURIList);

        if (isEmpty(userClaims)) {
            // User claims can be empty if user does not exist in user stores. Probably a federated user.
            if (log.isDebugEnabled()) {
                log.debug("No claims found for " + username + " from user store.");
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Number of user claims retrieved from user store: " + userClaims.size());
            }

            handleServiceProviderRoleMappings(serviceProvider, userAttributeSeparator, userClaims);

            Map<String, String> spToLocalClaimMappings = ClaimMetadataHandler.getInstance().
                    getMappingsMapFromOtherDialectToCarbon(OIDC_DIALECT, null, spTenantDomain, false);
            // Get user claims in OIDC dialect
            mappedAppClaims.putAll(getUserClaimsInOidcDialect(spToLocalClaimMappings, userClaims));
        }

        String domain = user.getUserStoreDomain();
        RealmConfiguration realmConfiguration = ((org.wso2.carbon.user.core.UserStoreManager) realm
                .getUserStoreManager()).getSecondaryUserStoreManager(domain).getRealmConfiguration();
        String claimSeparator = realmConfiguration.getUserStoreProperty(
                IdentityCoreConstants.MULTI_ATTRIBUTE_SEPARATOR);
        if (StringUtils.isNotBlank(claimSeparator)) {
            mappedAppClaims.put(IdentityCoreConstants.MULTI_ATTRIBUTE_SEPARATOR, claimSeparator);
        }

        return mappedAppClaims;
    }

    private List<String> getRequestedClaimUris(ClaimMapping[] requestedLocalClaimMap) {
        List<String> claimURIList = new ArrayList<>();
        for (ClaimMapping mapping : requestedLocalClaimMap) {
            if (mapping.isRequested()) {
                claimURIList.add(mapping.getLocalClaim().getClaimUri());
            }
        }
        return claimURIList;
    }

    private ServiceProvider getServiceProvider(String spTenantDomain, String consumerKey) throws IdentityApplicationManagementException {
        ApplicationManagementService applicationMgtService = OAuth2ServiceComponentHolder.getApplicationMgtService();
        String spName = applicationMgtService
                .getServiceProviderNameByClientId(consumerKey,
                        INBOUND_AUTH2_TYPE, spTenantDomain);
        return applicationMgtService.getApplicationExcludingFileBasedSPs(spName,
                spTenantDomain);
    }

    /**
     * Get user claims in OIDC claim dialect
     *
     * @param oidcToLocalClaimMappings OIDC dialect to Local dialect claim mappings
     * @param userClaims               User claims in local dialect
     * @return Map of user claim values in OIDC dialect.
     */
    private Map<String, Object> getUserClaimsInOidcDialect(Map<String, String> oidcToLocalClaimMappings,
                                                           Map<String, String> userClaims) {

        Map<String, Object> userClaimsInOidcDialect = new HashMap<>();
        if (isNotEmpty(userClaims)) {
            for (Map.Entry<String, String> claimMapping : oidcToLocalClaimMappings.entrySet()) {
                String value = userClaims.get(claimMapping.getValue());
                if (value != null) {
                    userClaimsInOidcDialect.put(claimMapping.getKey(), value);
                    if (log.isDebugEnabled() &&
                            IdentityUtil.isTokenLoggable(IdentityConstants.IdentityTokens.USER_CLAIMS)) {
                        log.debug("Mapped claim: key - " + claimMapping.getKey() + " value - " + value);
                    }
                }
            }
        }

        return userClaimsInOidcDialect;
    }

    /**
     * Get user attribute cached against the access token
     *
     * @param accessToken Access token
     * @return User attributes cached against the access token
     */
    private Map<ClaimMapping, String> getUserAttributesFromCacheUsingToken(String accessToken) {
        if (log.isDebugEnabled()) {
            if (IdentityUtil.isTokenLoggable(IdentityConstants.IdentityTokens.ACCESS_TOKEN)) {
                log.debug("Retrieving user attributes cached against access token: " + accessToken);
            } else {
                log.debug("Retrieving user attributes cached against access token.");
            }
        }

        AuthorizationGrantCacheKey cacheKey = new AuthorizationGrantCacheKey(accessToken);
        AuthorizationGrantCacheEntry cacheEntry = AuthorizationGrantCache.getInstance()
                .getValueFromCacheByToken(cacheKey);

        return cacheEntry == null ? new HashMap<>() : cacheEntry.getUserAttributes();
    }

    /**
     * Get user attributes cached against the authorization code
     *
     * @param authorizationCode Authorization Code
     * @return User attributes cached against the authorization code
     */
    private Map<ClaimMapping, String> getUserAttributesFromCacheUsingCode(String authorizationCode) {
        if (log.isDebugEnabled()) {
            if (IdentityUtil.isTokenLoggable(IdentityConstants.IdentityTokens.AUTHORIZATION_CODE)) {
                log.debug("Retrieving user attributes cached against authorization code: " + authorizationCode);
            } else {
                log.debug("Retrieving user attributes cached against authorization code.");
            }
        }

        AuthorizationGrantCacheKey cacheKey = new AuthorizationGrantCacheKey(authorizationCode);
        AuthorizationGrantCacheEntry cacheEntry = AuthorizationGrantCache.getInstance()
                .getValueFromCacheByCode(cacheKey);

        return cacheEntry == null ? new HashMap<>() : cacheEntry.getUserAttributes();
    }

    /**
     * Set claims from a Users claims Map object to a JWTClaimsSet object
     *
     * @param claims       Users claims
     * @param jwtClaimsSet JWTClaimsSet object
     */
    private void setClaimsToJwtClaimSet(Map<String, Object> claims, JWTClaimsSet jwtClaimsSet) {
        JSONArray claimValues;
        Object claimSeparator = claims.get(IdentityCoreConstants.MULTI_ATTRIBUTE_SEPARATOR);
        if (claimSeparator != null) {
            String claimSeparatorString = (String) claimSeparator;
            if (StringUtils.isNotBlank(claimSeparatorString)) {
                userAttributeSeparator = (String) claimSeparator;
            }
            claims.remove(IdentityCoreConstants.MULTI_ATTRIBUTE_SEPARATOR);
        }

        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            String value = entry.getValue().toString();
            claimValues = new JSONArray();
            if (userAttributeSeparator != null && value.contains(userAttributeSeparator)) {
                StringTokenizer st = new StringTokenizer(value, userAttributeSeparator);
                while (st.hasMoreElements()) {
                    String attributeValue = st.nextElement().toString();
                    if (StringUtils.isNotBlank(attributeValue)) {
                        claimValues.add(attributeValue);
                    }
                }
                jwtClaimsSet.setClaim(entry.getKey(), claimValues);
            } else {
                jwtClaimsSet.setClaim(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Use to control claims based on the requested scopes and defined scopes in the registry
     *
     * @param requestedScopes             String[] requestedScopes
     * @param serviceProviderTenantDomain String tenantDomain
     * @param userClaims                  Object> claims
     * @return
     */
    private Map<String, Object> filterClaimsByScope(String[] requestedScopes,
                                                    String serviceProviderTenantDomain,
                                                    Map<String, Object> userClaims) {
        return OIDCClaimUtil.getClaimsFilteredByOIDCScopes(serviceProviderTenantDomain, requestedScopes, userClaims);
    }
}
