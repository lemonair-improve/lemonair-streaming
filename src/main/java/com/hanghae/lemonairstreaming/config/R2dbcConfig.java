package com.hanghae.lemonairstreaming.config;// package com.example.lemonairstreaming.config;
// //
//
// import java.time.Duration;
//
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
// import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
// import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
//
// import io.r2dbc.spi.ConnectionFactories;
// import io.r2dbc.spi.ConnectionFactory;
// import io.r2dbc.spi.ConnectionFactoryOptions;
// import io.r2dbc.spi.Option;
//
// @Configuration
// @EnableR2dbcAuditing
// @EnableR2dbcRepositories
// public class R2dbcConfig extends AbstractR2dbcConfiguration {
//
// 	@Value("${spring.r2dbc.url}")
// 	private String host;
//
// 	@Value("${spring.r2dbc.username}")
// 	private String username;
//
// 	@Value("${spring.r2dbc.password}")
// 	private String password;
//
//
//
// 	@Override
// 	@Bean
// 	public ConnectionFactory connectionFactory() {
// 		return ConnectionFactories.get(ConnectionFactoryOptions.builder()
// 			// .option(ConnectionFactoryOptions.DRIVER, "pool")
// 			// .option(ConnectionFactoryOptions.PROTOCOL, "mysql")
// 			.option(ConnectionFactoryOptions.DRIVER, "mysql")
// 			.option(ConnectionFactoryOptions.HOST, host)
// 			.option(ConnectionFactoryOptions.PORT, 3306)
// 			.option(ConnectionFactoryOptions.USER, username)
// 			.option(ConnectionFactoryOptions.PASSWORD, password)
// 			// .option(ConnectionFactoryOptions.DATABASE, database)
// 			.option(Option.valueOf("initialSize"), 10)
// 			.option(Option.valueOf("maxSize"), 20)
// 			.option(Option.valueOf("maxIdleTime"), Duration.ofMinutes(5))
// 			.option(Option.valueOf("maxLifeTime"), Duration.ofMinutes(5))
// 			.option(Option.valueOf("validationQuery"), "select 1+1")
// 			.build());
// 	}
//
// 	// @Override
// 	// @Bean
// 	// public ConnectionFactory connectionFactory() {
// 	// 	return ConnectionFactories.get(
// 	// 		ConnectionFactoryOptions.builder()
// 	// 			.option(DRIVER, "postgresql")
// 	// 			.option(HOST, host)
// 	// 			.option(PORT, 5432)
// 	// 			.option(USER, username)
// 	// 			.option(PASSWORD, password)
// 	// 			.option(DATABASE, database)
// 	// 			.build()
// 	// 	);
// 	// }
// 	//
// 	// @Bean
// 	// public R2dbcEntityTemplate r2dbcEntityTemplate(ConnectionFactory connectionFactory) {
// 	// 	return new R2dbcEntityTemplate(connectionFactory);
// 	// }
// }