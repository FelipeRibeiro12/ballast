package com.felipe.ballast;

import org.springframework.boot.SpringApplication;

public class TestBallastApplication {

	public static void main(String[] args) {
		SpringApplication.from(BallastApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
