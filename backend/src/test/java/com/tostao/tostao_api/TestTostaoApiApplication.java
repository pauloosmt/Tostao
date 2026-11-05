package com.tostao.tostao_api;

import org.springframework.boot.SpringApplication;

public class TestTostaoApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(TostaoApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
