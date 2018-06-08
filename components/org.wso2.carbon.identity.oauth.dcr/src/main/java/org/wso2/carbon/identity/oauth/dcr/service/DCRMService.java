/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.oauth.dcr.service;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationConfig;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationRequestConfig;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.model.User;
import org.wso2.carbon.identity.oauth.IdentityOAuthAdminException;
import org.wso2.carbon.identity.oauth.OAuthAdminService;
import org.wso2.carbon.identity.oauth.common.OAuthConstants;
import org.wso2.carbon.identity.oauth.common.exception.InvalidOAuthClientException;
import org.wso2.carbon.identity.oauth.dcr.DCRMConstants;
import org.wso2.carbon.identity.oauth.dcr.bean.Application;
import org.wso2.carbon.identity.oauth.dcr.bean.ApplicationRegistrationRequest;
import org.wso2.carbon.identity.oauth.dcr.bean.ApplicationUpdateRequest;
import org.wso2.carbon.identity.oauth.dcr.exception.DCRMException;
import org.wso2.carbon.identity.oauth.dcr.internal.DCRDataHolder;
import org.wso2.carbon.identity.oauth.dcr.util.DCRConstants;
import org.wso2.carbon.identity.oauth.dcr.util.DCRMUtils;
import org.wso2.carbon.identity.oauth.dcr.util.ErrorCodes;
import org.wso2.carbon.identity.oauth.dto.OAuthConsumerAppDTO;

import java.util.ArrayList;
import java.util.List;


/**
 * DCRMService service is used to manage OAuth2/OIDC application registration.
 */
public class DCRMService {
    private static final Log log = LogFactory.getLog(DCRMService.class);
    private static OAuthAdminService oAuthAdminService = new OAuthAdminService();

    private static final String AUTH_TYPE_OAUTH_2 = "oauth2";
    private static final String OAUTH_VERSION = "OAuth-2.0";
    private static final String GRANT_TYPE_SEPARATOR = " ";

    /**
     * Get OAuth2/OIDC application information with client_id
     * @param clientId client_id of the application
     * @return
     * @throws DCRMException
     */
    public Application getApplication(String clientId) throws DCRMException {
        return buildResponse(getApplicationById(clientId));
    }

    /**
     * Create OAuth2/OIDC application
     * @param registrationRequest
     * @return
     * @throws DCRMException
     */
    public Application registerApplication(ApplicationRegistrationRequest registrationRequest) throws DCRMException {
        return createOAuthApplication(registrationRequest);
    }

    /**
     * Delete OAuth2/OIDC application with client_id
     * @param clientId
     * @throws DCRMException
     */
    public void deleteApplication(String clientId) throws DCRMException {

        OAuthConsumerAppDTO appDTO = getApplicationById(clientId);
        String applicationOwner = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
        deleteServiceProvider(appDTO.getApplicationName(), tenantDomain, applicationOwner);
    }

    /**
     * Update OAuth/OIDC application
     * @param updateRequest
     * @param clientId
     * @return
     * @throws DCRMException
     */
    public Application updateApplication(ApplicationUpdateRequest updateRequest, String clientId) throws DCRMException {

        OAuthConsumerAppDTO appDTO = getApplicationById(clientId);
        String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
        String applicationOwner = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        String clientName = updateRequest.getClientName();

        // Update Service Provider
        ServiceProvider sp = getServiceProvider(appDTO.getApplicationName(), tenantDomain);
        if (StringUtils.isNotEmpty(clientName)) {
            // Regex validation of the application name.
            if (!DCRMUtils.isRegexValidated(clientName)) {
                throw new DCRMException("The Application name: " + clientName + " is not valid! It is not adhering to" +
                        " the regex: " + DCRConstants.APP_NAME_VALIDATING_REGEX);
            }
            sp.setApplicationName(clientName);
            updateServiceProvider(sp, tenantDomain, applicationOwner);
        }

        // Update application
        try {
            if (StringUtils.isNotEmpty(clientName)) {
                // Regex validation of the application name.
                if (!DCRMUtils.isRegexValidated(clientName)) {
                    throw new DCRMException("The Application name: " + clientName + " is not valid! It is not adhering to" +
                            " the regex: " + DCRConstants.APP_NAME_VALIDATING_REGEX);
                }
                appDTO.setApplicationName(clientName);
            }
            if (!updateRequest.getGrantTypes().isEmpty()) {
                String grantType = StringUtils.join(updateRequest.getGrantTypes(), GRANT_TYPE_SEPARATOR);
                appDTO.setGrantTypes(grantType);
            }
            if (!updateRequest.getRedirectUris().isEmpty()) {
                String callbackUrl = validateAndSetCallbackURIs(updateRequest.getRedirectUris(), updateRequest.getGrantTypes());
                appDTO.setCallbackUrl(callbackUrl);
            }

            if(updateRequest.getTokenType() != null) {
                appDTO.setTokenType(updateRequest.getTokenType());
            }
            oAuthAdminService.updateConsumerApplication(appDTO);
        } catch (IdentityOAuthAdminException e) {
            throw DCRMUtils.generateServerException(
                    DCRMConstants.ErrorMessages.FAILED_TO_UPDATE_APPLICATION, clientId, e);
        }

        return buildResponse(getApplicationById(clientId));
    }

    private OAuthConsumerAppDTO getApplicationById(String clientId) throws DCRMException {
        if (StringUtils.isEmpty(clientId)) {
            String errorMessage = "Invalid client_id";
            throw DCRMUtils.generateClientException(
                    DCRMConstants.ErrorMessages.BAD_REQUEST_INVALID_INPUT, errorMessage);
        }
        try {
            OAuthConsumerAppDTO dto = oAuthAdminService.getOAuthApplicationData(clientId);
            if (dto == null || StringUtils.isEmpty(dto.getApplicationName())) {
                throw DCRMUtils.generateClientException(DCRMConstants.ErrorMessages.NOT_FOUND_APPLICATION_WITH_ID, clientId);
            }
            return dto;
        } catch (IdentityOAuthAdminException e) {
            if (e.getCause() instanceof InvalidOAuthClientException) {
                throw DCRMUtils.generateClientException(DCRMConstants.ErrorMessages.NOT_FOUND_APPLICATION_WITH_ID, clientId);
            }
            throw DCRMUtils.generateServerException(
                    DCRMConstants.ErrorMessages.FAILED_TO_GET_APPLICATION_BY_ID, clientId, e);
        }
    }

    private Application createOAuthApplication(ApplicationRegistrationRequest registrationRequest)
            throws DCRMException {

        String applicationOwner = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        String tenantDomain = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();
        String spName = registrationRequest.getClientName();

        // Regex validation of the application name.
        if (!DCRMUtils.isRegexValidated(spName)) {
            throw new DCRMException("The Application name: " + spName + " is not valid! It is not adhering to" +
                    " the regex: " + DCRConstants.APP_NAME_VALIDATING_REGEX);
        }

        // Check whether a service provider already exists for the name we are trying to register the OAuth app with.
        if (isServiceProviderExist(spName, tenantDomain)) {
            throw DCRMUtils.generateClientException(DCRMConstants.ErrorMessages.CONFLICT_EXISTING_APPLICATION, spName);
        }

        // Create a service provider.
        ServiceProvider serviceProvider = createServiceProvider(applicationOwner, tenantDomain, spName);

        OAuthConsumerAppDTO createdApp;
        try {
            // Register the OAuth app.
            createdApp = createOAuthApp(registrationRequest, applicationOwner, tenantDomain, spName);
        } catch (DCRMException ex) {
            if (log.isDebugEnabled()) {
                log.debug("OAuth app: " + spName + " registration failed in tenantDomain: " + tenantDomain + ". " +
                        "Deleting the service provider: " + spName + " to rollback.");
            }
            deleteServiceProvider(spName, tenantDomain, applicationOwner);
            throw ex;
        }

        try {
            updateServiceProviderWithOAuthAppDetails(serviceProvider, createdApp, applicationOwner, tenantDomain);
        } catch (DCRMException ex) {
            // Delete the OAuth app created. This will also remove the registered SP for the OAuth app.
            deleteApplication(createdApp.getOauthConsumerKey());
            throw ex;
        }
        return buildResponse(createdApp);
    }

    private Application buildResponse(OAuthConsumerAppDTO createdApp) {
        Application application = new Application();
        application.setClient_name(createdApp.getApplicationName());
        application.setClient_id(createdApp.getOauthConsumerKey());
        application.setClient_secret(createdApp.getOauthConsumerSecret());

        List<String> redirectUrisList = new ArrayList<>();
        redirectUrisList.add(createdApp.getCallbackUrl());
        application.setRedirect_uris(redirectUrisList);

        return application;
    }

    private void updateServiceProviderWithOAuthAppDetails(ServiceProvider serviceProvider,
                                                          OAuthConsumerAppDTO createdApp,
                                                          String applicationOwner,
                                                          String tenantDomain) throws DCRMException {
        // Update created service provider, InboundAuthenticationConfig with OAuth application info.
        InboundAuthenticationConfig inboundAuthenticationConfig = new InboundAuthenticationConfig();
        List<InboundAuthenticationRequestConfig> inboundAuthenticationRequestConfigs = new ArrayList<>();

        InboundAuthenticationRequestConfig inboundAuthenticationRequestConfig =
                new InboundAuthenticationRequestConfig();
        inboundAuthenticationRequestConfig.setInboundAuthKey(createdApp.getOauthConsumerKey());
        inboundAuthenticationRequestConfig.setInboundAuthType(AUTH_TYPE_OAUTH_2);
        inboundAuthenticationRequestConfigs.add(inboundAuthenticationRequestConfig);
        inboundAuthenticationConfig.setInboundAuthenticationRequestConfigs(inboundAuthenticationRequestConfigs
                .toArray(new InboundAuthenticationRequestConfig[inboundAuthenticationRequestConfigs
                        .size()]));
        serviceProvider.setInboundAuthenticationConfig(inboundAuthenticationConfig);
        //Set SaaS app option
        serviceProvider.setSaasApp(false);

        // Update the Service Provider app to add OAuthApp as an Inbound Authentication Config
        updateServiceProvider(serviceProvider, tenantDomain, applicationOwner);
    }

    private OAuthConsumerAppDTO createOAuthApp(ApplicationRegistrationRequest registrationRequest,
                                               String applicationOwner,
                                               String tenantDomain,
                                               String spName) throws DCRMException {
        // Then Create OAuthApp
        OAuthConsumerAppDTO oAuthConsumerApp = new OAuthConsumerAppDTO();
        oAuthConsumerApp.setApplicationName(spName);
        oAuthConsumerApp.setCallbackUrl(
                validateAndSetCallbackURIs(registrationRequest.getRedirectUris(), registrationRequest.getGrantTypes()));

        String grantType = StringUtils.join(registrationRequest.getGrantTypes(), GRANT_TYPE_SEPARATOR);
        oAuthConsumerApp.setGrantTypes(grantType);
        oAuthConsumerApp.setOAuthVersion(OAUTH_VERSION);
        oAuthConsumerApp.setTokenType(registrationRequest.getTokenType());
        if (log.isDebugEnabled()) {
            log.debug("Creating OAuth Application: " + spName + " in tenant: " + tenantDomain);
        }
        try {
            oAuthAdminService.registerOAuthApplicationData(oAuthConsumerApp);
        } catch (IdentityOAuthAdminException e) {
            throw DCRMUtils.generateServerException(
                    DCRMConstants.ErrorMessages.FAILED_TO_REGISTER_APPLICATION, spName, e);
        }

        if (log.isDebugEnabled()) {
            log.debug("Created OAuth Application: " + spName + " in tenant: " + tenantDomain);
        }

        OAuthConsumerAppDTO createdApp;
        try {
            createdApp = oAuthAdminService.getOAuthApplicationDataByAppName(oAuthConsumerApp.getApplicationName());
        } catch (IdentityOAuthAdminException e) {
            throw DCRMUtils.generateServerException(
                    DCRMConstants.ErrorMessages.FAILED_TO_GET_APPLICATION, oAuthConsumerApp.getApplicationName(), e);
        }

        if (createdApp == null) {
            throw DCRMUtils.generateServerException(DCRMConstants.ErrorMessages.FAILED_TO_REGISTER_APPLICATION, spName);
        }

        if (log.isDebugEnabled()) {
            log.debug("Retrieved Details of OAuth App: " + createdApp.getApplicationName() + " in tenant: " +
                    tenantDomain);
        }
        return createdApp;
    }

    private ServiceProvider createServiceProvider(String applicationOwner, String tenantDomain,
                                                  String spName) throws DCRMException {
        // Create the Service Provider
        ServiceProvider sp = new ServiceProvider();
        sp.setApplicationName(spName);
        User user = new User();
        user.setUserName(applicationOwner);
        user.setTenantDomain(tenantDomain);
        sp.setOwner(user);
        sp.setDescription("Service Provider for application " + spName);

        createServiceProvider(sp, applicationOwner, tenantDomain);

        // Get created service provider.
        ServiceProvider clientSP = getServiceProvider(spName, tenantDomain);
        if (clientSP == null) {
            throw DCRMUtils.generateClientException(DCRMConstants.ErrorMessages.FAILED_TO_REGISTER_SP, spName);
        }
        return clientSP;
    }

    /**
     * Check whether servers provider exist with a given name in the tenant.
     *
     * @param serviceProviderName
     * @param tenantDomain
     * @return
     */
    private boolean isServiceProviderExist(String serviceProviderName, String tenantDomain) {

        ServiceProvider serviceProvider = null;
        try {
            serviceProvider = getServiceProvider(serviceProviderName, tenantDomain);
        } catch (DCRMException e) {
            log.error("Error while retriving service provider: " + serviceProviderName + " in tenant: " + tenantDomain);
        }

        return serviceProvider != null;
    }

    private ServiceProvider getServiceProvider(String applicationName, String tenantDomain) throws DCRMException {
        ServiceProvider serviceProvider = null;
        try {
            serviceProvider = DCRDataHolder.getInstance().getApplicationManagementService().getServiceProvider(applicationName, tenantDomain);
        } catch (IdentityApplicationManagementException e) {
            throw DCRMUtils.generateServerException(
                    DCRMConstants.ErrorMessages.FAILED_TO_GET_SP, applicationName, e);
        }
        return serviceProvider;
    }

    private void updateServiceProvider(ServiceProvider serviceProvider, String tenantDomain, String userName) throws DCRMException {
        try {
            DCRDataHolder.getInstance().getApplicationManagementService()
                    .updateApplication(serviceProvider, tenantDomain, userName);
        } catch (IdentityApplicationManagementException e) {
            throw DCRMUtils.generateServerException(
                    DCRMConstants.ErrorMessages.FAILED_TO_UPDATE_SP, serviceProvider.getApplicationName(), e);
        }
    }

    private void createServiceProvider(ServiceProvider serviceProvider,
                                       String applicationName, String tenantDomain) throws DCRMException {
        try {
            DCRDataHolder.getInstance().getApplicationManagementService()
                    .createApplication(serviceProvider, tenantDomain, applicationName);
        } catch (IdentityApplicationManagementException e) {
            String errorMessage =
                    "Error while creating service provider: " + applicationName + " in tenant: " + tenantDomain;
            throw new DCRMException(ErrorCodes.BAD_REQUEST.toString(), errorMessage, e);
        }
    }

    private void deleteServiceProvider(String applicationName,
                                       String tenantDomain, String userName) throws DCRMException {
        try {
            DCRDataHolder.getInstance().getApplicationManagementService()
                    .deleteApplication(applicationName, tenantDomain, userName);
        } catch (IdentityApplicationManagementException e) {
            throw DCRMUtils.generateServerException(DCRMConstants.ErrorMessages.FAILED_TO_DELETE_SP, applicationName, e);
        }
    }

    private String validateAndSetCallbackURIs(List<String> redirectUris, List<String> grantTypes) throws DCRMException {

        //TODO: After implement multi-urls to the oAuth application, we have to change this API call
        //TODO: need to validate before processing request
        if (redirectUris.size() == 0) {
            if (isRedirectURIMandatory(grantTypes)) {
                String errorMessage = "RedirectUris property must have at least one URI value when using " +
                        "Authorization code or implicit grant types.";
                throw DCRMUtils.generateClientException(
                        DCRMConstants.ErrorMessages.BAD_REQUEST_INVALID_INPUT, errorMessage);
            } else {
                return StringUtils.EMPTY;
            }
        } else if (redirectUris.size() == 1) {
            String redirectUri = redirectUris.get(0);
            if (DCRMUtils.isRedirectionUriValid(redirectUri)) {
                return redirectUri;
            } else {
                throw DCRMUtils.generateClientException(
                        DCRMConstants.ErrorMessages.BAD_REQUEST_INVALID_REDIRECT_URI, redirectUri);
            }

        } else {
            return OAuthConstants.CALLBACK_URL_REGEXP_PREFIX + createRegexPattern(redirectUris);
        }
    }

    private boolean isRedirectURIMandatory(List<String> grantTypes) {
        return grantTypes.contains(DCRConstants.GrantTypes.AUTHORIZATION_CODE) ||
                grantTypes.contains(DCRConstants.GrantTypes.IMPLICIT);
    }

    private String createRegexPattern(List<String> redirectURIs) throws DCRMException {
        StringBuilder regexPattern = new StringBuilder();
        for (String redirectURI : redirectURIs) {
            if (DCRMUtils.isRedirectionUriValid(redirectURI)) {
                if (regexPattern.length() > 0) {
                    regexPattern.append("|").append(redirectURI);
                } else {
                    regexPattern.append("(").append(redirectURI);
                }
            } else {
                throw DCRMUtils.generateClientException(
                        DCRMConstants.ErrorMessages.BAD_REQUEST_INVALID_REDIRECT_URI, redirectURI);
            }
        }
        if (regexPattern.length() > 0) {
            regexPattern.append(")");
        }
        return regexPattern.toString();
    }
}
