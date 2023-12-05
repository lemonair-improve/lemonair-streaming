package com.hanghae.lemonairstreaming.config;

import java.util.Arrays;
import java.util.Collections;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.config.EnableWebFlux;

//TODO: 2023-12-02 CORSFilter가 없으면 무슨 문제가 발생하는지 알아보기
@Configuration
@EnableWebFlux
public class CORSFilter {
	@Bean
	public CorsWebFilter corsWebFilter() {
		CorsConfiguration corsConfig = new CorsConfiguration();
		corsConfig.setAllowedOrigins(Collections.singletonList("*"));
		corsConfig.setAllowedMethods(Arrays.asList("GET", "POST", "DELETE", "PUT"));
		corsConfig.setAllowedHeaders(Collections.singletonList("*"));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", corsConfig);
		corsConfig.setExposedHeaders(Arrays.asList("Access_Token", "Refresh_Token"));

		return new CorsWebFilter(source);
	}
}