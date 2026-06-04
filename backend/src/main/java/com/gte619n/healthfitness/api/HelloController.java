package com.gte619n.healthfitness.api;

import com.gte619n.healthfitness.core.HelloService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {
    private final HelloService hello;

    public HelloController(HelloService hello) {
        this.hello = hello;
    }

    @GetMapping("/hello")
    public HelloResponse hello() {
        return new HelloResponse(hello.greeting(), Instant.now());
    }
}
