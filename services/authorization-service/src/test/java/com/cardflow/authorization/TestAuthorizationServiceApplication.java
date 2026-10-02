package com.cardflow.authorization;

import org.springframework.boot.SpringApplication;

public class TestAuthorizationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(AuthorizationServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
