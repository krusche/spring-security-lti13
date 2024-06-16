/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.ox.ctl.lti13.security.oauth2.client.lti.authentication;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithms;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestOperations;
import uk.ac.ox.ctl.lti13.security.oauth2.core.endpoint.OIDCLaunchFlowResponse;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An implementation of an {@link AuthenticationProvider}
 * for the IMS SEC 1.0 OpenID Connect Launch Flow
 * <p>
 * This {@link AuthenticationProvider} is responsible for authenticating
 * an ID Token in an OpenID Implicit flow.
 * <p>
 * It will create a {@code Principal} in the form of an {@link OidcUser}.
 * The {@code OidcUser} is then associated to the {@link OAuth2LoginAuthenticationToken}
 * to complete the authentication.
 *
 * @author Joe Grandja
 * @since 5.0
 * @see OAuth2LoginAuthenticationToken
 * @see OAuth2AccessTokenResponseClient
 * @see OidcUserService
 * @see OidcUser
 * @see <a target="_blank" href="https://openid.net/specs/openid-connect-core-1_0.html#ImplicitFlowAuth">Section 3.2 Authentication using the Implicit Flow</a>
 * @see <a target="_blank" href="https://openid.net/specs/openid-connect-core-1_0.html#ImplicitAuthRequest">3.2.2.1.  Authentication Request</a>
 * @see <a target="_blank" href="https://openid.net/specs/openid-connect-core-1_0.html#ImplicitAuthResponse">3.2.2.5.  Successful Authentication Response</a>
 */
public class OidcLaunchFlowAuthenticationProvider implements AuthenticationProvider {
	private static final String INVALID_STATE_PARAMETER_ERROR_CODE = "invalid_state_parameter";
	private static final String MISSING_SIGNATURE_VERIFIER_ERROR_CODE = "missing_signature_verifier";
	private final Map<String, JwtDecoder> jwtDecoders = new ConcurrentHashMap<>();
	private GrantedAuthoritiesMapper authoritiesMapper = (authorities -> authorities);
	private RestOperations restOperations;

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		OidcLaunchFlowToken authorizationCodeAuthentication =
			(OidcLaunchFlowToken) authentication;

		// Section 3.1.2.1 Authentication Request - https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest
		// scope
		// 		REQUIRED. OpenID Connect requests MUST contain the "openid" scope value.
		if (!authorizationCodeAuthentication.getAuthorizationExchange()
			.authorizationRequest().getScopes().contains(OidcScopes.OPENID)) {
			// This is NOT an OpenID Connect Authentication Request so return null
			// and let OAuth2LoginAuthenticationProvider handle it instead
			return null;
		}

		OAuth2AuthorizationRequest authorizationRequest = authorizationCodeAuthentication
			.getAuthorizationExchange().authorizationRequest();
		OIDCLaunchFlowResponse authorizationResponse = authorizationCodeAuthentication
			.getAuthorizationExchange().authorizationResponse();

		if (authorizationResponse.statusError()) {
			throw new OAuth2AuthenticationException(
				authorizationResponse.getError(), authorizationResponse.getError().toString());
		}

		if (!authorizationResponse.getState().equals(authorizationRequest.getState())) {
			OAuth2Error oauth2Error = new OAuth2Error(INVALID_STATE_PARAMETER_ERROR_CODE);
			throw new OAuth2AuthenticationException(oauth2Error, oauth2Error.toString());
		}

		ClientRegistration clientRegistration = authorizationCodeAuthentication.getClientRegistration();

		OidcIdToken idToken = createOidcToken(clientRegistration, authorizationResponse.getIdToken());

		// We don't have a userinfo endpoint so just construct our user from the claims in the ID Token
		Set<GrantedAuthority> authorities = new HashSet<>();
		OidcUserAuthority authority = new OidcUserAuthority(idToken, null);
		authorities.add(authority);
		DefaultOidcUser oidcUser = new DefaultOidcUser(authorities, idToken);

		Collection<? extends GrantedAuthority> mappedAuthorities =
			this.authoritiesMapper.mapAuthorities(oidcUser.getAuthorities());

		OidcLaunchFlowToken authenticationResult = new OidcLaunchFlowToken(
			authorizationCodeAuthentication.getClientRegistration(),
			authorizationCodeAuthentication.getAuthorizationExchange(),
			oidcUser,
			mappedAuthorities);
		authenticationResult.setDetails(authorizationCodeAuthentication.getDetails());

		return authenticationResult;
	}

	/**
	 * Sets the {@link GrantedAuthoritiesMapper} used for mapping {@link OidcUser#getAuthorities()}}
	 * to a new set of authorities which will be associated to the {@link OAuth2LoginAuthenticationToken}.
	 *
	 * @param authoritiesMapper the {@link GrantedAuthoritiesMapper} used for mapping the user's authorities
	 */
	public final void setAuthoritiesMapper(GrantedAuthoritiesMapper authoritiesMapper) {
		Assert.notNull(authoritiesMapper, "authoritiesMapper cannot be null");
		this.authoritiesMapper = authoritiesMapper;
	}

	/**
	 * Sets the {@link RestOperations} used to retrieve the JWKs URL.
	 *
	 * @param restOperations the {@link RestOperations} used to retrieve the JWKs URI.
	 */
	public final void setRestOperations(RestOperations restOperations) {
		this.restOperations = restOperations;
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return OidcLaunchFlowToken.class.isAssignableFrom(authentication);
	}

	private OidcIdToken createOidcToken(ClientRegistration clientRegistration, String idToken) {
		JwtDecoder jwtDecoder = getJwtDecoder(clientRegistration);
		Jwt jwt = jwtDecoder.decode(idToken);
		OidcIdToken oidcIdToken = new OidcIdToken(jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getClaims());
		OidcTokenValidator.validateIdToken(oidcIdToken, clientRegistration);
		return oidcIdToken;
	}

	private JwtDecoder getJwtDecoder(ClientRegistration clientRegistration) {
		String jwkSetUri = clientRegistration.getProviderDetails().getJwkSetUri();
		if (!StringUtils.hasText(jwkSetUri)) {
			OAuth2Error oauth2Error = new OAuth2Error(
					MISSING_SIGNATURE_VERIFIER_ERROR_CODE,
					"Failed to find a Signature Verifier for Client Registration: '" +
							clientRegistration.getRegistrationId() + "'. Check to ensure you have configured the JwkSet URI.",
					null
			);
			throw new OAuth2AuthenticationException(oauth2Error, oauth2Error.toString());
		}
		JwtDecoder jwtDecoder = this.jwtDecoders.get(jwkSetUri);
		if (jwtDecoder == null) {
			// TODO This should look at the Cache-Control header so to expire old jwtDecoders.
			// Canvas looks to rotate it's keys monthly.
			NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder decoderBuilder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).jwsAlgorithm(SignatureAlgorithm.from(JwsAlgorithms.RS256));
			if (restOperations != null) {
				decoderBuilder.restOperations(restOperations);
			}
			jwtDecoder = decoderBuilder.build();
			this.jwtDecoders.put(jwkSetUri, jwtDecoder);
		}
		return jwtDecoder;
	}
}
