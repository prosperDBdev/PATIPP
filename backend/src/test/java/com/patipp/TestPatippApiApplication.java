package com.patipp;

import org.springframework.boot.SpringApplication;

public class TestPatippApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(PatippApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
