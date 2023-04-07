/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.security.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.thingsboard.server.common.data.CacheConstants;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.common.data.security.UserCredentials;
import org.thingsboard.server.common.data.security.event.UserAuthDataChangedEvent;
import org.thingsboard.server.common.data.security.model.JwtToken;
import org.thingsboard.server.config.JwtSettings;
import org.thingsboard.server.dao.customer.CustomerService;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.service.security.auth.jwt.JwtAuthenticationProvider;
import org.thingsboard.server.service.security.auth.jwt.RefreshTokenAuthenticationProvider;
import org.thingsboard.server.service.security.exception.JwtExpiredTokenException;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.model.UserPrincipal;
import org.thingsboard.server.service.security.model.token.JwtTokenFactory;
import org.thingsboard.server.service.security.model.token.RawAccessJwtToken;

import java.util.UUID;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TokenOutdatingTest {
    private JwtAuthenticationProvider accessTokenAuthenticationProvider;
    private RefreshTokenAuthenticationProvider refreshTokenAuthenticationProvider;

    private TokenOutdatingService tokenOutdatingService;
    private ConcurrentMapCacheManager cacheManager;
    private JwtTokenFactory tokenFactory;
    private JwtSettings jwtSettings;

    private UserId userId;

    @BeforeEach
    public void setUp() {
        jwtSettings = new JwtSettings();
        jwtSettings.setTokenIssuer("test.io");
        jwtSettings.setTokenExpirationTime((int) MINUTES.toSeconds(10));
        jwtSettings.setRefreshTokenExpTime((int) DAYS.toSeconds(7));
        jwtSettings.setTokenSigningKey("secret");
        tokenFactory = new JwtTokenFactory(jwtSettings);

        cacheManager = new ConcurrentMapCacheManager();
        tokenOutdatingService = new TokenOutdatingService(cacheManager, tokenFactory, jwtSettings);
        tokenOutdatingService.initCache();

        userId = new UserId(UUID.randomUUID());

        UserService userService = mock(UserService.class);

        User user = new User();
        user.setId(userId);
        user.setAuthority(Authority.TENANT_ADMIN);
        user.setEmail("email");
        when(userService.findUserById(any(), eq(userId))).thenReturn(user);

        UserCredentials userCredentials = new UserCredentials();
        userCredentials.setEnabled(true);
        when(userService.findUserCredentialsByUserId(any(), eq(userId))).thenReturn(userCredentials);

        accessTokenAuthenticationProvider = new JwtAuthenticationProvider(tokenFactory, tokenOutdatingService);
        refreshTokenAuthenticationProvider = new RefreshTokenAuthenticationProvider(tokenFactory, userService, mock(CustomerService.class), tokenOutdatingService);
    }

    @Test
    public void testOutdateOldUserTokens() throws Exception {
        JwtToken jwtToken = createAccessJwtToken(userId);

        SECONDS.sleep(1); // need to wait before outdating so that outdatage time is strictly after token issue time
        tokenOutdatingService.onUserAuthDataChanged(new UserAuthDataChangedEvent(userId));
        assertTrue(tokenOutdatingService.isOutdated(jwtToken, userId));

        SECONDS.sleep(1);

        JwtToken newJwtToken = tokenFactory.createAccessJwtToken(createMockSecurityUser(userId));
        assertFalse(tokenOutdatingService.isOutdated(newJwtToken, userId));
    }

    @Test
    public void testAuthenticateWithOutdatedAccessToken() throws InterruptedException {
        RawAccessJwtToken accessJwtToken = getRawJwtToken(createAccessJwtToken(userId));

        assertDoesNotThrow(() -> {
            accessTokenAuthenticationProvider.authenticate(new JwtAuthenticationToken(accessJwtToken));
        });

        SECONDS.sleep(1);
        tokenOutdatingService.onUserAuthDataChanged(new UserAuthDataChangedEvent(userId));

        assertThrows(JwtExpiredTokenException.class, () -> {
            accessTokenAuthenticationProvider.authenticate(new JwtAuthenticationToken(accessJwtToken));
        });
    }

    @Test
    public void testAuthenticateWithOutdatedRefreshToken() throws InterruptedException {
        RawAccessJwtToken refreshJwtToken = getRawJwtToken(createRefreshJwtToken(userId));

        assertDoesNotThrow(() -> {
            refreshTokenAuthenticationProvider.authenticate(new RefreshAuthenticationToken(refreshJwtToken));
        });

        SECONDS.sleep(1);
        tokenOutdatingService.onUserAuthDataChanged(new UserAuthDataChangedEvent(userId));

        assertThrows(CredentialsExpiredException.class, () -> {
            refreshTokenAuthenticationProvider.authenticate(new RefreshAuthenticationToken(refreshJwtToken));
        });
    }

    @Test
    public void testTokensOutdatageTimeRemovalFromCache() throws Exception {
        JwtToken jwtToken = createAccessJwtToken(userId);

        SECONDS.sleep(1);
        tokenOutdatingService.onUserAuthDataChanged(new UserAuthDataChangedEvent(userId));

        int refreshTokenExpirationTime = 3;
        jwtSettings.setRefreshTokenExpTime(refreshTokenExpirationTime);

        SECONDS.sleep(refreshTokenExpirationTime - 2);

        assertTrue(tokenOutdatingService.isOutdated(jwtToken, userId));
        assertNotNull(cacheManager.getCache(CacheConstants.USERS_UPDATE_TIME_CACHE).get(userId.getId().toString()));

        SECONDS.sleep(3);

        assertFalse(tokenOutdatingService.isOutdated(jwtToken, userId));
        assertNull(cacheManager.getCache(CacheConstants.USERS_UPDATE_TIME_CACHE).get(userId.getId().toString()));
    }

    private JwtToken createAccessJwtToken(UserId userId) {
        return tokenFactory.createAccessJwtToken(createMockSecurityUser(userId));
    }

    private JwtToken createRefreshJwtToken(UserId userId) {
        return tokenFactory.createRefreshToken(createMockSecurityUser(userId));
    }

    private RawAccessJwtToken getRawJwtToken(JwtToken token) {
        return new RawAccessJwtToken(token.getToken());
    }

    private SecurityUser createMockSecurityUser(UserId userId) {
        SecurityUser securityUser = new SecurityUser();
        securityUser.setEmail("email");
        securityUser.setUserPrincipal(new UserPrincipal(UserPrincipal.Type.USER_NAME, securityUser.getEmail()));
        securityUser.setAuthority(Authority.CUSTOMER_USER);
        securityUser.setId(userId);
        return securityUser;
    }
}
