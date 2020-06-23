/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.application.authenticator.basicauth;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.AuthenticatorFlowStatus;
import org.wso2.carbon.identity.application.authentication.framework.LocalApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.config.ConfigurationFacade;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.exception.InvalidCredentialsException;
import org.wso2.carbon.identity.application.authentication.framework.exception.LogoutFailedException;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.authenticator.basicauth.internal.BasicAuthenticatorDataHolder;
import org.wso2.carbon.identity.application.authenticator.basicauth.internal.BasicAuthenticatorServiceComponent;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.application.common.model.User;
import org.wso2.carbon.identity.base.IdentityRuntimeException;
import org.wso2.carbon.identity.captcha.connector.recaptcha.SSOLoginReCaptchaConfig;
import org.wso2.carbon.identity.captcha.util.CaptchaConstants;
import org.wso2.carbon.identity.core.model.IdentityErrorMsgContext;
import org.wso2.carbon.identity.core.util.IdentityCoreConstants;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.governance.IdentityGovernanceException;
import org.wso2.carbon.identity.governance.common.IdentityConnectorConfig;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Username Password based Authenticator
 */
public class BasicAuthenticator extends AbstractApplicationAuthenticator
        implements LocalApplicationAuthenticator {

    private static final long serialVersionUID = 1819664539416029785L;
    private static final String PASSWORD_PROPERTY = "PASSWORD_PROPERTY";
    private static final String PASSWORD_RESET_ENDPOINT = "accountrecoveryendpoint/confirmrecovery.do?";
    private static final Log log = LogFactory.getLog(BasicAuthenticator.class);
    private static String RE_CAPTCHA_USER_DOMAIN = "user-domain-recaptcha";
    private List<String> omittingErrorParams = null;

    @Override
    public boolean canHandle(HttpServletRequest request) {
        String userName = request.getParameter(BasicAuthenticatorConstants.USER_NAME);
        String password = request.getParameter(BasicAuthenticatorConstants.PASSWORD);
        return userName != null && password != null;
    }

    @Override
    public AuthenticatorFlowStatus process(HttpServletRequest request,
                                           HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException, LogoutFailedException {

        if (context.isLogoutRequest()) {
            return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
        } else {
            return super.process(request, response, context);
        }
    }

    @Override
    protected void initiateAuthenticationRequest(HttpServletRequest request,
                                                 HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException {

        Map<String, String> parameterMap = getAuthenticatorConfig().getParameterMap();
        String showAuthFailureReason = null;
        String maskUserNotExistsErrorCode = null;
        if (parameterMap != null) {
            showAuthFailureReason = parameterMap.get(BasicAuthenticatorConstants.CONF_SHOW_AUTH_FAILURE_REASON);
            if (log.isDebugEnabled()) {
                log.debug(BasicAuthenticatorConstants.CONF_SHOW_AUTH_FAILURE_REASON + " has been set as : " +
                        showAuthFailureReason);
            }
            if (Boolean.parseBoolean(showAuthFailureReason)) {
                maskUserNotExistsErrorCode =
                        parameterMap.get(BasicAuthenticatorConstants.CONF_MASK_USER_NOT_EXISTS_ERROR_CODE);
                if (log.isDebugEnabled()) {
                    log.debug(BasicAuthenticatorConstants.CONF_MASK_USER_NOT_EXISTS_ERROR_CODE +
                            " has been set as : " + maskUserNotExistsErrorCode);
                }

                String errorParamsToOmit = parameterMap.get(BasicAuthenticatorConstants.CONF_ERROR_PARAMS_TO_OMIT);
                if (log.isDebugEnabled()) {
                    log.debug(BasicAuthenticatorConstants.CONF_ERROR_PARAMS_TO_OMIT + " has been set as : " +
                            errorParamsToOmit);
                }
                if (StringUtils.isNotBlank(errorParamsToOmit)) {
                    errorParamsToOmit = errorParamsToOmit.replaceAll(" ", "");
                    omittingErrorParams = new ArrayList<>(Arrays.asList(errorParamsToOmit.split(",")));
                }
            }
        }

        String loginPage = ConfigurationFacade.getInstance().getAuthenticationEndpointURL();
        String retryPage = ConfigurationFacade.getInstance().getAuthenticationEndpointRetryURL();
        String queryParams = context.getContextIdIncludedQueryParams();
        String password = (String) context.getProperty(PASSWORD_PROPERTY);
        String redirectURL;
        context.getProperties().remove(PASSWORD_PROPERTY);

        Map<String, String> runtimeParams = getRuntimeParams(context);
        if (runtimeParams != null) {
            String inputType = null;
            String usernameFromContext = runtimeParams.get(FrameworkConstants.JSAttributes.JS_OPTIONS_USERNAME);
            if (usernameFromContext != null) {
                inputType = FrameworkConstants.INPUT_TYPE_IDENTIFIER_FIRST;
            }
            if (FrameworkConstants.INPUT_TYPE_IDENTIFIER_FIRST.equalsIgnoreCase(inputType)) {
                queryParams += "&" + FrameworkConstants.RequestParams.INPUT_TYPE +"=" + inputType;
                context.addEndpointParam(FrameworkConstants.JSAttributes.JS_OPTIONS_USERNAME, usernameFromContext);
            }
        }

        try {
            String retryParam = "";

            if (context.isRetrying()) {
                retryParam = BasicAuthenticatorConstants.AUTH_FAILURE_PARAM + "true" +
                        BasicAuthenticatorConstants.AUTH_FAILURE_MSG_PARAM + "login.fail.message";
            }

            if (context.getProperty("UserTenantDomainMismatch") != null &&
                    (Boolean) context.getProperty("UserTenantDomainMismatch")) {
                retryParam = BasicAuthenticatorConstants.AUTH_FAILURE_PARAM + "true" +
                        BasicAuthenticatorConstants.AUTH_FAILURE_MSG_PARAM + "user.tenant.domain.mismatch.message";
                context.setProperty("UserTenantDomainMismatch", false);
            }

            IdentityErrorMsgContext errorContext = IdentityUtil.getIdentityErrorMsg();
            IdentityUtil.clearIdentityErrorMsg();

            if (errorContext != null && errorContext.getErrorCode() != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Identity error message context is not null");
                }
                String errorCode = errorContext.getErrorCode();

                if (errorCode.equals(IdentityCoreConstants.USER_ACCOUNT_NOT_CONFIRMED_ERROR_CODE)) {
                    retryParam = BasicAuthenticatorConstants.AUTH_FAILURE_PARAM + "true" +
                            BasicAuthenticatorConstants.AUTH_FAILURE_MSG_PARAM + "account.confirmation.pending";
                    String username = request.getParameter(BasicAuthenticatorConstants.USER_NAME);
                    Object domain = IdentityUtil.threadLocalProperties.get().get(RE_CAPTCHA_USER_DOMAIN);
                    if (domain != null) {
                        username = IdentityUtil.addDomainToName(username, domain.toString());
                    }

                    redirectURL = loginPage + ("?" + queryParams) + BasicAuthenticatorConstants.FAILED_USERNAME
                            + URLEncoder.encode(username, BasicAuthenticatorConstants.UTF_8) +
                            BasicAuthenticatorConstants.ERROR_CODE + errorCode + BasicAuthenticatorConstants
                            .AUTHENTICATORS + getName() + ":" + BasicAuthenticatorConstants.LOCAL + retryParam;

                } else if (errorCode.equals(
                        IdentityCoreConstants.ADMIN_FORCED_USER_PASSWORD_RESET_VIA_EMAIL_LINK_ERROR_CODE)) {
                    retryParam = BasicAuthenticatorConstants.AUTH_FAILURE_PARAM + "true" +
                            BasicAuthenticatorConstants.AUTH_FAILURE_MSG_PARAM + "password.reset.pending";
                    redirectURL = loginPage + ("?" + queryParams) +
                            BasicAuthenticatorConstants.FAILED_USERNAME + URLEncoder.encode(request.getParameter(
                            BasicAuthenticatorConstants.USER_NAME), BasicAuthenticatorConstants.UTF_8) +
                            BasicAuthenticatorConstants.ERROR_CODE + errorCode +
                            BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                            BasicAuthenticatorConstants.LOCAL + retryParam;

                } else if (errorCode.equals(
                        IdentityCoreConstants.ADMIN_FORCED_USER_PASSWORD_RESET_VIA_OTP_ERROR_CODE)) {
                    String username = request.getParameter(BasicAuthenticatorConstants.USER_NAME);
                    String tenantDoamin = MultitenantUtils.getTenantDomain(username);
                    String callback = loginPage + ("?" + queryParams)
                            + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                            BasicAuthenticatorConstants.LOCAL;
                    redirectURL = (PASSWORD_RESET_ENDPOINT + queryParams) +
                            BasicAuthenticatorConstants.USER_NAME_PARAM + URLEncoder.encode(username, BasicAuthenticatorConstants.UTF_8) +
                            BasicAuthenticatorConstants.TENANT_DOMAIN_PARAM + URLEncoder.encode(tenantDoamin, BasicAuthenticatorConstants.UTF_8) +
                            BasicAuthenticatorConstants.CONFIRMATION_PARAM + URLEncoder.encode(password,
                            BasicAuthenticatorConstants.UTF_8) + "&callback=" + URLEncoder.encode(callback,
                            BasicAuthenticatorConstants.UTF_8);

                } else if ("true".equals(showAuthFailureReason)) {

                    if (Boolean.parseBoolean(maskUserNotExistsErrorCode) &&
                            StringUtils.contains(errorCode, UserCoreConstants.ErrorCode.USER_DOES_NOT_EXIST)) {

                        errorCode = UserCoreConstants.ErrorCode.INVALID_CREDENTIAL;

                        if (log.isDebugEnabled()) {
                            log.debug("Masking user not found error code: " +
                                    UserCoreConstants.ErrorCode.USER_DOES_NOT_EXIST + " with error code: " +
                                    errorCode);
                        }
                    }

                    String reason = null;
                    if (errorCode.contains(":")) {
                        String[] errorCodeReason = errorCode.split(":");
                        errorCode = errorCodeReason[0];
                        if (errorCodeReason.length > 1) {
                            reason = errorCodeReason[1];
                        }
                    }
                    int remainingAttempts =
                            errorContext.getMaximumLoginAttempts() - errorContext.getFailedLoginAttempts();

                    if (log.isDebugEnabled()) {
                        log.debug("errorCode : " + errorCode);
                        log.debug("username : " + request.getParameter(BasicAuthenticatorConstants.USER_NAME));
                        log.debug("remainingAttempts : " + remainingAttempts);
                    }

                    if (errorCode.equals(UserCoreConstants.ErrorCode.INVALID_CREDENTIAL)) {
                        Map<String, String> paramMap = new HashMap<>();
                        paramMap.put(BasicAuthenticatorConstants.ERROR_CODE, errorCode);
                        paramMap.put(BasicAuthenticatorConstants.FAILED_USERNAME,
                                URLEncoder.encode(request.getParameter(BasicAuthenticatorConstants.USER_NAME),
                                        BasicAuthenticatorConstants.UTF_8));
                        paramMap.put(BasicAuthenticatorConstants.REMAINING_ATTEMPTS, String.valueOf(remainingAttempts));

                        retryParam = retryParam + buildErrorParamString(paramMap);
                        redirectURL = loginPage + ("?" + queryParams)
                                + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                                BasicAuthenticatorConstants.LOCAL + retryParam;

                    } else if (errorCode.equals(UserCoreConstants.ErrorCode.USER_IS_LOCKED)) {
                        Map<String, String> paramMap = new HashMap<>();
                        paramMap.put(BasicAuthenticatorConstants.ERROR_CODE, errorCode);
                        paramMap.put(BasicAuthenticatorConstants.FAILED_USERNAME,
                                URLEncoder.encode(request.getParameter(BasicAuthenticatorConstants.USER_NAME),
                                        BasicAuthenticatorConstants.UTF_8));

                        if (StringUtils.isNotBlank(reason)) {
                            paramMap.put(BasicAuthenticatorConstants.LOCKED_REASON, reason);
                        }
                        if (remainingAttempts == 0) {
                            paramMap.put(BasicAuthenticatorConstants.REMAINING_ATTEMPTS, "0");
                        }

                        redirectURL = response.encodeRedirectURL(retryPage + ("?" + queryParams))
                                + buildErrorParamString(paramMap);
                    } else if (errorCode.equals(
                            IdentityCoreConstants.ADMIN_FORCED_USER_PASSWORD_RESET_VIA_OTP_MISMATCHED_ERROR_CODE)) {
                        Map<String, String> paramMap = new HashMap<>();
                        paramMap.put(BasicAuthenticatorConstants.ERROR_CODE, errorCode);
                        paramMap.put(BasicAuthenticatorConstants.FAILED_USERNAME,
                                URLEncoder.encode(request.getParameter(BasicAuthenticatorConstants.USER_NAME),
                                        BasicAuthenticatorConstants.UTF_8));

                        retryParam = "&authFailure=true&authFailureMsg=login.fail.message";
                        redirectURL = loginPage + ("?" + queryParams)
                                + buildErrorParamString(paramMap)
                                + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                                BasicAuthenticatorConstants.LOCAL + retryParam;

                    } else {
                        Map<String, String> paramMap = new HashMap<>();
                        paramMap.put(BasicAuthenticatorConstants.ERROR_CODE, errorCode);
                        paramMap.put(BasicAuthenticatorConstants.FAILED_USERNAME,
                                URLEncoder.encode(request.getParameter(BasicAuthenticatorConstants.USER_NAME),
                                        BasicAuthenticatorConstants.UTF_8));

                        retryParam = retryParam + buildErrorParamString(paramMap);
                        redirectURL = loginPage + ("?" + queryParams)
                                + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":"
                                + BasicAuthenticatorConstants.LOCAL + retryParam;
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Unknown identity error code.");
                    }
                    redirectURL = loginPage + ("?" + queryParams)
                            + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                            BasicAuthenticatorConstants.LOCAL + retryParam;

                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Identity error message context is null");
                }
                redirectURL = loginPage + ("?" + queryParams)
                        + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                        BasicAuthenticatorConstants.LOCAL + retryParam;
            }

            redirectURL += getCaptchaParams(context.getTenantDomain());
            response.sendRedirect(redirectURL);
        } catch (IOException e) {
            throw new AuthenticationFailedException(e.getMessage(), User.getUserFromUserName(request.getParameter
                    (BasicAuthenticatorConstants.USER_NAME)), e);
        }
    }


    @Override
    protected void processAuthenticationResponse(HttpServletRequest request,
                                                 HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException {

        String username = request.getParameter(BasicAuthenticatorConstants.USER_NAME);
        String password = request.getParameter(BasicAuthenticatorConstants.PASSWORD);

        Map<String, Object> authProperties = context.getProperties();
        if (authProperties == null) {
            authProperties = new HashMap<>();
            context.setProperties(authProperties);
        }

        Map<String, String> runtimeParams = getRuntimeParams(context);
        if (runtimeParams != null) {
            String usernameFromContext = runtimeParams.get(FrameworkConstants.JSAttributes.JS_OPTIONS_USERNAME);
            if (usernameFromContext != null && !usernameFromContext.equals(username)) {
                if (log.isDebugEnabled()) {
                    log.debug("Username set for identifier first login: " + usernameFromContext + " and username " +
                            "submitted from login page" + username + " does not match.");
                }
                throw new InvalidCredentialsException("Credential mismatch.");
            }
        }

        authProperties.put(PASSWORD_PROPERTY, password);

        boolean isAuthenticated;
        UserStoreManager userStoreManager;
        // Reset RE_CAPTCHA_USER_DOMAIN thread local variable before the authentication
        IdentityUtil.threadLocalProperties.get().remove(RE_CAPTCHA_USER_DOMAIN);
        // Check the authentication
        try {
            int tenantId = IdentityTenantUtil.getTenantIdOfUser(username);
            UserRealm userRealm = BasicAuthenticatorServiceComponent.getRealmService().getTenantUserRealm(tenantId);
            if (userRealm != null) {
                userStoreManager = (UserStoreManager) userRealm.getUserStoreManager();
                isAuthenticated = userStoreManager.authenticate(
                        MultitenantUtils.getTenantAwareUsername(username), password);
            } else {
                throw new AuthenticationFailedException("Cannot find the user realm for the given tenant: " +
                        tenantId, User.getUserFromUserName(username));
            }
        } catch (IdentityRuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("BasicAuthentication failed while trying to get the tenant ID of the user " + username, e);
            }
            throw new AuthenticationFailedException(e.getMessage(), e);
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            if (log.isDebugEnabled()) {
                log.debug("BasicAuthentication failed while trying to authenticate the user " + username, e);
            }
            throw new AuthenticationFailedException(e.getMessage(), e);
        }

        if (!isAuthenticated) {
            if (log.isDebugEnabled()) {
                log.debug("User authentication failed due to invalid credentials");
            }
            if (IdentityUtil.threadLocalProperties.get().get(RE_CAPTCHA_USER_DOMAIN) != null) {
                username = IdentityUtil.addDomainToName(
                        username, IdentityUtil.threadLocalProperties.get().get(RE_CAPTCHA_USER_DOMAIN).toString());
            }
            IdentityUtil.threadLocalProperties.get().remove(RE_CAPTCHA_USER_DOMAIN);
            throw new InvalidCredentialsException("User authentication failed due to invalid credentials",
                    User.getUserFromUserName(username));
        }


        String tenantDomain = MultitenantUtils.getTenantDomain(username);

        //TODO: user tenant domain has to be an attribute in the AuthenticationContext
        authProperties.put("user-tenant-domain", tenantDomain);

        username = FrameworkUtils.prependUserStoreDomainToName(username);

        if (getAuthenticatorConfig().getParameterMap() != null) {
            String userNameUri = getAuthenticatorConfig().getParameterMap().get("UserNameAttributeClaimUri");
            if (StringUtils.isNotBlank(userNameUri)) {
                boolean multipleAttributeEnable;
                String domain = UserCoreUtil.getDomainFromThreadLocal();
                if (StringUtils.isNotBlank(domain)) {
                    multipleAttributeEnable = Boolean.parseBoolean(userStoreManager.getSecondaryUserStoreManager(domain)
                            .getRealmConfiguration().getUserStoreProperty("MultipleAttributeEnable"));
                } else {
                    multipleAttributeEnable = Boolean.parseBoolean(userStoreManager.
                            getRealmConfiguration().getUserStoreProperty("MultipleAttributeEnable"));
                }
                if (multipleAttributeEnable) {
                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("Searching for UserNameAttribute value for user " + username +
                                    " for claim uri : " + userNameUri);
                        }
                        String usernameValue = userStoreManager.
                                getUserClaimValue(MultitenantUtils.getTenantAwareUsername(username), userNameUri, null);
                        if (StringUtils.isNotBlank(usernameValue)) {
                            tenantDomain = MultitenantUtils.getTenantDomain(username);
                            usernameValue = FrameworkUtils.prependUserStoreDomainToName(usernameValue);
                            username = usernameValue + "@" + tenantDomain;
                            if (log.isDebugEnabled()) {
                                log.debug("UserNameAttribute is found for user. Value is :  " + username);
                            }
                        }
                    } catch (UserStoreException e) {
                        //ignore  but log in debug
                        if (log.isDebugEnabled()) {
                            log.debug("Error while retrieving UserNameAttribute for user : " + username, e);
                        }
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("MultipleAttribute is not enabled for user store domain : " + domain + " " +
                                "Therefore UserNameAttribute is not retrieved");
                    }
                }
            }
        }
        context.setSubject(AuthenticatedUser.createLocalAuthenticatedUserFromSubjectIdentifier(username));
        String rememberMe = request.getParameter("chkRemember");

        if ("on".equals(rememberMe)) {
            context.setRememberMe(true);
        }
    }

    @Override
    protected boolean retryAuthenticationEnabled() {
        return true;
    }

    @Override
    public String getContextIdentifier(HttpServletRequest request) {
        return request.getParameter("sessionDataKey");
    }

    @Override
    public String getFriendlyName() {
        return BasicAuthenticatorConstants.AUTHENTICATOR_FRIENDLY_NAME;
    }

    @Override
    public String getName() {
        return BasicAuthenticatorConstants.AUTHENTICATOR_NAME;
    }

    private String buildErrorParamString(Map<String, String> paramMap) {

        StringBuilder params = new StringBuilder();
        for (Map.Entry<String, String> entry : paramMap.entrySet()) {
            params.append(filterAndAddParam(entry.getKey(), entry.getValue()));
        }
        return params.toString();
    }

    private String filterAndAddParam(String key, String value) {

        String keyActual = key.replaceAll("&", "").replaceAll("=", "");
        if (CollectionUtils.isNotEmpty(omittingErrorParams) && omittingErrorParams.contains(keyActual)) {
            if (log.isDebugEnabled()) {
                log.debug("omitting param " + keyActual + " in the error response.");
            }

            // The param should be omitted, hence returning empty string.
            return StringUtils.EMPTY;
        } else {
            return key + value;
        }
    }

    /**
     * Append the recaptcha related params if recaptcha is enabled for the authentication always.
     *
     * @param tenantDomain tenant domain of the application
     * @return string with the appended recaptcha params
     */
    private String getCaptchaParams(String tenantDomain) {

        IdentityConnectorConfig connector = new SSOLoginReCaptchaConfig();
        String defaultCaptchaConfigName = ((SSOLoginReCaptchaConfig) connector).getName() +
                CaptchaConstants.ReCaptchaConnectorPropertySuffixes.ENABLE_ALWAYS;
        Property[] connectorConfigs;
        String captchaParams = "";

        try {
            connectorConfigs = BasicAuthenticatorDataHolder.getInstance().getIdentityGovernanceService()
                    .getConfiguration(new String[]{defaultCaptchaConfigName}, tenantDomain);
        if (!ArrayUtils.isEmpty(connectorConfigs) && Boolean.valueOf(connectorConfigs[0].getValue())) {
                Properties captchaConfigs = getCaptchaConfigs();

                if (captchaConfigs != null && !captchaConfigs.isEmpty() &&
                        Boolean.valueOf(captchaConfigs.getProperty(CaptchaConstants.RE_CAPTCHA_ENABLED))) {

                    captchaParams = BasicAuthenticatorConstants.RECAPTCHA_PARAM + "true" +
                            BasicAuthenticatorConstants.RECAPTCHA_KEY_PARAM + captchaConfigs.getProperty
                            (CaptchaConstants.RE_CAPTCHA_SITE_KEY) +
                            BasicAuthenticatorConstants.RECAPTCHA_API_PARAM + captchaConfigs.getProperty
                            (CaptchaConstants.RE_CAPTCHA_API_URL);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Recaptcha is not enabled.");
                    }
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Enforcing recaptcha always for the basic authentication is not enabled.");
                }
            }
        } catch (IdentityGovernanceException e) {
            log.error("Error occurred while verifying the captcha configs. Proceeding the authentication request " +
                    "without enabling recaptcha.", e);
        }

        return captchaParams;
    }

    /**
     * Get the recaptcha configs from the data holder if they are valid.
     *
     * @return recaptcha properties
     */
    private Properties getCaptchaConfigs() {

        Properties properties = BasicAuthenticatorDataHolder.getInstance().getRecaptchaConfigs();

        if (properties != null && !properties.isEmpty() &&
                Boolean.valueOf(properties.getProperty(CaptchaConstants.RE_CAPTCHA_ENABLED))) {
            if (StringUtils.isBlank(properties.getProperty(CaptchaConstants.RE_CAPTCHA_SITE_KEY)) ||
                    StringUtils.isBlank(properties.getProperty(CaptchaConstants.RE_CAPTCHA_API_URL)) ||
                    StringUtils.isBlank(properties.getProperty(CaptchaConstants.RE_CAPTCHA_SECRET_KEY)) ||
                    StringUtils.isBlank(properties.getProperty(CaptchaConstants.RE_CAPTCHA_VERIFY_URL))) {

                if (log.isDebugEnabled()) {
                    log.debug("Empty values found for the captcha properties in the file " + CaptchaConstants
                            .CAPTCHA_CONFIG_FILE_NAME + ".");
                }
                properties.clear();
            }
        }
        return properties;
    }
}
