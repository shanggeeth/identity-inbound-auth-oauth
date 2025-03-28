/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.carbon.identity.oauth2.validators.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.common.testng.WithCarbonHome;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;

import java.net.MalformedURLException;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@WithCarbonHome
public class JWKSBasedJWTValidatorTest {

    private JWKSBasedJWTValidator validator;
    private X509Certificate mockCertificate;
    private PublicKey mockNonRSAPublicKey;
    private RSAPublicKey mockRSAPublicKey;

    private String jwtString =
            "eyJ4NXQiOiJObUptT0dVeE16WmxZak0yWkRSaE5UWmxZVEExWXpkaFpUUmlPV0UwTldJMk0ySm1PVGMxWkEiLCJhbGciOiJSUzI1NiJ9" +
                    ".eyJhdF9oYXNoIjoiR2ptOGFsN21FSkRVYjZuN3V1Mi1qUSIsInN1YiI6ImFkbWluQGNhcmJvbi5zdXBlciIsImF1ZCI6WyJ" +
                    "hVmdieWhBMVY3TWV0ZW1PNEtrUjlFYnBzOW9hIiwiaHR0cHM6XC9cL2xvY2FsaG9zdDo5NDQzXC9vYXV0aDJcL3Rva2VuIl0" +
                    "sImF6cCI6ImFWZ2J5aEExVjdNZXRlbU80S2tSOUVicHM5b2EiLCJhdXRoX3RpbWUiOjE1MjUyMzYzMzcsImlzcyI6Imh0dHB" +
                    "zOlwvXC8xMC4xMDAuOC4yOjk0NDNcL29hdXRoMlwvdG9rZW4iLCJleHAiOjE1MjUyMzk5MzcsImlhdCI6MTUyNTIzNjMzN30" +
                    ".DOPv7UHymV3zJJpxxWqbGcrvjY-OOzmdJVUxwHorDlOGABP_X_Krd584rLIbcYFmd8q5wSUuX21wXCLCOXFli1CUC-ZfP0S" +
                    "0fJqUZv_ynNo6NTFY9d3-sv0b7QYT-8mnxSmjqqsmDrOcxlD7gcYkkr1pLLQe9ZK2B_lR5KZlMW0";

    @Mock
    private DefaultJWTProcessor<SecurityContext> jwtProcessor;
    @Mock
    private JWKSourceDataProvider dataProvider;
    @Mock
    private RemoteJWKSet<SecurityContext> jwkSet;

    @BeforeMethod
    public void setUp() {

        initMocks(this);

        mockCertificate = mock(X509Certificate.class);
        mockNonRSAPublicKey = mock(PublicKey.class);
        mockRSAPublicKey = mock(RSAPublicKey.class);
    }

    @Test(dataProvider = "validateDataForException")
    public void testValidateSignature(Object test, String jwt, String jwksUri, String algorithm, Map<String, Object>
            opts) throws
            Exception {

        try (MockedStatic<JWKSourceDataProvider> jwkSourceDataProvider = mockStatic(JWKSourceDataProvider.class)) {
            jwkSourceDataProvider.when(JWKSourceDataProvider::getInstance).thenReturn(dataProvider);

            TestScenario testScenario = (TestScenario) test;

            try (MockedConstruction<DefaultJWTProcessor> mockedConstruction = Mockito.mockConstruction(
                    DefaultJWTProcessor.class,
                    (mock, context) -> {
                        if (testScenario == TestScenario.VALID_JWT) {
                            JWTClaimsSet jwtClaimsSet = new JWTClaimsSet.Builder().build();
                            when(mock.process(jwtString, null)).thenReturn(jwtClaimsSet);
                        } else if (testScenario != TestScenario.INVALID_JWKS) {
                            when(mock.process(anyString(), any(SecurityContext.class))).thenThrow(
                                    testScenario.throwError());
                        }
                    })) {
                validator = new JWKSBasedJWTValidator();

                if (testScenario == TestScenario.INVALID_JWKS) {
                    doThrow(testScenario.throwError()).when(dataProvider).getJWKSource(jwksUri);
                } else {
                    when(dataProvider.getJWKSource(anyString())).thenReturn(jwkSet);
                }

                try {
                    boolean isValid = validator.validateSignature(jwt, jwksUri, algorithm, opts);
                    assertTrue(isValid, "JWT validation failed with unexpected error.");
                } catch (IdentityOAuth2Exception e) {

                    if (testScenario == TestScenario.INVALID_JWT) {
                        assertEquals("Error occurred while parsing JWT string.", e.getMessage(),
                                "Signature validation not handled properly.");
                    }
                    if (testScenario == TestScenario.INVALID_JWKS) {
                        assertEquals("Provided jwks_uri: " + jwksUri + " is malformed.", e.getMessage(),
                                "Failed to validate jwks_uri.");
                    }
                    if (testScenario == TestScenario.INVALID_SIGNATURE) {
                        assertEquals("Signature validation failed for the provided JWT.", e.getMessage(),
                                "invalid algorithm");
                    }
                }
            }
        }
    }

    @DataProvider(name = "validateDataForException")
    public Object[][] provideValidateDataForException() {

        HashMap opts = new HashMap();
        opts.put("nonce", "nonceValue");
        return new Object[][]{
                {TestScenario.VALID_JWT, jwtString, "https://localhost:9444/oauth2/jwks", "RS256",
                        Collections.emptyMap()},
                {TestScenario.INVALID_JWT, "invalidJWT", "https://localhost:9444/oauth2/jwks", "RS256",
                        Collections.emptyMap()},
                {TestScenario.INVALID_JWKS, jwtString, "invalidUri", "invalid", Collections.emptyMap()},
                {TestScenario.INVALID_SIGNATURE, jwtString, "https://localhost:9444/oauth2/jwks", "RS256", opts},
        };
    }

    public enum TestScenario {
        VALID_JWT,
        INVALID_JWT,
        INVALID_JWKS,
        INVALID_SIGNATURE,
        UNSUPPORTED_ALGO;

        Exception throwError() {

            switch (this) {
                case VALID_JWT:
                case INVALID_JWT:
                    return new ParseException("Error occurred while parsing JWT string.", 0);
                case INVALID_JWKS:
                    return new MalformedURLException();
                case INVALID_SIGNATURE:
                    return new JOSEException("Signature validated failed.");
                case UNSUPPORTED_ALGO:
                    return new BadJOSEException("Unsupported algorithm");
                default:
                    return null;
            }
        }
    }

}
