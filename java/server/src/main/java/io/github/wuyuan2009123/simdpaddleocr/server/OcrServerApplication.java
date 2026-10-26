package io.github.wuyuan2009123.simdpaddleocr.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OcrServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OcrServerApplication.class, args);
    }
}
