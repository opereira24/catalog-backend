package pt.diamondcars.catalogbackend.config.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Replaces the production {@link JwtDecoder} with the offline HMAC one of {@link TestJwtSupport}.
 * A {@code @Primary} bean with a different name, not an override: Spring Boot 4 disables bean
 * definition overriding, and a bean also named {@code jwtDecoder} fails the context with {@code
 * BeanDefinitionOverrideException}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtDecoderConfig {

	/**
	 * @return a decoder that only accepts {@link TestJwtSupport#VALID_AUDIENCE}
	 */
	@Bean
	@Primary
	JwtDecoder testJwtDecoder() {
		return TestJwtSupport.decoderAcceptingAudience(TestJwtSupport.VALID_AUDIENCE);
	}
}
