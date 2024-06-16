/*
 * Copyright 2002-2017 the original author or authors.
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
package uk.ac.ox.ctl.lti13.security.oauth2.core.endpoint;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.util.Assert;

/**
 * An &quot;exchange&quot; of an OAuth 2.0 Authorization Request and Response
 * for the authorization code grant type.
 *
 * @param authorizationRequest This should be our own class in the future
 * @author Joe Grandja
 * @see OAuth2AuthorizationRequest
 * @see OAuth2AuthorizationResponse
 * @since 5.0
 */
public record OIDCLaunchFlowExchange(OAuth2AuthorizationRequest authorizationRequest,
									 OIDCLaunchFlowResponse authorizationResponse) {
	/**
	 * Constructs a new {@code OAuth2AuthorizationExchange} with the provided
	 * Authorization Request and Authorization Response.
	 *
	 * @param authorizationRequest  the {@link OAuth2AuthorizationRequest Authorization Request}
	 * @param authorizationResponse the {@link OAuth2AuthorizationResponse Authorization Response}
	 */
	public OIDCLaunchFlowExchange {
		Assert.notNull(authorizationRequest, "authorizationRequest cannot be null");
		Assert.notNull(authorizationResponse, "authorizationResponse cannot be null");
	}
}
